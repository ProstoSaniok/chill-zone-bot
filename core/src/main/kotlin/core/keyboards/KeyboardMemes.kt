package core.keyboards

import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton

object KeyboardMemes {
    fun memesMenu(): InlineKeyboardMarkup = kb(
        listOf(
            listOf("🎲 Смотреть мемы"),
            listOf("⭐ Избранные мемы", "➕ Добавить мем"),
            listOf("🏠 Главное меню")
        )
    )

    fun memesViewerMenu(): InlineKeyboardMarkup = kb(
        listOf(
            listOf("🎲 Следующий мем"),
            listOf("⭐ Избранные мемы", "➕ Добавить мем"),
            listOf("⬅️ К мемам")
        )
    )

    fun memesAddMenu(): InlineKeyboardMarkup = kb(
        listOf(
            listOf("❌ Отменить добавление")
        )
    )

    fun memesFavoritesMenu(): InlineKeyboardMarkup = kb(
        listOf(
            listOf("🎲 Смотреть мемы", "➕ Добавить мем"),
            listOf("⬅️ К мемам")
        )
    )

    fun memeActionsInline(
        memeId: Long,
        likes: Int = 0,
        dislikes: Int = 0,
        favorite: Boolean = false,
        vote: Int = 0,
        favoriteIndex: Int? = null,
        favoriteCount: Int = 0
    ): InlineKeyboardMarkup {
        val suffix = if (favoriteIndex == null) "" else "_saved"
        val rows = mutableListOf<List<InlineKeyboardButton>>(
            listOf(
                InlineKeyboardButton.CallbackData(text = "👍 $likes${if (vote == 1) " ✓" else ""}", callbackData = "meme_vote_${memeId}_1$suffix"),
                InlineKeyboardButton.CallbackData(text = "👎 $dislikes${if (vote == -1) " ✓" else ""}", callbackData = "meme_vote_${memeId}_-1$suffix")
            ),
            listOf(
                InlineKeyboardButton.CallbackData(
                    text = if (favorite) "★ Убрать из избранного" else "☆ В избранное",
                    callbackData = "meme_fav_$memeId$suffix"
                )
            )
        )
        if (favoriteIndex == null) {
            rows.add(listOf(InlineKeyboardButton.CallbackData(text = "🎲 Следующий мем", callbackData = "meme_next")))
        } else {
            val navigation = mutableListOf<InlineKeyboardButton>()
            if (favoriteIndex > 0) navigation.add(InlineKeyboardButton.CallbackData(text = "← Предыдущий", callbackData = "meme_page_${memeId}_-1"))
            navigation.add(InlineKeyboardButton.CallbackData(text = "${favoriteIndex + 1} / $favoriteCount", callbackData = "meme_position"))
            if (favoriteIndex + 1 < favoriteCount) navigation.add(InlineKeyboardButton.CallbackData(text = "Следующий →", callbackData = "meme_page_${memeId}_1"))
            rows.add(navigation)
        }
        return InlineKeyboardMarkup.create(
            rows
        )
    }

    private fun kb(rows: List<List<String>>): InlineKeyboardMarkup {
        return core.ui.UiKeyboard.rows(rows)
    }
}
