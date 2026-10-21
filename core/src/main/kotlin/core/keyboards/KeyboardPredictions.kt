package core.keyboards

import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup

object KeyboardPredictions {
    fun predictionsMenu(): InlineKeyboardMarkup = kb(
        listOf(
            listOf("Получить предсказание", "Мои предсказания"),
            listOf("Поиск предсказаний"),
            listOf("⬅️ Обратно")
        )
    )

    fun predictionProcessMenu(): InlineKeyboardMarkup = kb(
        listOf(
            listOf("Получить ещё", "Добавить в избранные"),
            listOf("⬅️ Обратно")
        )
    )

    fun predictionRarity(): InlineKeyboardMarkup = kb(
        listOf(
            listOf("Обычное", "Редкое"),
            listOf("Эпическое", "Легендарное"),
            listOf("⬅️ Обратно")
        )
    )

    private fun kb(rows: List<List<String>>): InlineKeyboardMarkup {
        return core.ui.UiKeyboard.rows(rows)
    }
}