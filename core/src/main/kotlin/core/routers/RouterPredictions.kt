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
import data.repositories.PredictionRepository
import data.repositories.UserRepository

object RouterPredictions {
    fun givePrediction(
            bot: Bot,
            chat: ChatId,
            uid: Long,
            predictions: PredictionRepository,
            users: UserRepository
    ) {
        val p = predictions.randomWeighted()
        if (p == null) {
            bot.screenText(
                    chat,
                    "Пока нет предсказаний.",
                    replyMarkup = KeyboardFactory.predictionsMenu()
            )
            return
        }
        ScreenRenderer.persistent()
        predictions.collect(uid, p.id)
        users.addRating(uid, 1)
        bot.screenText(
                chat,
                " ${p.text}\n\nРедкость: ${p.rarity}",
                parseMode = ParseMode.HTML,
                replyMarkup = KeyboardFactory.predictionProcessMenu()
        )
    }

    fun showCollectedPredictions(
            bot: Bot,
            chat: ChatId,
            uid: Long,
            predictions: PredictionRepository
    ) {
        val list = predictions.listCollected(uid)
        if (list.isEmpty()) {
            bot.screenText(
                    chat,
                    "Коллекция предсказаний пуста.",
                    replyMarkup = KeyboardFactory.predictionsMenu()
            )
            return
        }
        bot.screenText(
                chat,
                list.joinToString("\n\n") { "• ${it.rarity}: ${it.text}" },
                parseMode = ParseMode.HTML,
                replyMarkup = KeyboardFactory.predictionsMenu()
        )
    }

    fun showPredictionMenu(bot: Bot, chat: ChatId, uid: Long, users: UserRepository) {
        val profile = users.profile(uid)
        val session = SessionStore.get(uid)
        session.data["last_menu"] = "predictions"
        val menuMessage =
                """🔮 <b>Меню предсказаний</b>

Здесь вы можете:
• <b>Получить предсказание</b> - получить случайное предсказание с редкостью
• <b>Мои предсказания</b> - просмотреть собранную коллекцию предсказаний
• <b>Поиск предсказаний</b> - найти предсказания по тексту или редкости

Выберите действие:"""
        ImageManager.sendMessageWithImage(bot, chat, menuMessage, profile, parseMode = ParseMode.HTML, replyMarkup = KeyboardFactory.predictionsMenu())
    }

    fun handlePredictionAction(
            bot: Bot,
            chat: ChatId,
            uid: Long,
            text: String,
            predictions: PredictionRepository,
            users: UserRepository,
            session: Session
    ): Boolean {
        when (text) {
            "🔮 Предсказания" -> {
                showPredictionMenu(bot, chat, uid, users)
                return true
            }
            "Получить предсказание" -> {
                givePrediction(bot, chat, uid, predictions, users)
                return true
            }
            "Мои предсказания" -> {
                showCollectedPredictions(bot, chat, uid, predictions)
                return true
            }
            "Поиск предсказаний" -> {
                session.action = PendingAction.SEARCH_PREDICTIONS
                val profile = users.profile(uid)
                ImageManager.sendMessageWithImage(
                        bot,
                        chat,
                        "Напиши часть текста или редкость.",
                        profile, replyMarkup = KeyboardFactory.predictionsMenu())
                return true
            }
            "Получить ещё" -> {
                givePrediction(bot, chat, uid, predictions, users)
                return true
            }
            "Добавить в избранные" -> {
                // TODO: Добавить логику добавления в избранные
                bot.screenText(
                        chat,
                        "Функция избранных предсказаний в разработке.",
                        replyMarkup = KeyboardFactory.predictionProcessMenu()
                )
                return true
            }
            "⬅️ Обратно" -> {
                // Возврат в меню предсказаний или главное меню
                if (session.action == PendingAction.SEARCH_PREDICTIONS) {
                    showPredictionMenu(bot, chat, uid, users)
                    session.action = PendingAction.NONE
                } else {
                    // Возврат в главное меню
                    SessionStore.clear(uid)
                    bot.screenText(chat, "Главное меню.", replyMarkup = KeyboardFactory.mainMenu())
                }
                return true
            }
            else -> {
                if (session.action != PendingAction.SEARCH_PREDICTIONS) return false
                val matches = predictions.search(text)
                bot.screenText(chat, if (matches.isEmpty()) "Ничего не найдено. Попробуй другой запрос."
                    else matches.joinToString("\n\n") { "${it.rarity}: ${it.text}" },
                    replyMarkup = KeyboardFactory.predictionsMenu())
                return true
            }
        }
    }
}
