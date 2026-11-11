package core.keyboards

import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton

object KeyboardPredictions {
    fun predictionsMenu(): InlineKeyboardMarkup = kb(
        listOf(
            listOf("Получить", "Моя коллекция"),
            listOf("Поиск предсказаний"),
            listOf("⬅️ Обратно")
        )
    )

    fun collectionPage(page: Int, hasNext: Boolean): InlineKeyboardMarkup {
        val rows = mutableListOf<List<InlineKeyboardButton>>()
        val paging = mutableListOf<InlineKeyboardButton>()
        if (page > 0) paging += InlineKeyboardButton.CallbackData("⬅️ Предыдущая", "prediction_collection:${page - 1}")
        if (hasNext) paging += InlineKeyboardButton.CallbackData("Следующая ➡️", "prediction_collection:${page + 1}")
        if (paging.isNotEmpty()) rows += paging
        rows += listOf(
            InlineKeyboardButton.CallbackData("⬅️ Обратно", "text:⬅️ Обратно"),
            InlineKeyboardButton.CallbackData("🏠 Главное меню", "text:🏠 Главное меню")
        )
        return InlineKeyboardMarkup.create(rows)
    }

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
