package core.ui

import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton

/** Internal actions are replaced by short, screen-bound tokens before going to Telegram. */
object UiKeyboard {
    fun rows(rows: List<List<String>>): InlineKeyboardMarkup = InlineKeyboardMarkup.create(
        rows.map { row ->
            row.filter(String::isNotBlank).map { text ->
                InlineKeyboardButton.CallbackData(text, "text:$text")
            }
        }.filter { it.isNotEmpty() }
    )

    fun combine(vararg keyboards: InlineKeyboardMarkup): InlineKeyboardMarkup =
        InlineKeyboardMarkup.create(keyboards.flatMap { it.inlineKeyboard }.distinct())
}
