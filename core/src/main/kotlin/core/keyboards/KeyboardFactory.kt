package core.keyboards

import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
import data.models.UserProfile
import data.repositories.FavoriteItem

// Вспомогательная функция для создания клавиатур
private fun kb(rows: List<List<String>>): InlineKeyboardMarkup {
    return core.ui.UiKeyboard.rows(rows)
}

object KeyboardFactory {
    // Системные клавиатуры (основные меню)
    fun mainMenu(isAdmin: Boolean = false): InlineKeyboardMarkup = kb(
        listOf(
            listOf("👤 Профиль", "🗂 Подборки"),
            listOf("😂 Мемы", "🔮 Предсказания"),
            listOf("📝 Тесты", "📅 События"),
            listOf("🎮 Мини-игры", "🖼 Пиксель-арт"),
            listOf("💬 Обратная связь", "⚙️ Настройки")
        )
    )

    // Перенаправления в модульные клавиатуры
    fun profileMenu(
            userProfile: UserProfile? = null,
            accountSwitchLabel: String? = null
    ): InlineKeyboardMarkup {
        return KeyboardProfile.profileMenu(userProfile, accountSwitchLabel)
    }

    fun settingsMenu(userProfile: UserProfile? = null): InlineKeyboardMarkup {
        return KeyboardProfile.settingsMenu(userProfile)
    }

    fun memesMenu(): InlineKeyboardMarkup {
        return KeyboardMemes.memesMenu()
    }

    fun memesViewerMenu(): InlineKeyboardMarkup {
        return KeyboardMemes.memesViewerMenu()
    }

    fun memesAddMenu(): InlineKeyboardMarkup {
        return KeyboardMemes.memesAddMenu()
    }

    fun memesFavoritesMenu(): InlineKeyboardMarkup {
        return KeyboardMemes.memesFavoritesMenu()
    }

    fun memeActionsInline(memeId: Long): InlineKeyboardMarkup {
        return KeyboardMemes.memeActionsInline(memeId)
    }

    fun predictionsMenu(): InlineKeyboardMarkup {
        return KeyboardPredictions.predictionsMenu()
    }

    fun predictionProcessMenu(): InlineKeyboardMarkup {
        return KeyboardPredictions.predictionProcessMenu()
    }

    fun predictionRarity(): InlineKeyboardMarkup {
        return KeyboardPredictions.predictionRarity()
    }

    fun testsMenu(): InlineKeyboardMarkup {
        return KeyboardTests.testsMenu()
    }

    fun testKinds(): InlineKeyboardMarkup {
        return KeyboardTests.testKinds()
    }

    fun testsContinue(): InlineKeyboardMarkup {
        return KeyboardTests.testsContinue()
    }

    fun eventsMenu(): InlineKeyboardMarkup {
        return KeyboardEvents.eventsMenu()
    }

    fun gamesMenu(): InlineKeyboardMarkup {
        return KeyboardGames.gamesMenu()
    }

    fun collectionsMenu(): InlineKeyboardMarkup {
        return KeyboardCollections.collectionsMenu()
    }

    fun searchTypeMenu(): InlineKeyboardMarkup {
        return KeyboardCollections.searchTypeMenu()
    }

    fun searchResultsMenu(hasMore: Boolean = false): InlineKeyboardMarkup {
        return KeyboardCollections.searchResultsMenu(hasMore)
    }

    fun feedbackMenu(): InlineKeyboardMarkup {
        return KeyboardFeedBack.feedbackMenu()
    }

    fun pixelMenu(): InlineKeyboardMarkup {
        return KeyboardPixelArt.pixelMenu()
    }

    // Системные inline клавиатуры (общие для всех модулей)
    fun searchResultsInlineKeyboard(itemsCount: Int): InlineKeyboardMarkup {
        return KeyboardCollections.searchResultsInlineKeyboard(itemsCount)
    }

    fun searchResultsMenuWithInline(itemsCount: Int, hasMore: Boolean = false): InlineKeyboardMarkup {
        return KeyboardCollections.searchResultsMenuWithInline(itemsCount, hasMore)
    }

    fun favoritesInlineKeyboard(favorites: List<FavoriteItem>): InlineKeyboardMarkup {
        return KeyboardCollections.favoritesInlineKeyboard(favorites)
    }

    fun singleFavoriteDeleteKeyboard(favorite: FavoriteItem, currentIndex: Int, totalCount: Int): InlineKeyboardMarkup {
        return KeyboardCollections.singleFavoriteDeleteKeyboard(favorite, currentIndex, totalCount)
    }

    fun favoritesNavKeyboard(currentIndex: Int, totalCount: Int): InlineKeyboardMarkup {
        return KeyboardCollections.favoritesNavKeyboard(currentIndex, totalCount)
    }

    fun singleFavoriteNav(): InlineKeyboardMarkup {
        return KeyboardCollections.singleFavoriteNav()
    }
}
