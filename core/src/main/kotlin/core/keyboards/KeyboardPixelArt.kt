package core.keyboards

import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup

object KeyboardPixelArt {
    fun pixelMenu(): InlineKeyboardMarkup = kb(
        listOf(
            listOf("Публичный холст"),
            listOf("⬅️ Обратно")
        )
    )

    private fun kb(rows: List<List<String>>): InlineKeyboardMarkup {
        return core.ui.UiKeyboard.rows(rows)
    }
}