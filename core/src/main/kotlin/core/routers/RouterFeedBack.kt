package core.routers

import core.ui.*
import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.ParseMode
import core.ImageManager
import core.PendingAction
import core.Session
import core.SessionStore
import core.keyboards.KeyboardFactory
import data.models.*
import data.repositories.FeedbackRepository
import data.repositories.UserRepository
import data.services.ModerationService

object RouterFeedBack {
    fun handleFeedbackAction(
            bot: Bot,
            chat: ChatId,
            uid: Long,
            text: String,
            feedback: FeedbackRepository,
            users: UserRepository,
            session: Session
    ): Boolean {
        when (text) {
            "💬 Обратная связь" -> {
                val profile = users.profile(uid)
                ImageManager.sendMessageWithImage(
                        bot,
                        chat,
                        "Есть вопрос или предложение? Нажми «Оставить отзыв» и напиши нам.",
                        profile, replyMarkup = KeyboardFactory.feedbackMenu())
                return true
            }
            "Оставить отзыв" -> {
                session.action = PendingAction.FEEDBACK_TEXT
                val profile = users.profile(uid)
                ImageManager.sendMessageWithImage(
                        bot,
                        chat,
                        "Напиши отзыв одним сообщением.",
                        profile, replyMarkup = KeyboardFactory.feedbackMenu())
                return true
            }
            "Посмотреть поддержку" -> {
                val profile = users.profile(uid)
                ImageManager.sendMessageWithImage(
                        bot,
                        chat,
                        "Есть вопрос или предложение? Нажми «Оставить отзыв» и напиши нам.",
                        profile, replyMarkup = KeyboardFactory.feedbackMenu())
                return true
            }
            "⬅️ Обратно" -> {
                // Возврат в главное меню
                SessionStore.clear(uid)
                bot.screenText(chat, "Главное меню.", replyMarkup = KeyboardFactory.mainMenu())
                return true
            }
            else -> {
                if (session.action == PendingAction.FEEDBACK_TEXT) {
                    val moderationResult = ModerationService.checkText(uid, text)
                    if (!moderationResult.isAllowed) {
                        if (moderationResult.shouldBan) {
                            ModerationService.banForViolation(
                                    uid,
                                    "Использование ненормативной лексики"
                            )
                            ModerationService.clearWarnings(uid)
                            bot.screenText(
                                    chat,
                                    "🚫 Вы были заблокированы за использование ненормативной лексики."
                            )
                        } else {
                            ModerationService.addWarning(uid)
                            bot.screenText(
                                    chat,
                                    "⚠️ <b>Предупреждение!</b>\n\nВаше сообщение содержит недопустимый контент.\n\nПовторное нарушение приведёт к автоматическому бану на 1 сутки.",
                                    parseMode = ParseMode.HTML
                            )
                        }
                        session.action = PendingAction.NONE
                        return true
                    }
                    ScreenRenderer.persistent()
                    feedback.save(uid, "", scope = "general", text = text)
                    session.action = PendingAction.NONE
                    bot.screenText(
                            chat,
                            "✅ Спасибо за отзыв!",
                            replyMarkup = KeyboardFactory.feedbackMenu()
                    )
                    return true
                }
                return false
            }
        }
    }
}
