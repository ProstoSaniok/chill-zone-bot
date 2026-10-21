package core.keyboards

import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup

object KeyboardEvents {
    fun eventsMenu(): InlineKeyboardMarkup = kb(
        listOf(
            listOf("Ближайшие события", "Создать событие"),
            listOf("Мои события"),
            listOf("⬅️ Обратно")
        )
    )

    private fun kb(rows: List<List<String>>): InlineKeyboardMarkup {
        return core.ui.UiKeyboard.rows(rows)
    }
}