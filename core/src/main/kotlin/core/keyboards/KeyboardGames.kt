package core.keyboards

import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup

object KeyboardGames {
    fun gamesMenu(): InlineKeyboardMarkup = kb(
        listOf(
            listOf("⚽ Гол", "🏀 В кольцо"),
            listOf("🎡 Колесо фортуны", "✂️ Камень-ножницы-бумага"),
            listOf("🎰 Слоты", "❌⭕️ Крестики-нолики"),
            listOf("📊 Моя статистика", "🏆 Топ игроков"),
            listOf("⬅️ Обратно")
        )
    )

    fun ticTacToe(): InlineKeyboardMarkup = kb(
        listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("👥 2 игрока", "🤖 против бота"),
            listOf("🔁 Новая игра", "🎮 Мини-игры")
        )
    )

    private fun kb(rows: List<List<String>>): InlineKeyboardMarkup {
        return core.ui.UiKeyboard.rows(rows)
    }
}
