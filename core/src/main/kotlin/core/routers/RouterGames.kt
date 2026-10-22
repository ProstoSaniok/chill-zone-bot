package core.routers

import core.ui.*
import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.dice.DiceEmoji
import core.handlers.handlerTicTacToe
import core.ImageManager
import core.PendingAction
import core.Session
import core.SessionStore
import core.keyboards.KeyboardFactory
import core.keyboards.KeyboardGames
import data.models.*
import data.repositories.GameRepository
import data.repositories.UserRepository

object RouterGames {
    fun playTelegramDice(
        bot: Bot, chat: ChatId, uid: Long, game: String,
        games: GameRepository, users: UserRepository
    ) {
        val emoji = when (game) {
            "⚽ Гол" -> DiceEmoji.Football
            "🏀 В кольцо" -> DiceEmoji.Basketball
            else -> DiceEmoji.SlotMachine
        }
        val value = bot.sendDice(chat, emoji).get().dice?.value
            ?: error("Telegram не вернул результат игры")
        ScreenRenderer.persistent()
        val won = when (game) {
            "⚽ Гол" -> value == 3
            "🏀 В кольцо" -> value == 4 || value == 5
            else -> value in setOf(1, 22, 43, 64)
        }
        val result = if (won) {
            val points = listOf(5, 10, 15, 20).random()
            games.win(uid, points)
            users.addRating(uid, maxOf(1, points / 5))
            "🎉 Победа! +$points очков рейтинга."
        } else {
            games.lose(uid)
            "😢 Проигрыш, без штрафа."
        }
        bot.screenText(chat, "$game: $result", replyMarkup = KeyboardFactory.gamesMenu())
    }

    fun handleTicTacToe(bot: Bot, chat: ChatId, uid: Long, text: String, session: Session): Boolean {
        if (text != "❌⭕️ Крестики-нолики" && text != "/tictactoe" &&
            text != "🔁 Новая игра" && text != "👥 2 игрока" &&
            text != "🤖 против бота" && text !in (1..9).map(Int::toString)) return false
        session.action = PendingAction.TIC_TAC_TOE
        bot.screenText(chat, handlerTicTacToe(uid, text), replyMarkup = KeyboardGames.ticTacToe())
        return true
    }

    fun playWheel(bot: Bot, chat: ChatId, uid: Long, games: GameRepository, users: UserRepository) {
        ScreenRenderer.persistent()
        val prize = listOf(0, 5, 10, 15, 20, 30).random()
        if (prize > 0) {
            games.win(uid, prize)
            users.addRating(uid, maxOf(1, prize / 10))
            bot.screenText(
                    chat,
                    "Колесо фортуны: +$prize рейтинга!",
                    replyMarkup = KeyboardFactory.gamesMenu()
            )
        } else {
            games.lose(uid)
            bot.screenText(
                    chat,
                    "Колесо фортуны: пусто.",
                    replyMarkup = KeyboardFactory.gamesMenu()
            )
        }
    }

    fun showGameStats(bot: Bot, chat: ChatId, uid: Long, games: GameRepository) {
        val s = games.stats(uid)
        bot.screenText(
                chat,
                "Статистика:\nРейтинг: ${s.rating}\nПобеды: ${s.wins}\nПоражения: ${s.losses}\nStreak: ${s.streak}\nЛучший streak: ${s.bestStreak}",
                replyMarkup = KeyboardFactory.gamesMenu()
        )
    }

    fun showTopGames(bot: Bot, chat: ChatId, games: GameRepository) {
        val top = games.top()
        if (top.isEmpty()) {
            bot.screenText(
                    chat,
                    "Пока нет игроков в таблице.",
                    replyMarkup = KeyboardFactory.gamesMenu()
            )
            return
        }
        bot.screenText(
                chat,
                top.withIndex().joinToString("\n") { (idx, s) ->
                    "${idx + 1}. #${s.userId} — ${s.rating}"
                },
                replyMarkup = KeyboardFactory.gamesMenu()
        )
    }

    fun handleGameAction(
            bot: Bot,
            chat: ChatId,
            uid: Long,
            text: String,
            games: GameRepository,
            users: UserRepository,
            session: Session
    ): Boolean {
        when (text) {
            "🎮 Мини-игры" -> {
                session.action = PendingAction.NONE
                showGameStats(bot, chat, uid, games)
                return true
            }
            "⚽ Гол" -> {
                session.action = PendingAction.NONE
                playTelegramDice(bot, chat, uid, text, games, users)
                return true
            }
            "🏀 В кольцо" -> {
                session.action = PendingAction.NONE
                playTelegramDice(bot, chat, uid, text, games, users)
                return true
            }
            "🎰 Слоты" -> {
                session.action = PendingAction.NONE
                playTelegramDice(bot, chat, uid, text, games, users)
                return true
            }
            "🎡 Колесо фортуны" -> {
                session.action = PendingAction.NONE
                playWheel(bot, chat, uid, games, users)
                return true
            }
            "✂️ Камень-ножницы-бумага" -> {
                session.action = PendingAction.RPS_CHOICE
                val profile = users.profile(uid)
                ImageManager.sendMessageWithImage(
                        bot,
                        chat,
                        "Выбери ход: камень, ножницы или бумага.",
                        profile, replyMarkup = UiKeyboard.rows(listOf(listOf("камень", "ножницы", "бумага"))))
                return true
            }
            "📊 Моя статистика" -> {
                session.action = PendingAction.NONE
                showGameStats(bot, chat, uid, games)
                return true
            }
            "🏆 Топ игроков" -> {
                session.action = PendingAction.NONE
                showTopGames(bot, chat, games)
                return true
            }
            "⬅️ Обратно" -> {
                // Возврат в главное меню или в меню игр
                if (session.action == PendingAction.RPS_CHOICE) {
                    session.action = PendingAction.NONE
                    showGameStats(bot, chat, uid, games)
                } else {
                    // Возврат в главное меню
                    SessionStore.clear(uid)
                    bot.screenText(chat, "Главное меню.", replyMarkup = KeyboardFactory.mainMenu())
                }
                return true
            }
            else -> return false
        }
    }

    fun handleRpsChoice(
            bot: Bot,
            chat: ChatId,
            uid: Long,
            text: String,
            games: GameRepository,
            users: UserRepository,
            session: Session
    ): Boolean {
        val userChoice = text.lowercase()
        if (userChoice !in listOf("камень", "ножницы", "бумага")) {
            bot.screenText(chat, "Выбери камень, ножницы или бумагу.",
                replyMarkup = UiKeyboard.rows(listOf(listOf("камень", "ножницы", "бумага"))))
            return true
        }
        ScreenRenderer.persistent()
        val botChoice = listOf("камень", "ножницы", "бумага").random()
        val result =
                when {
                    userChoice == botChoice -> "Ничья"
                    userChoice == "камень" && botChoice == "ножницы" -> "Победа"
                    userChoice == "ножницы" && botChoice == "бумага" -> "Победа"
                    userChoice == "бумага" && botChoice == "камень" -> "Победа"
                    else -> "Поражение"
                }
        if (result == "Победа") {
            games.win(uid, 5)
            users.addRating(uid, 1)
        } else if (result == "Поражение") {
            games.lose(uid)
        }
        session.action = PendingAction.NONE
        bot.screenText(
                chat,
                "Я выбрал: $botChoice. Результат: $result.",
                replyMarkup = KeyboardFactory.gamesMenu()
        )
        return true
    }
}
