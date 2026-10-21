package core

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.ParseMode
import com.github.kotlintelegrambot.entities.ReplyMarkup
import core.ui.screenPhoto
import core.ui.screenText
import data.models.UserProfile

/** Optional screen artwork. Only real Telegram file IDs should be registered. */
object ImageManager {
    private val images = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun sendMessageWithImage(
        bot: Bot, chat: ChatId, message: String, userProfile: UserProfile?,
        replyMarkup: ReplyMarkup? = null, parseMode: ParseMode? = null,
        screenId: String? = null
    ) {
        val fileId = screenId?.let(images::get)?.takeIf { userProfile?.showMedia != false }
        if (fileId != null) bot.screenPhoto(chat, fileId, message, parseMode, replyMarkup)
        else bot.screenText(chat, message, parseMode, replyMarkup)
    }

    fun register(screenId: String, fileId: String) { images[screenId] = fileId }
}
