package core

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.*
import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import core.keyboards.KeyboardFactory
import core.ui.*
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

/** Exercises the real Telegram client against a local HTTP stub. No token or database. */
class ReplyClassTest {
    private lateinit var server: HttpServer
    private lateinit var bot: Bot
    private val requests = Collections.synchronizedList(mutableListOf<Pair<String, Map<String, String>>>())
    private var nextId = 0L
    private var editError: String? = null
    private val actor = sequence.incrementAndGet()
    private val chat get() = ChatId.fromId(actor)
    private val gson = Gson()

    @BeforeTest fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val method = exchange.requestURI.path.substringAfterLast('/')
            val body = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
            val params = body.split("&").associate { pair ->
                URLDecoder.decode(pair.substringBefore('='), "UTF-8") to
                    URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
            }
            requests.add(method to params)
            val failure = if (method.startsWith("edit")) editError.also { editError = null } else null
            val response = if (failure != null) {
                """{"ok":false,"error_code":400,"description":"$failure"}"""
            } else if (method == "answerCallbackQuery") """{"ok":true,"result":true}"""
            else {
                val id = if (method.startsWith("send")) ++nextId else params["message_id"]?.toLongOrNull() ?: nextId
                """{"ok":true,"result":{"message_id":$id,"date":1,"chat":{"id":$actor,"type":"private"},"text":"screen"}}"""
            }
            val bytes = response.toByteArray()
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(if (failure == null) 200 else 400, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        bot = Bot.Builder().apply { token = "test"; apiUrl = "http://127.0.0.1:${server.address.port}/" }.build()
    }

    @AfterTest fun stop() { server.stop(0) }

    private fun screen(text: String = "Menu") {
        bot.screenText(chat, text, replyMarkup = UiKeyboard.rows(listOf(listOf("Open"))))
    }
    private fun input(block: () -> Unit = { screen() }) {
        ScreenRenderer.transaction(bot, actor, actor) { block() }
    }
    private fun callback(token: String = token(), user: Long = actor): CallbackQuery = gson.fromJson(
        """{"id":"callback","from":{"id":$user,"is_bot":false,"first_name":"Tester"},"chat_instance":"test","data":"$token","message":{"message_id":$nextId,"date":1,"chat":{"id":$actor,"type":"private"}}}""",
        CallbackQuery::class.java
    )
    private fun token(label: String = "Open"): String {
        val markup = requests.last { it.second["reply_markup"]?.contains("callback_data") == true }.second.getValue("reply_markup")
        val rows = JsonParser().parse(markup).asJsonObject.getAsJsonArray("inline_keyboard")
        return rows.flatMap { it.asJsonArray.toList() }.first { it.asJsonObject["text"].asString == label }
            .asJsonObject["callback_data"].asString
    }
    private fun click(query: CallbackQuery = callback(), block: () -> Unit = { screen("Next") }) {
        ScreenRenderer.transaction(bot, actor, query.from.id, query) { block() }
    }
    private fun count(method: String) = requests.count { it.first == method }

    @Test fun navigationButtonsAreNotDuplicated() {
        input {
            bot.screenText(chat, "Главное меню", replyMarkup = KeyboardFactory.mainMenu())
        }
        val mainMarkup = requests.last { it.second["reply_markup"]?.contains("callback_data") == true }
            .second.getValue("reply_markup")
        val mainButtons = JsonParser().parse(mainMarkup).asJsonObject
            .getAsJsonArray("inline_keyboard")
            .flatMap { it.asJsonArray.toList() }
            .map { it.asJsonObject["text"].asString }
        assertFalse(mainButtons.contains("⬅️ Обратно"))
        assertFalse(mainButtons.contains("🏠 Главное меню"))

        input {
            bot.screenText(chat, "Вложенный экран",
                replyMarkup = UiKeyboard.rows(listOf(listOf("⬅️ Обратно"))))
        }
        val nestedMarkup = requests.last { it.second["reply_markup"]?.contains("callback_data") == true }
            .second.getValue("reply_markup")
        val nestedButtons = JsonParser().parse(nestedMarkup).asJsonObject
            .getAsJsonArray("inline_keyboard")
            .flatMap { it.asJsonArray.toList() }
            .map { it.asJsonObject["text"].asString }
        assertEquals(1, nestedButtons.count { it == "⬅️ Обратно" })
    }

    @Test fun navigationEditsAndAcknowledgesBeforeWork() {
        input()
        val before = requests.size
        click()
        assertEquals("answerCallbackQuery", requests[before].first)
        assertEquals(2, count("sendMessage")) // one migration notice, one screen
        assertEquals(1, count("editMessageText"))
        assertTrue(token().toByteArray().size <= 64)
    }

    @Test fun oldAndForeignButtonsNeverExecute() {
        input()
        val old = callback()
        click(old)
        var executed = false
        click(old) { executed = true }
        click(callback(user = actor + 99999)) { executed = true }
        assertFalse(executed)
    }

    @Test fun textInputCreatesScreenBelowInputAndRetiresOldKeyboard() {
        input()
        input { screen("Answer") }
        assertEquals(3, count("sendMessage"))
        assertEquals(1, count("editMessageReplyMarkup"))
    }

    @Test fun resultSurvivesNavigation() {
        input()
        click { ScreenRenderer.persistent(); screen("Result") }
        assertEquals(1, count("editMessageText"))
        click { screen("Menu again") }
        assertEquals(3, count("sendMessage"))
        assertEquals(1, count("editMessageText"))
    }

    @Test fun fragmentsAndKeyboardBecomeOneMessage() {
        input {
            screen("Card")
            bot.screenText(chat, "", replyMarkup = UiKeyboard.rows(listOf(listOf("Save"))))
        }
        assertEquals(2, count("sendMessage"))
        assertTrue(token("Save").startsWith("ui:"))
        assertEquals("Card", requests.last().second["text"])
    }

    @Test fun missingScreenFallsBackOnceButOtherErrorsDoNotDuplicate() {
        input()
        editError = "Bad Request: message to edit not found"
        click()
        assertEquals(3, count("sendMessage"))
        editError = "Bad Request: can't parse entities"
        assertFails { click { screen("Broken") } }
        assertEquals(3, count("sendMessage"))
    }

    @Test fun duplicateEditIsNotAnError() {
        input()
        editError = "Bad Request: message is not modified"
        click()
        assertEquals(2, count("sendMessage"))
    }

    @Test fun backRestoresFormAndKeepsEnteredDraft() {
        input { SessionStore.get(actor).action = PendingAction.CREATE_EVENT_TITLE; screen("Title?") }
        click(callback(token("⬅️ Обратно"))) {
            ScreenRenderer.pushHistory()
            SessionStore.get(actor).data["event_title"] = "Party"
            SessionStore.get(actor).action = PendingAction.CREATE_EVENT_DESC
            screen("Description?")
        }
        click(callback(token("⬅️ Обратно"))) { assertTrue(ScreenRenderer.back()) }
        SessionStore.withOwner(actor, actor) {
            assertEquals(PendingAction.CREATE_EVENT_TITLE, SessionStore.get(actor).action)
            assertEquals("Party", SessionStore.get(actor).data["event_title"])
        }
    }

    @Test fun sessionIsScopedByChatAndActorAndClearPreservesAccount() {
        SessionStore.withOwner(10, actor) {
            SessionStore.get(999).data["profile_impersonated_user_id"] = "7"
            SessionStore.get(actor).action = PendingAction.EDIT_NAME
            SessionStore.clear(actor)
            assertEquals("7", SessionStore.get(actor).data["profile_impersonated_user_id"])
        }
        SessionStore.withOwner(11, actor) { assertNull(SessionStore.get(actor).data["profile_impersonated_user_id"]) }
    }

    @Test fun longScreenPagesWithoutLosingContent() {
        val content = "🙂".repeat(5000)
        assertEquals(content, ScreenRenderer.splitPages(content, 901).joinToString(""))
        assertTrue(ScreenRenderer.splitPages(content, 901).all { it.length <= 901 && !it.last().isHighSurrogate() })
        input { screen(content) }
        val oldSends = count("sendMessage")
        click(callback(token("Страница →"))) { fail("Pagination should be handled by renderer") }
        assertEquals(oldSends, count("sendMessage"))
        assertTrue(requests.last().second.getValue("text").length < 4096)
        assertTrue(requests.last().second.getValue("text").endsWith("2 / 3"))
        click(callback(token("← Страница"))) { fail("Pagination should be handled by renderer") }
        assertEquals("editMessageText", requests.last().first)
        assertTrue(requests.last().second.getValue("text").endsWith("1 / 3"))
    }

    @Test fun dismissingNoticeRestoresTheActualText() {
        input()
        click { ScreenRenderer.notice("Saved") }
        assertEquals("Menu\n\nSaved", requests.last().second["text"])
        click { }
        assertEquals("editMessageText", requests.last().first)
        assertEquals("Menu", requests.last().second["text"])
    }

    @Test fun ignoredInputDoesNotResendOrInvalidateScreen() {
        input()
        val previous = callback()
        val before = requests.size
        input { }
        assertEquals(before, requests.size)
        var executed = false
        click(previous) { executed = true; screen("Still works") }
        assertTrue(executed)
    }

    @Test fun completedFormCannotBeOpenedAgainWithBack() {
        input()
        click {
            ScreenRenderer.pushHistory()
            SessionStore.get(actor).action = PendingAction.CREATE_EVENT_TITLE
            screen("Title?")
        }
        input {
            SessionStore.get(actor).action = PendingAction.CREATE_EVENT_DESC
            screen("Description?")
        }
        click(callback(token("⬅️ Обратно"))) {
            SessionStore.get(actor).action = PendingAction.NONE
            ScreenRenderer.persistent()
            screen("Created")
        }
        click { assertTrue(ScreenRenderer.back()) }
        SessionStore.withOwner(actor, actor) {
            assertEquals(PendingAction.NONE, SessionStore.get(actor).action)
        }
        assertEquals("Menu", requests.last { it.first == "sendMessage" }.second["text"])
    }

    @Test fun homeDuringFormDoesNotPutFormBackInHistory() {
        input { SessionStore.get(actor).action = PendingAction.CREATE_EVENT_TITLE; screen("Title?") }
        click(callback(token("🏠 Главное меню"))) {
            ScreenRenderer.home()
            SessionStore.clear(actor)
            screen("Home")
        }
        click { assertFalse(ScreenRenderer.back()) }
    }

    @Test fun searchResultsCanReturnToQueryWithoutLosingContext() {
        input()
        click {
            ScreenRenderer.pushHistory()
            SessionStore.get(actor).action = PendingAction.SEARCH_TESTS
            screen("Search?")
        }
        input {
            SessionStore.get(actor).data["test_search_query"] = "Example"
            SessionStore.get(actor).action = PendingAction.TEST_SEARCH_RESULTS
            screen("Results")
        }
        click { assertTrue(ScreenRenderer.back()) }
        SessionStore.withOwner(actor, actor) {
            assertEquals(PendingAction.SEARCH_TESTS, SessionStore.get(actor).action)
        }
    }

    @Test fun invalidSessionAfterRestartRequiresMenu() {
        var executed = false
        click(callback("ui:unknown:0")) { executed = true }
        assertFalse(executed)
        assertEquals(0, count("sendMessage"))
    }

    companion object { private val sequence = AtomicLong(50000) }
}
