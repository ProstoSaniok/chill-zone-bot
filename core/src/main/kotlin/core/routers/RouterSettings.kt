package core.routers

import data.models.*
import data.repositories.UserRepository
import core.ui.*
import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.ParseMode
import core.keyboards.KeyboardFactory
import core.ImageManager
import core.Session
import core.PendingAction

object RouterSettings {
    fun handleSettingsAction(bot: Bot, chat: ChatId, uid: Long, text: String, users: UserRepository): Boolean {
        when (text) {
            "⚙️ Настройки" -> {
                val profile = users.profile(uid)
                val settingsMessage = "Настройки."
                bot.screenText(chat, settingsMessage, replyMarkup = KeyboardFactory.settingsMenu(profile))
                return true
            }
            "Включить картинки [✅]", "Выключить картинки [❌]" -> {
                val enabled = users.toggleMedia(uid)
                val profile = users.profile(uid)
                bot.screenText(chat, "Настройки.", replyMarkup = KeyboardFactory.settingsMenu(profile))
                return true
            }
            "⬅️ Обратно" -> {
                // Возврат в профиль пользователя
                RouterProfile.showProfile(bot, chat, uid, users)
                return true
            }
            else -> return false
        }
    }
}