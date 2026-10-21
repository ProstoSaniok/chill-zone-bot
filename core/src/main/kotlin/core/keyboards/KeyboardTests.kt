package core.keyboards

import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup

object KeyboardTests {
    fun testsMenu(): InlineKeyboardMarkup = kb(
        listOf(
            listOf("Случайный тест", "Создать тест"),
            listOf("Пройти тест", "Мои тесты"),
            listOf("Поиск тестов"),
            listOf("⬅️ Обратно")
        )
    )

    fun testsSearchNavMenu(): InlineKeyboardMarkup = kb(
        listOf(
            listOf("⬅️ Обратно")
        )
    )

    fun testsSearchResultsMenu(): InlineKeyboardMarkup = kb(
        listOf(
            listOf("⬅️ Обратно")
        )
    )

    fun testKinds(): InlineKeyboardMarkup = kb(
        listOf(
            listOf("SINGLE", "MULTI"),
            listOf("NUMBER", "TEXT"),
            listOf("MATCH", "COLOR"),
            listOf("⬅️ Обратно")
        )
    )

    fun testsContinue(): InlineKeyboardMarkup = kb(
        listOf(
            listOf("Да", "Нет"),
            listOf("⬅️ Обратно")
        )
    )

    private fun kb(rows: List<List<String>>): InlineKeyboardMarkup {
        return core.ui.UiKeyboard.rows(rows)
    }
}
