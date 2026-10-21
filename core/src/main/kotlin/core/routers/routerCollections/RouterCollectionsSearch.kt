package core.routers.routerCollections

import data.*
import data.models.*
import data.repositories.FavoriteRepository
import data.repositories.RecommendationRepository
import core.ui.*
import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.ParseMode
import core.keyboards.KeyboardFactory
import core.keyboards.KeyboardCollections
import core.ImageManager
import core.SessionStore
import core.PendingAction
import core.Session

object RouterCollectionsSearch {
    fun showSingleSearchResult(bot: Bot, chat: ChatId, uid: Long, items: List<RecommendationItem>, index: Int, hasMore: Boolean = false) {
        if (index < 0 || index >= items.size) return
        
        val item = items[index]
        val isSaved = FavoriteRepository.isFavorite(uid, item.id, item.type)
        
        val message = buildString {
            append("🔍 Результат поиска (${index + 1} из ${items.size})\n\n")
            append("${item.title} (${item.year})\n")
            append("Тип: ${getTypeDisplayName(item.type)}\n")
            
            if (item.description.isNotEmpty()) {
                val cleanDescription = item.description
                    .replace(Regex("<[^>]*>"), "") // Удаляем HTML теги
                    .replace(Regex("&[^;]*;"), "") // Удаляем HTML сущности
                    .replace(Regex("\\s+"), " ") // Заменяем множественные пробелы
                    .trim()
                    .take(300) // Ограничиваем до 300 символов
                
                append("Описание: ${cleanDescription}\n")
            }
            
            if (item.url != null) {
                append("Ссылка: ${item.url}\n")
            }
        }
        
        val inlineKeyboard = KeyboardCollections.singleSearchResultKeyboard(item, index, items.size, isSaved)
        val replyKeyboard = KeyboardCollections.searchResultNavKeyboard(hasMore)
        
        bot.screenText(chat, message, parseMode = ParseMode.HTML, replyMarkup = replyKeyboard)
        bot.screenText(chat, "", replyMarkup = inlineKeyboard)
    }

    fun getTypeDisplayName(type: RecommendationType): String {
        return when (type) {
            RecommendationType.FILM -> "Фильм"
            RecommendationType.SERIES -> "Сериал"
            RecommendationType.BOOK -> "Книга"
            RecommendationType.GAME -> "Игра"
        }
    }
}
