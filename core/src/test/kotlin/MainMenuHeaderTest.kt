package core

import core.ui.MainMenuHeader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MainMenuHeaderTest {
    private fun response(ids: List<String>, type: String = "custom_emoji") = """
        {"ok":true,"result":{"sticker_type":"$type","stickers":[
        ${ids.joinToString(",") { """{"custom_emoji_id":"$it","emoji":"🔤"}""" }}
        ]}}
    """.trimIndent()

    @Test fun takesExactlyFirstFiveInPackOrderWithoutSpacesOrLineBreaks() {
        val ids = listOf("900", "100", "700", "200", "500", "300")
        assertEquals(ids.take(5).joinToString("") {
            "<tg-emoji emoji-id=\"$it\">🔤</tg-emoji>"
        }, MainMenuHeader.parse(response(ids)))
    }

    @Test fun rejectsIncompleteOrNonEmojiPacksAndMissingIds() {
        assertFailsWith<IllegalArgumentException> { MainMenuHeader.parse(response(listOf("1"))) }
        assertFailsWith<IllegalArgumentException> {
            MainMenuHeader.parse(response(List(5) { "1" }, "regular"))
        }
        assertFailsWith<IllegalArgumentException> {
            MainMenuHeader.parse(response(listOf("1", "2", "", "4", "5")))
        }
    }
}
