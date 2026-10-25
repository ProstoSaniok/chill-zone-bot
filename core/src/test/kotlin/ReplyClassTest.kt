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
    private var photoError: String? = null
    private var photoStatus = 400
    private val actor = sequence.incrementAndGet()
    private val chat get() = ChatId.fromId(actor)
    private val gson = Gson()

    @BeforeTest fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val method = exchange.requestURI.path.substringAfterLast('/')
            val body = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
            val params = if (exchange.requestHeaders.getFirst("Content-Type").orEmpty().startsWith("multipart/")) {
                Regex("name=\"([^\"]+)\".*?\\r\\n\\r\\n(.*?)\\r\\n--", RegexOption.DOT_MATCHES_ALL)
                    .findAll(body).associate { it.groupValues[1] to it.groupValues[2] }
            } else body.split("&").associate { pair ->
                URLDecoder.decode(pair.substringBefore('='), "UTF-8") to
                    URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
            }
            requests.add(method to params)
            val failure = when {
                method.startsWith("edit") -> editError.also { editError = null }
                method == "sendPhoto" -> photoError.also { photoError = null }
                else -> null
            }
            val response = if (failure != null) {
                """{"ok":false,"error_code":400,"description":"$failure"}"""
            } else if (method == "answerCallbackQuery") """{"ok":true,"result":true}"""
            else {
                val id = if (method.startsWith("send")) ++nextId else params["message_id"]?.toLongOrNull() ?: nextId
                """{"ok":true,"result":{"message_id":$id,"date":1,"chat":{"id":$actor,"type":"private"},"text":"screen"}}"""
            }
            val bytes = response.toByteArray()
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(if (failure == null) 200 else if (method == "sendPhoto") photoStatus else 400, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        bot = Bot.Builder().apply { token = "test"; apiUrl = "http://127.0.0.1:${server.address.port}/" }.build()
    }

    @AfterTest fun stop() { if (::server.isInitialized) server.stop(0) }

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

    private fun photo() {
        bot.screenPhoto(chat, "unavailable-photo", "Service",
            replyMarkup = UiKeyboard.rows(listOf(listOf("Open"))))
    }

    @Test fun rejectedPhotoKeepsServiceNavigationWorking() {
        input()
        photoError = "Bad Request: wrong file identifier/HTTP URL specified"
        click { photo() }
        assertEquals(1, count("sendPhoto"))
        assertEquals(2, count("sendMessage"))
        assertEquals("Service", requests.last { it.first == "sendMessage" }.second["text"])
        click { screen("Next service") }
        assertEquals(1, count("editMessageText"))
        assertEquals(1, count("sendPhoto"))
    }

    @Test fun invalidPhotoUrlKeepsProfileNavigationWorking() {
        input()
        photoError = "Bad Request: invalid file HTTP URL specified: URL host is empty"
        click { photo() }
        assertEquals("Service", requests.last { it.first == "sendMessage" }.second["text"])
        click()
        assertEquals(1, count("editMessageText"))
    }

    @Test fun missingAvatarPathsAreNotSentAsTelegramFileIds() {
        listOf("C:\\avatars\\profile.jpg", "/app/avatars/profile.jpg", "mocks/avatars/missing.jpg",
            "file:///tmp/avatar.jpg", "https:///avatar.jpg", "").forEach {
            assertFalse(core.routers.RouterProfile.isRemoteAvatar(it), it)
        }
        assertTrue(core.routers.RouterProfile.isRemoteAvatar("AgAC_valid-file_id"))
        assertTrue(core.routers.RouterProfile.isRemoteAvatar("https://example.com/avatar.jpg"))
    }

    @Test fun rejectedPhotoEditFallsBackToText() {
        input { photo() }
        editError = "Bad Request: wrong file identifier/HTTP URL specified"
        click {
            bot.screenPhoto(chat, "another-photo", "Another service",
                replyMarkup = UiKeyboard.rows(listOf(listOf("Open"))))
        }
        assertEquals(1, count("editMessageMedia"))
        assertEquals(1, count("sendMessage"))
        click()
        assertEquals(1, count("editMessageText"))
    }

    @Test fun otherPhotoFailuresIncludeTelegramReasonWithoutRetrying() {
        input()
        photoError = "Bad Request: can't parse entities"
        val failure = assertFailsWith<IllegalStateException> { click { photo() } }
        assertTrue(failure.message.orEmpty().contains("can't parse entities"))
        assertTrue(failure.message.orEmpty().contains("HTTP 400"))
        assertEquals(1, count("sendMessage"))
    }

    @Test fun serverPhotoFailureDoesNotSendDuplicate() {
        input()
        photoStatus = 500
        photoError = "Internal Server Error"
        assertFailsWith<IllegalStateException> { click { photo() } }
        assertEquals(1, count("sendMessage"))
        assertEquals(1, count("sendPhoto"))
    }

    @Test fun avatarButtonIsDirectlyAboveBack() {
        val rows = core.keyboards.KeyboardProfile.editProfileMenu().inlineKeyboard
        assertEquals("⬅️ Обратно", (rows.last().single() as InlineKeyboardButton.CallbackData).text)
        assertEquals("🖼️ Добавить аватарку", (rows[rows.lastIndex - 1].single() as InlineKeyboardButton.CallbackData).text)
    }

    @Test fun staleFormReturnsToParentWithoutReopeningInput() {
        input()
        click {
            ScreenRenderer.pushHistory()
            SessionStore.get(actor).action = PendingAction.EDIT_NAME
            screen("Name?")
        }
        val old = callback(token("❌ Отмена"))
        input { SessionStore.get(actor).action = PendingAction.NONE; screen("Updated") }
        click(old) { fail("Stale form must not be submitted") }
        assertEquals("Menu", requests.last { it.first == "sendMessage" }.second["text"])
    }

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

    @Test fun navigationEditsAndAcknowledgesOnce() {
        input()
        click()
        assertEquals("answerCallbackQuery", requests.last().first)
        assertEquals(1, count("sendMessage")) // only the screen, without a migration notice
        assertEquals(1, count("editMessageText"))
        assertTrue(token().toByteArray().size <= 64)
    }

    @Test fun oldButtonsRestoreDestinationWithoutExecutingMutation() {
        input()
        val old = callback()
        click(old)
        click(old) { fail("Old action must not execute") }
        assertEquals("Menu", requests.last { it.first == "sendMessage" }.second["text"])
    }

    @Test fun foreignButtonsRecoverToMenu() {
        input()
        val query = callback(user = actor + 99999)
        ScreenRenderer.transaction(bot, actor, query.from.id, query) { command ->
            assertEquals("text:🏠 Главное меню", command)
            screen("Home")
        }
    }

    @Test fun textInputCreatesScreenBelowInputAndKeepsOldKeyboard() {
        input()
        input { screen("Answer") }
        assertEquals(2, count("sendMessage"))
        assertEquals(0, count("editMessageReplyMarkup"))
    }

    @Test fun previousResultButtonsUseTheirOwnContextOnFirstClick() {
        input {
            SessionStore.get(actor).data["prediction_id"] = "first"
            ScreenRenderer.persistent()
            screen("First prediction")
        }
        val first = callback()
        click {
            SessionStore.get(actor).data["prediction_id"] = "second"
            ScreenRenderer.persistent()
            screen("Second prediction")
        }
        val second = callback()
        assertEquals(2, count("sendMessage"))
        assertEquals(0, count("editMessageReplyMarkup"))

        listOf(first to "first", second to "second").forEach { (query, expected) ->
            var executed = false
            ScreenRenderer.transaction(bot, actor, actor, query) { command ->
                assertEquals("text:Open", command)
                assertEquals(expected, SessionStore.get(actor).data["prediction_id"])
                executed = true
                ScreenRenderer.notice("Saved $expected")
            }
            assertTrue(executed)
        }
        assertEquals(2, count("sendMessage"))
    }

    @Test fun resultSurvivesNavigation() {
        input()
        click { ScreenRenderer.persistent(); screen("Result") }
        assertEquals(1, count("editMessageText"))
        click { screen("Menu again") }
        assertEquals(2, count("sendMessage"))
        assertEquals(1, count("editMessageText"))
    }

    @Test fun fragmentsAndKeyboardBecomeOneMessage() {
        input {
            screen("Card")
            bot.screenText(chat, "", replyMarkup = UiKeyboard.rows(listOf(listOf("Save"))))
        }
        assertEquals(1, count("sendMessage"))
        assertTrue(token("Save").startsWith("ui:"))
        assertEquals("Card", requests.last().second["text"])
    }

    @Test fun missingScreenFallsBackOnceButOtherErrorsDoNotDuplicate() {
        input()
        editError = "Bad Request: message to edit not found"
        click()
        assertEquals(2, count("sendMessage"))
        editError = "Bad Request: can't parse entities"
        assertFails { click { screen("Broken") } }
        assertEquals(2, count("sendMessage"))
    }

    @Test fun duplicateEditIsNotAnError() {
        input()
        editError = "Bad Request: message is not modified"
        click()
        assertEquals(1, count("sendMessage"))
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
        assertTrue(requests.last { it.first != "answerCallbackQuery" }.second.getValue("text").length < 4096)
        assertTrue(requests.last { it.first != "answerCallbackQuery" }.second.getValue("text").endsWith("2 / 3"))
        click(callback(token("← Страница"))) { fail("Pagination should be handled by renderer") }
        assertEquals("editMessageText", requests.last { it.first != "answerCallbackQuery" }.first)
        assertTrue(requests.last { it.first != "answerCallbackQuery" }.second.getValue("text").endsWith("1 / 3"))
    }

    @Test fun noticeUsesTelegramToastWithoutChangingScreenText() {
        input()
        click { ScreenRenderer.notice("Saved") }
        val answer = requests.last { it.first == "answerCallbackQuery" }.second
        assertEquals("Saved", answer["text"])
        assertEquals("false", answer["show_alert"])
        assertFalse(requests.any { it.second["text"]?.contains("Saved") == true && it.first != "answerCallbackQuery" })
    }

    @Test fun backAndStaleButtonsRefreshData() {
        var name = "Old name"
        input {
            ScreenRenderer.refreshOnRestore { screen(name) }
            screen(name)
        }
        val old = callback()
        click { ScreenRenderer.pushHistory(); screen("Edit") }
        name = "New name"
        click { assertTrue(ScreenRenderer.back()) }
        assertEquals(name, requests.last { it.first == "editMessageText" }.second["text"])
        click(old) { fail("Stale action must not execute") }
        assertEquals(name, requests.last { it.first == "sendMessage" }.second["text"])
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
        click(callback(token("❌ Отмена"))) {
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

    @Test fun invalidSessionAfterRestartRecoversToMenu() {
        val query = callback("ui:unknown:0")
        ScreenRenderer.transaction(bot, actor, actor, query) { command ->
            assertEquals("text:🏠 Главное меню", command)
            screen("Home")
        }
        assertEquals("Home", requests.last { it.first == "sendMessage" }.second["text"])
    }

    @Test fun profileButtonAfterRestartRecoversToProfile() {
        val query = callback("ui:2:expired:0")
        ScreenRenderer.transaction(bot, actor, actor, query) { command ->
            assertEquals("text:✏️ Изменить профиль", command)
            screen("Updated profile")
        }
        assertEquals("Updated profile", requests.last { it.first == "sendMessage" }.second["text"])
    }

    companion object { private val sequence = AtomicLong(50000) }
}
