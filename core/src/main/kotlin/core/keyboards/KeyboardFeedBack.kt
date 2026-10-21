package core.keyboards

import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup

object KeyboardFeedBack {
    fun feedbackMenu(): InlineKeyboardMarkup = kb(
        listOf(
            listOf("Оставить отзыв", "Посмотреть поддержку"),
            listOf("⬅️ Обратно")
        )
    )

    private fun kb(rows: List<List<String>>): InlineKeyboardMarkup {
        return core.ui.UiKeyboard.rows(rows)
    }
}