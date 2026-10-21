package core.routers

import core.ui.*
import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.ChatId
import core.ImageManager
import core.SessionStore
import core.keyboards.KeyboardFactory
import data.models.*
import data.repositories.UserRepository

object RouterPixelArt {
    fun handlePixelAction(
            bot: Bot,
            chat: ChatId,
            uid: Long,
            text: String,
            users: UserRepository
    ): Boolean {
        when (text) {
            "🖼 Пиксель-арт" -> {
                val profile = users.profile(uid)
                val pixelMessage =
                        "Пиксель-арт пока доступен как отдельный веб-модуль, но раздел уже предусмотрен в меню."
                ImageManager.sendMessageWithImage(bot, chat, pixelMessage, profile, replyMarkup = KeyboardFactory.pixelMenu())
                return true
            }
            "Публичный холст" -> {
                val profile = users.profile(uid)
                ImageManager.sendMessageWithImage(
                        bot,
                        chat,
                        "Публичный холст можно подключить как Web App поверх этого меню.",
                        profile, replyMarkup = KeyboardFactory.pixelMenu())
                return true
            }
            "⬅️ Обратно" -> {
                // Возврат в главное меню
                SessionStore.clear(uid)
                bot.screenText(chat, "Главное меню.", replyMarkup = KeyboardFactory.mainMenu())
                return true
            }
            else -> return false
        }
    }
}
