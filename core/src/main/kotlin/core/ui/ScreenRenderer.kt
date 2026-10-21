package core.ui

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.*
import com.github.kotlintelegrambot.entities.inputmedia.InputMediaPhoto
import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
import core.SessionStore
import core.PendingAction
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * One synchronous transaction per update. Routers describe a screen; Telegram is
 * called once after routing. Search coroutines must be structured (runBlocking),
 * never GlobalScope: otherwise they could write into an unrelated screen.
 */
object ScreenRenderer {
    private data class Key(val chat: Long, val actor: Long)
    private class Conversation {
        val ui = UiSession()
    }
    private class Update(
        val bot: Bot, val chat: Long, val actor: Long, val ui: UiSession,
        val fromCallback: Boolean, val before: SessionSnapshot
    ) {
        val parts = mutableListOf<Screen>()
        val delivered = mutableListOf<() -> Unit>()
        var pushHistory = false
        var restoring = false
        var result = false
        var forceNew = !fromCallback
        var notice: String? = null
    }
    private val conversations = ConcurrentHashMap<Key, Conversation>()
    private val current = ThreadLocal<Update?>()

    fun transaction(bot: Bot, chat: Long, actor: Long, callback: CallbackQuery? = null,
                    block: (String?) -> Unit) {
        val conversation = conversations.computeIfAbsent(Key(chat, actor)) { Conversation() }
        synchronized(conversation) {
            SessionStore.withOwner(chat, actor) {
                val ui = conversation.ui
                val command = if (callback != null) {
                    val action = ui.actions[callback.data]
                    if (callback.message?.messageId != ui.messageId || action == null) {
                        bot.answerCallbackQuery(callback.id, "Этот экран устарел. Открой /menu.")
                        return@withOwner
                    }
                    // Acknowledge before network/database work. Consume all tokens atomically.
                    bot.answerCallbackQuery(callback.id)
                    ui.actions = emptyMap()
                    action
                } else null
                val update = Update(bot, chat, actor, ui, callback != null,
                    SessionSnapshot.of(SessionStore.get(actor)))
                current.set(update)
                try {
                    if (command?.startsWith("page:") == true) {
                        ui.page = command.removePrefix("page:").toIntOrNull() ?: 0
                    } else {
                        block(command)
                        if (update.parts.isNotEmpty()) ui.page = 0
                    }
                    val after = SessionStore.get(actor).action
                    if (!update.restoring && update.before.action != PendingAction.NONE &&
                        update.before.action != PendingAction.PLAY_TEST &&
                        after != PendingAction.NONE && after != update.before.action) {
                        update.pushHistory = true
                    }
                    flush(update)
                } catch (e: Exception) {
                    // Never replay a mutation after a rendering failure.
                    ui.actions = emptyMap()
                    throw e
                } finally {
                    current.remove()
                }
            }
        }
    }

    fun pushHistory() { current.get()?.pushHistory = true }
    fun persistent() { current.get()?.result = true }
    fun afterDelivery(action: () -> Unit) {
        current.get()?.delivered?.add(action) ?: action()
    }
    fun home() {
        current.get()?.let { it.ui.history.clear(); it.pushHistory = false; it.restoring = true }
    }

    fun back(): Boolean {
        val update = current.get() ?: return false
        val entry = update.ui.history.removeLastOrNull() ?: return false
        val session = SessionStore.get(update.actor)
        val draft = session.data.filterKeys { it in setOf("event_title", "event_desc", "event_place", "event_time", "event_max", "test_title", "test_kind", "test_prompt") }
        entry.session.restore(session)
        session.data.putAll(draft)
        update.parts.clear()
        update.parts.add(entry.screen.copy(persistent = false))
        update.pushHistory = false
        update.restoring = true
        return true
    }

    fun cancel(): Boolean {
        val update = current.get() ?: return false
        while (update.ui.history.isNotEmpty()) {
            if (update.ui.history.last().session.action == PendingAction.NONE) return back()
            update.ui.history.removeLast()
        }
        return false
    }

    fun patchKeyboard(keyboard: InlineKeyboardMarkup) {
        val update = checkNotNull(current.get())
        val screen = checkNotNull(update.ui.screen)
        update.parts.clear()
        update.parts.add(screen.copy(keyboard = keyboard))
    }

    fun submit(bot: Bot, chat: ChatId, screen: Screen) {
        val update = current.get()
        check(update != null) { "UI output must run inside ScreenRenderer.transaction" }
        check(chat == ChatId.fromId(update.chat)) { "Use notifyUser for independent notifications" }
        update.parts.add(screen)
    }

    fun notice(text: String?) { if (!text.isNullOrBlank()) current.get()?.notice = text }

    private fun flush(update: Update) {
        val ui = update.ui
        // An ignored update (for example an unrelated sticker) must not copy the
        // previous screen or invalidate its buttons by sending it again.
        if (!update.fromCallback && update.parts.isEmpty()) return
        // A callback that only changes data/acknowledges still gets fresh tokens.
        val parts = update.parts.ifEmpty { listOfNotNull(ui.screen) }
        if (parts.isEmpty()) return
        val photo = parts.lastOrNull { it.photo != null }?.photo
        val text = parts.map { it.text }.filter(String::isNotBlank).distinct().joinToString("\n\n")
        val rows = parts.flatMap { it.keyboard?.inlineKeyboard.orEmpty() }.distinct().toMutableList()
        val pending = SessionStore.get(update.actor).action
        val isForm = isInputAction(pending)
        if (isForm) {
            // Text-entry screens must not offer unrelated menu actions as answers.
            val choices = setOf("SINGLE", "MULTI", "NUMBER", "TEXT", "MATCH", "COLOR", "Концерт", "Митап", "Вечеринка",
                "Другое", "камень", "ножницы", "бумага")
            val filtered = rows.map { row -> row.filter { button ->
                button !is InlineKeyboardButton.CallbackData ||
                    button.callbackData.startsWith("answer:") ||
                    button.callbackData.removePrefix("text:") in choices ||
                    button.callbackData.startsWith("text:✂️ Обрезать") ||
                    button.callbackData == "text:📦 Добавить границы до квадрата"
            } }.filter { it.isNotEmpty() }
            rows.clear()
            rows.addAll(filtered)
            rows.addAll(UiKeyboard.rows(listOf(listOf("❌ Отмена"))).inlineKeyboard)
        }
        val searchActions = setOf(PendingAction.SEARCH_PREDICTIONS, PendingAction.SEARCH_TESTS,
            PendingAction.COLLECTION_QUERY, PendingAction.ADMIN_SEARCH)
        val completedForm = !update.restoring && isInputAction(update.before.action) &&
            update.before.action !in searchActions && !isForm
        if (completedForm || update.result) {
            // A completed form must not be restored and submitted a second time.
            while (ui.history.lastOrNull()?.let { isInputAction(it.session.action) } == true) {
                ui.history.removeLast()
            }
            update.pushHistory = false
        }
        val isMainMenu = rows.any { row ->
            row.any { it is InlineKeyboardButton.CallbackData && it.callbackData == "text:👤 Профиль" }
        }
        val hasBack = rows.any { row ->
            row.any { it is InlineKeyboardButton.CallbackData && it.callbackData == "text:⬅️ Обратно" }
        }
        val hasHome = rows.any { row ->
            row.any { it is InlineKeyboardButton.CallbackData && it.callbackData == "text:🏠 Главное меню" }
        }
        if (!isMainMenu && !hasBack && !hasHome) {
            rows.add(UiKeyboard.rows(listOf(listOf("⬅️ Обратно", "🏠 Главное меню"))).inlineKeyboard.first())
        }
        val screen = Screen(text, InlineKeyboardMarkup.create(rows.toList()), photo,
            parts.lastOrNull { it.parseMode != null }?.parseMode,
            update.result || parts.any { it.persistent })
        val fullText = listOfNotNull(screen.text, update.notice).distinct().joinToString("\n\n")
        val limit = if (photo == null) 3800 else 900
        // Long lists are paged, never silently truncated. Use plain text on long
        // pages so a page boundary cannot split an HTML tag/entity.
        val longText = fullText.length > limit
        val plain = if (longText && screen.parseMode == ParseMode.HTML)
            fullText.replace(Regex("<[^>]*>"), "").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")
            else fullText
        val pages = if (longText) splitPages(plain, limit) else listOf(plain)
        ui.page = ui.page.coerceIn(0, pages.lastIndex)
        val renderedText = pages[ui.page] + if (pages.size > 1) "\n\n${ui.page + 1} / ${pages.size}" else ""
        val parseMode = if (longText) null else screen.parseMode
        if (pages.size > 1) {
            val navigation = mutableListOf<InlineKeyboardButton>()
            if (ui.page > 0) navigation.add(InlineKeyboardButton.CallbackData("← Страница", "page:${ui.page - 1}"))
            if (ui.page < pages.lastIndex) navigation.add(InlineKeyboardButton.CallbackData("Страница →", "page:${ui.page + 1}"))
            rows.add(0, navigation)
        }

        // Keyboard removal needs its own message. Do this once per process/session.
        if (!ui.keyboardRemoved) {
            val removal = update.bot.sendMessage(ChatId.fromId(update.chat),
                "Навигация теперь находится под сообщениями. Открыть меню: /menu",
                replyMarkup = ReplyKeyboardRemove())
            check(removal.isSuccess) { "Could not remove legacy keyboard: $removal" }
            ui.keyboardRemoved = true
        }

        val nonce = UUID.randomUUID().toString().replace("-", "").take(16)
        val actions = mutableMapOf<String, String>()
        var index = 0
        val keyboard = InlineKeyboardMarkup.create(rows.map { row ->
            row.map { button ->
                if (button is InlineKeyboardButton.CallbackData) {
                    val token = "ui:$nonce:${index++}"
                    actions[token] = button.callbackData
                    InlineKeyboardButton.CallbackData(button.text, token)
                } else button
            }
        })
        val old = ui.screen
        var id = ui.messageId
        var send = update.forceNew || id == null || old == null || (old.persistent && update.parts.isNotEmpty()) ||
            (old.photo == null) != (screen.photo == null)
        val chat = ChatId.fromId(update.chat)
        if (!send) {
            val edited = when {
                ui.renderedText == renderedText && ui.renderedParseMode == parseMode && old!!.photo == screen.photo ->
                    update.bot.editMessageReplyMarkup(chatId = chat, messageId = id, replyMarkup = keyboard)
                screen.photo != null ->
                    update.bot.editMessageMedia(chatId = chat, messageId = id,
                        media = InputMediaPhoto(screen.photo, renderedText, parseMode?.modeName),
                        replyMarkup = keyboard)
                else -> update.bot.editMessageText(chatId = chat, messageId = id,
                    text = renderedText, parseMode = parseMode, replyMarkup = keyboard)
            }
            if (edited.first?.body()?.ok != true) {
                val error = edited.first?.errorBody()?.string().orEmpty()
                when {
                    error.contains("message is not modified", true) -> Unit
                    error.contains("message to edit not found", true) ||
                        error.contains("message can't be edited", true) -> send = true
                    else -> error("Telegram edit failed; no duplicate sent: $error ${edited.second}")
                }
            }
        }
        if (send) {
            id = if (screen.photo != null) {
                val sent = update.bot.sendPhoto(chat, screen.photo, caption = renderedText,
                    parseMode = parseMode, replyMarkup = keyboard)
                check(sent.first?.body()?.ok == true) { "Telegram photo failed: ${sent.second}" }
                sent.first?.body()?.result?.messageId
            } else {
                val sent = update.bot.sendMessage(chat, renderedText,
                    parseMode = parseMode, replyMarkup = keyboard)
                check(sent.isSuccess) { "Telegram send failed: $sent" }
                sent.get().messageId
            }
            check(id != null) { "Telegram returned no message id" }
            ui.messageId?.let { previous ->
                update.bot.editMessageReplyMarkup(chatId = chat, messageId = previous,
                    replyMarkup = InlineKeyboardMarkup.create(emptyList<List<InlineKeyboardButton>>()))
            }
        }
        if (update.pushHistory && old != null && !old.persistent) {
            ui.history.addLast(HistoryEntry(old, update.before))
            while (ui.history.size > 30) ui.history.removeFirst()
        }
        ui.messageId = id
        ui.screen = screen
        ui.actions = actions
        ui.renderedText = renderedText
        ui.renderedParseMode = parseMode
        update.delivered.forEach { it() }
    }

    private fun isInputAction(action: PendingAction): Boolean = action in setOf(
        PendingAction.EDIT_NAME, PendingAction.EDIT_USERNAME, PendingAction.EDIT_BIO, PendingAction.EDIT_AVATAR,
        PendingAction.AVATAR_PROCESSING, PendingAction.ADD_MEME, PendingAction.FEEDBACK_TEXT,
        PendingAction.SEARCH_PREDICTIONS, PendingAction.SEARCH_TESTS, PendingAction.COLLECTION_QUERY,
        PendingAction.CREATE_TEST_TITLE, PendingAction.CREATE_TEST_AUTHOR, PendingAction.CREATE_TEST_COUNT,
        PendingAction.CREATE_TEST_KIND, PendingAction.CREATE_TEST_PROMPT,
        PendingAction.CREATE_TEST_MORE, PendingAction.CREATE_EVENT_TITLE, PendingAction.CREATE_EVENT_DESC,
        PendingAction.CREATE_EVENT_PLACE, PendingAction.CREATE_EVENT_TIME, PendingAction.CREATE_EVENT_MAX,
        PendingAction.CREATE_EVENT_KIND, PendingAction.RPS_CHOICE, PendingAction.PLAY_TEST,
        PendingAction.ADMIN_SEARCH, PendingAction.ADMIN_EDIT_NAME, PendingAction.ADMIN_EDIT_USERNAME,
        PendingAction.ADMIN_EDIT_BIO, PendingAction.ADMIN_EDIT_BAN_REASON
    )

    internal fun splitPages(text: String, limit: Int): List<String> {
        val pages = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = (start + limit).coerceAtMost(text.length)
            if (end < text.length && Character.isHighSurrogate(text[end - 1])) end--
            pages.add(text.substring(start, end))
            start = end
        }
        return pages.ifEmpty { listOf("") }
    }
}

fun Bot.screenText(chatId: ChatId, text: String, parseMode: ParseMode? = null,
                   replyMarkup: ReplyMarkup? = null) =
    ScreenRenderer.submit(this, chatId, Screen(text, replyMarkup as? InlineKeyboardMarkup, parseMode = parseMode))

fun Bot.screenPhoto(chatId: ChatId, photo: TelegramFile, caption: String? = null,
                    parseMode: ParseMode? = null, replyMarkup: ReplyMarkup? = null) =
    ScreenRenderer.submit(this, chatId, Screen(caption.orEmpty(), replyMarkup as? InlineKeyboardMarkup, photo, parseMode))

fun Bot.screenPhoto(chatId: ChatId, photo: String, caption: String? = null,
                    parseMode: ParseMode? = null, replyMarkup: ReplyMarkup? = null) =
    screenPhoto(chatId, TelegramFile.ByFileId(photo), caption, parseMode, replyMarkup)

fun Bot.screenPhoto(chatId: ChatId, photo: File, caption: String? = null,
                    parseMode: ParseMode? = null, replyMarkup: ReplyMarkup? = null) =
    screenPhoto(chatId, TelegramFile.ByFile(photo), caption, parseMode, replyMarkup)

fun Bot.callbackNotice(callbackQueryId: String, text: String? = null, showAlert: Boolean? = null) {
    // The central dispatcher already acknowledged the query. Keep notices in the screen.
    ScreenRenderer.notice(text)
}
