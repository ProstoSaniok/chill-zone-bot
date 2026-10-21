package core.routers

import core.ui.*
import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.CallbackQuery
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.TelegramFile
import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import com.github.kotlintelegrambot.entities.inputmedia.InputMediaPhoto
import core.FSMContext
import core.PendingAction
import core.Session
import core.SessionStore
import core.keyboards.KeyboardFactory
import core.keyboards.KeyboardMemes
import data.models.MemeItem
import data.repositories.MemeRepository
import data.repositories.UserRepository
import data.services.AdminService

object RouterMemes {
    private val navigation = setOf(
        "🎲 Смотреть мемы", "🎲 Следующий мем", "⭐ Избранные мемы", "➕ Добавить мем",
        "⬅️ К мемам", "❌ Отменить добавление", "🏠 Главное меню"
    )

    fun isNavigation(text: String): Boolean = text in navigation

    private fun enter(session: Session, screen: String) {
        session.action = PendingAction.NONE
        session.context = FSMContext.MEMES
        session.data["meme_screen"] = screen
    }

    fun showMenu(bot: Bot, chat: ChatId, uid: Long, notice: String? = null, session: Session = SessionStore.get(uid)) {
        enter(session, "menu")
        bot.screenText(chat, listOfNotNull(notice,
            "😂 Мемы\n\n🎲 Смотри ленту и оценивай мемы.\n⭐ Сохраняй понравившееся в избранное.\n➕ Делись своими мемами."
        ).joinToString("\n\n"), replyMarkup = KeyboardFactory.memesMenu())
    }

    private fun actions(uid: Long, meme: MemeItem, memes: MemeRepository, index: Int? = null, total: Int = 0) =
        memes.counts(meme.id).let { counts ->
            KeyboardMemes.memeActionsInline(meme.id, counts.likes, counts.dislikes,
                memes.isFavorite(uid, meme.id), memes.userVote(uid, meme.id), index, total)
        }

    private fun caption(meme: MemeItem, index: Int? = null, total: Int = 0): String {
        val heading = if (index == null) "😂 Мем #${meme.id}" else "⭐ Избранные мемы · ${index + 1} из $total"
        return (heading + if (meme.caption.isBlank()) "" else "\n\n${meme.caption}").take(1024)
    }

    private fun showCard(
        bot: Bot, chat: ChatId, uid: Long, meme: MemeItem, memes: MemeRepository,
        messageId: Long? = null, index: Int? = null, total: Int = 0
    ): Boolean {
        val markup = actions(uid, meme, memes, index, total)
        bot.screenPhoto(chat, TelegramFile.ByFileId(meme.fileId), caption = caption(meme, index, total),
            replyMarkup = UiKeyboard.combine(markup, UiKeyboard.rows(listOf(listOf("⬅️ К мемам")))))
        ScreenRenderer.afterDelivery { SessionStore.get(uid).data["last_meme"] = meme.id.toString() }
        return true
    }

    fun showMeme(bot: Bot, chat: ChatId, uid: Long, memes: MemeRepository, session: Session = SessionStore.get(uid)) {
        val entering = session.data["meme_screen"] != "feed" || session.action != PendingAction.NONE
        enter(session, "feed")
        val meme = memes.randomUnseen(uid)
        if (meme == null) {
            bot.screenText(chat, "В ленте пока пусто. Добавь первый мем через «➕ Добавить мем».", replyMarkup = KeyboardFactory.memesViewerMenu())
            return
        }
        if (showCard(bot, chat, uid, meme, memes)) ScreenRenderer.afterDelivery { memes.markSeen(uid, meme.id) }
    }

    fun rateLastMeme(bot: Bot, chat: ChatId, uid: Long, vote: Int, memes: MemeRepository) {
        val meme = lastMeme(uid, memes) ?: return showMenu(bot, chat, uid, "Сначала открой ленту мемов.")
        memes.vote(uid, meme.id, vote)
        bot.screenText(chat, "Оценка сохранена. Оценивать мемы также можно кнопками под картинкой.")
    }

    fun favoriteLastMeme(bot: Bot, chat: ChatId, uid: Long, memes: MemeRepository) {
        val meme = lastMeme(uid, memes) ?: return showMenu(bot, chat, uid, "Сначала открой ленту мемов.")
        val added = memes.toggleFavorite(uid, meme.id)
        bot.screenText(chat, if (added) "⭐ Мем сохранён в избранном." else "Мем убран из избранного.")
    }

    fun showFavorites(bot: Bot, chat: ChatId, uid: Long, memes: MemeRepository, session: Session = SessionStore.get(uid)) {
        enter(session, "favorites")
        val favorites = memes.favorites(uid)
        if (favorites.isEmpty()) bot.screenText(chat, "⭐ Избранное пусто. Сохраняй понравившиеся мемы из ленты.",
            replyMarkup = KeyboardFactory.memesFavoritesMenu())
        favorites.firstOrNull()?.let { showCard(bot, chat, uid, it, memes, index = 0, total = favorites.size) }
    }

    fun handleCallback(bot: Bot, callback: CallbackQuery, memes: MemeRepository) {
        val message = callback.message ?: return
        val chat = ChatId.fromId(message.chat.id)
        val uid = callback.from.id
        val session = SessionStore.get(uid)
        val activeUid = session.data["profile_impersonated_user_id"]?.toLongOrNull() ?: uid
        if (AdminService.isUserBanned(uid) || AdminService.isUserBanned(activeUid)) {
            bot.callbackNotice(callback.id, "Доступ к мемам ограничен.")
            return
        }
        if (session.action != PendingAction.NONE || session.context != FSMContext.MEMES || session.data["meme_screen"] == null) {
            bot.callbackNotice(callback.id, "Сначала открой раздел «😂 Мемы» и заверши текущее действие.")
            return
        }
        val data = callback.data
        if (data == "meme_next") {
            bot.callbackNotice(callback.id)
            showMeme(bot, chat, activeUid, memes, session)
            return
        }
        if (data == "meme_position") {
            bot.callbackNotice(callback.id, "Листай избранное стрелками рядом.")
            return
        }
        val parts = data.split("_")
        val meme = parts.getOrNull(2)?.toLongOrNull()?.let(memes::getById)
        if (meme == null) {
            bot.callbackNotice(callback.id, "Мем больше недоступен. Открой ленту заново.")
            return
        }
        val savedCard = parts.lastOrNull() == "saved" || data.startsWith("meme_page_")
        val favorites = if (savedCard) memes.favorites(activeUid) else emptyList()
        val index = favorites.indexOfFirst { it.id == meme.id }
        if (savedCard && index < 0) {
            bot.callbackNotice(callback.id, "Этот мем уже убран. Открой «⭐ Избранные мемы» заново.")
            return
        }
        when (parts.getOrNull(1)) {
            "vote" -> {
                val vote = parts.getOrNull(3)?.toIntOrNull()?.takeIf { it == 1 || it == -1 } ?: return
                if (memes.userVote(activeUid, meme.id) == vote) {
                    bot.callbackNotice(callback.id, "Ты уже так оценил этот мем.")
                    return
                }
                memes.vote(activeUid, meme.id, vote)
                bot.callbackNotice(callback.id, "Оценка сохранена")
                ScreenRenderer.patchKeyboard(UiKeyboard.combine(actions(activeUid, meme, memes, if (savedCard) index else null, favorites.size), UiKeyboard.rows(listOf(listOf("⬅️ К мемам")))))
            }
            "fav" -> {
                val added = memes.toggleFavorite(activeUid, meme.id)
                bot.callbackNotice(callback.id, if (added) "⭐ Сохранено в избранном" else "Убрано из избранного")
                if (savedCard && !added) {
                    val remaining = memes.favorites(activeUid)
                    if (remaining.isEmpty()) {
                        bot.screenText(chat, "⭐ Избранное теперь пусто. Сохраняй понравившиеся мемы из ленты.",
                            replyMarkup = KeyboardFactory.memesFavoritesMenu())
                    } else {
                        val next = index.coerceAtMost(remaining.lastIndex)
                        showCard(bot, chat, activeUid, remaining[next], memes, message.messageId, next, remaining.size)
                    }
                } else {
                    ScreenRenderer.patchKeyboard(UiKeyboard.combine(actions(activeUid, meme, memes), UiKeyboard.rows(listOf(listOf("⬅️ К мемам")))))
                }
            }
            "page" -> {
                val step = parts.getOrNull(3)?.toIntOrNull()?.takeIf { it == -1 || it == 1 } ?: return
                val next = (index + step).coerceIn(0, favorites.lastIndex)
                bot.callbackNotice(callback.id)
                if (next != index) showCard(bot, chat, activeUid, favorites[next], memes, message.messageId, next, favorites.size)
            }
            else -> bot.callbackNotice(callback.id, "Неизвестная команда.")
        }
    }

    fun handleMemeAction(
        bot: Bot, chat: ChatId, uid: Long, text: String, memes: MemeRepository,
        users: UserRepository, session: Session
    ): Boolean {
        when (text) {
            "😂 Мемы", "⬅️ К мемам", "❌ Отменить добавление", "⬅️ Обратно" -> showMenu(bot, chat, uid, session = session)
            "🎲 Смотреть мемы", "🎲 Следующий мем", "Следующий мем" -> showMeme(bot, chat, uid, memes, session)
            "⭐ Избранные мемы", "Мои избр. мемы", "Мои избранные" -> showFavorites(bot, chat, uid, memes, session)
            "➕ Добавить мем", "Добавить мем" -> {
                enter(session, "add")
                session.action = PendingAction.ADD_MEME
                bot.screenText(chat, "➕ Добавить мем\n\nОтправь одно фото. Подпись можно добавить к этому же сообщению.\nМем появится в общей ленте сразу после отправки.\n\nПередумал? Нажми «❌ Отменить добавление».", replyMarkup = KeyboardFactory.memesAddMenu())
            }
            "🏠 Главное меню" -> {
                session.action = PendingAction.NONE
                session.data.remove("meme_screen")
                session.data.remove("last_meme")
                bot.screenText(chat, "Главное меню.", replyMarkup = KeyboardFactory.mainMenu())
            }
            "👍" -> rateLastMeme(bot, chat, uid, 1, memes)
            "👎" -> rateLastMeme(bot, chat, uid, -1, memes)
            "💾 Избр. мем" -> favoriteLastMeme(bot, chat, uid, memes)
            else -> {
                if (session.action != PendingAction.ADD_MEME) return false
                bot.screenText(chat, "Жду фото мема. Подпись добавь при отправке фото. Для выхода нажми «❌ Отменить добавление».", replyMarkup = KeyboardFactory.memesAddMenu())
            }
        }
        return true
    }

    private fun lastMeme(uid: Long, memes: MemeRepository): MemeItem? =
        SessionStore.get(uid).data["last_meme"]?.toLongOrNull()?.let(memes::getById)
}
