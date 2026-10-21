package core.routers.routerCollections

import data.models.*
import data.repositories.RecommendationRepository
import data.repositories.FavoriteRepository
import data.repositories.UserRepository
import core.ui.*
import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.ParseMode
import core.keyboards.KeyboardFactory
import core.keyboards.KeyboardCollections
import core.ImageManager
import core.SessionStore
import core.FSMContext
import core.PendingAction
import core.Session
import kotlinx.coroutines.runBlocking
import com.github.kotlintelegrambot.entities.CallbackQuery
import core.routers.routerCollections.RouterCollectionsFavorites
import core.routers.routerCollections.RouterCollectionsSearch

object RouterCollections {
    fun handleCollectionAction(bot: Bot, chat: ChatId, uid: Long, text: String, users: UserRepository, session: Session): Boolean {
        when (text) {
            "🗂 Подборки" -> {
                session.action = PendingAction.NONE
                session.context = FSMContext.COLLECTIONS
                session.clearDraft()
                session.data["last_menu"] = "collections"
                val profile = users.profile(uid)
                val collectionsMessage = """🔍 <b>Меню подборок</b>

Здесь вы можете:
• 🔍 <b>Поиск</b> - находить фильмы, сериалы, книги и игры по запросу
• ⭐️ <b>Избранное</b> - просматривать сохраненные элементы

Выберите действие:"""
                ImageManager.sendMessageWithImage(bot, chat, collectionsMessage, profile, parseMode = ParseMode.HTML, replyMarkup = KeyboardFactory.collectionsMenu())
                return true
            }
            "🔍 Поиск" -> {
                session.action = PendingAction.SEARCH_TYPE_SELECT
                session.context = FSMContext.COLLECTIONS
                session.clearDraft()
                session.data["last_menu"] = "collections"
                val profile = users.profile(uid)
                val searchTypeMessage = """🔍 <b>Выберите тип поиска</b>

Выберите категорию для поиска:"""
                bot.screenText(chat, searchTypeMessage, parseMode = ParseMode.HTML, replyMarkup = KeyboardFactory.searchTypeMenu())
                return true
            }
            "⭐ Избранное" -> {
                session.action = PendingAction.VIEW_FAVORITES
                session.context = FSMContext.COLLECTIONS
                RouterCollectionsFavorites.showCollectionFavorites(bot, chat, uid, users)
                return true
            }
            "🎬 Фильм", "📺 Сериал", "📚 Книга", "🎮 Игра" -> {
                // Определяем тип контента из текста кнопки
                val type = when (text) {
                    "🎬 Фильм" -> RecommendationType.FILM
                    "📺 Сериал" -> RecommendationType.SERIES
                    "📚 Книга" -> RecommendationType.BOOK
                    "🎮 Игра" -> RecommendationType.GAME
                    else -> RecommendationType.FILM
                }
                
                // Устанавливаем тип и переходим к вводу запроса
                session.data["collection_type"] = type.name
                session.action = PendingAction.COLLECTION_QUERY
                
                val profile = users.profile(uid)
                val queryMessage = """🔍 <b>Поиск ${RouterCollectionsSearch.getTypeDisplayName(type).lowercase()}</b>

Введите название ${RouterCollectionsSearch.getTypeDisplayName(type).lowercase()} для поиска:"""
                ImageManager.sendMessageWithImage(bot, chat, queryMessage, profile, parseMode = ParseMode.HTML, replyMarkup = KeyboardFactory.singleFavoriteNav())
                return true
            }
            "➡️ Ещё" -> {
                // Загружаем следующие результаты и добавляем к текущим
                val type = session.data["collection_type"]?.let { RecommendationType.valueOf(it) } ?: RecommendationType.FILM
                val query = session.data["collection_query"]
                val offset = (session.data["collection_offset"]?.toIntOrNull() ?: 0) + 3
                
                // Устанавливаем состояние для навигации по результатам
                session.action = PendingAction.COLLECTION_RESULTS
                
                // Вызываем suspend функцию через coroutine scope
                runBlocking {
                    val result = RecommendationRepository.searchMultiple(type, query, 3, offset = offset)
                    
                    if (result.items.isEmpty()) {
                        bot.screenText(chat, "Других вариантов не нашёл.", replyMarkup = KeyboardCollections.searchResultNavKeyboard(false))
                    } else {
                        // Получаем текущие результаты из сессии
                        val currentResultsData = session.data["search_results"] ?: ""
                        val currentResults = if (currentResultsData.isNotEmpty()) {
                            currentResultsData.split("|").mapNotNull { itemData ->
                                val parts = itemData.split(":")
                                if (parts.size == 2) {
                                    // В реальном приложении здесь нужно будет восстанавливать объекты
                                    // Пока просто загружаем заново все результаты
                                    null
                                } else null
                            }
                        } else emptyList()
                        
                        // Загружаем все результаты заново для простоты
                        val allResults = RecommendationRepository.searchMultiple(type, query, offset + 3, offset = 0).items
                        
                        val currentCount = allResults.size
                        val totalCount = result.totalCount
                        val resultHeader = if (totalCount > currentCount) {
                            "Ещё результаты: ${currentCount} из ${totalCount}"
                        } else {
                            "Все результаты: ${currentCount} из ${totalCount}"
                        }
                        
                        bot.screenText(chat, "$resultHeader\n\nИспользуйте стрелочки для навигации:", parseMode = ParseMode.HTML)
                        
                        // Обновляем данные в сессии
                        session.data["search_results"] = allResults.joinToString("|") { "${it.id}:${it.type.name}" }
                        session.data["search_total_count"] = allResults.size.toString()
                        session.data["collection_offset"] = offset.toString()
                        
                        // Показываем первый из новых результатов
                        RouterCollectionsSearch.showSingleSearchResult(bot, chat, uid, allResults, offset, true) // Всегда показываем "➡️ Ещё" т.к. это подгрузка
                    }
                }
                return true
            }
            "🔍 Новый запрос" -> {
                // Начинаем новый поиск в той же категории
                val type = session.data["collection_type"]?.let { RecommendationType.valueOf(it) }
                
                if (type != null) {
                    // Сбрасываем данные предыдущего поиска, но сохраняем тип
                    session.data["collection_query"] = ""
                    session.data["collection_offset"] = "0"
                    session.action = PendingAction.COLLECTION_QUERY
                    
                    val profile = users.profile(uid)
                    val queryMessage = """🔍 <b>Поиск ${RouterCollectionsSearch.getTypeDisplayName(type).lowercase()}</b>

Введите название ${RouterCollectionsSearch.getTypeDisplayName(type).lowercase()} для поиска:"""
                    ImageManager.sendMessageWithImage(bot, chat, queryMessage, profile, parseMode = ParseMode.HTML, replyMarkup = KeyboardFactory.singleFavoriteNav())
                    return true
                } else {
                    // Если тип не определен, возвращаем к выбору типа
                    session.action = PendingAction.SEARCH_TYPE_SELECT
                    val profile = users.profile(uid)
                    val searchTypeMessage = """🔍 <b>Выберите тип поиска</b>

Выберите категорию для поиска:"""
                    bot.screenText(chat, searchTypeMessage, parseMode = ParseMode.HTML, replyMarkup = KeyboardFactory.searchTypeMenu())
                    return true
                }
            }
            "💾 Сохранить" -> {
                when (session.context) {
                    FSMContext.MEMES -> {
                        // Для мемов - существующая логика
                        return false
                    }
                    FSMContext.COLLECTIONS -> {
                        // Для подборок - новая логика сохранения
                        RouterCollectionsFavorites.saveToFavorites(bot, chat, uid, session)
                        return true
                    }
                    FSMContext.PROFILE_VIEW -> {
                        // Профиль не сохраняется здесь
                        return false
                    }
                }
            }
            "Мои избранные" -> {
                when (session.context) {
                    FSMContext.MEMES -> {
                        // Показать избранные мемы
                        return false
                    }
                    FSMContext.COLLECTIONS -> {
                        // Показать избранные подборки
                        RouterCollectionsFavorites.showCollectionFavorites(bot, chat, uid, users)
                        return true
                    }
                    FSMContext.PROFILE_VIEW -> {
                        // В профиле нет избранного
                        return false
                    }
                }
            }
            "⬅️ Обратно" -> {
                when (session.action) {
                    PendingAction.VIEW_FAVORITES -> {
                        // Возврат из избранного в меню подборок
                        session.action = PendingAction.NONE
                        session.context = FSMContext.COLLECTIONS
                        session.data["last_menu"] = "collections"
                        val profile = users.profile(uid)
                        val collectionsMessage = """🔍 <b>Меню подборок</b>

Здесь вы можете:
• 🔍 <b>Поиск</b> - находить фильмы, сериалы, книги и игры по запросу
• ⭐️ <b>Избранное</b> - просматривать сохраненные элементы

Выберите действие:"""
                        ImageManager.sendMessageWithImage(bot, chat, collectionsMessage, profile, parseMode = ParseMode.HTML, replyMarkup = KeyboardFactory.collectionsMenu())
                        return true
                    }
                    PendingAction.COLLECTION_QUERY -> {
                        // Возврат из ввода запроса в главное меню
                        session.action = PendingAction.NONE
                        session.context = FSMContext.MEMES
                        session.clearDraft()
                        val profile = users.profile(uid)
                        val collectionsMessage = """🔍 <b>Меню подборок</b>

Здесь вы можете:
• 🔍 <b>Поиск</b> - находить фильмы, сериалы, книги и игры по запросу
• ⭐️ <b>Избранное</b> - просматривать сохраненные элементы

Выберите действие:"""
                        ImageManager.sendMessageWithImage(bot, chat, collectionsMessage, profile, parseMode = ParseMode.HTML, replyMarkup = KeyboardFactory.collectionsMenu())
                        return true
                    }
                    PendingAction.COLLECTION_RESULTS -> {
                        // Возврат из результатов поиска в главное меню
                        session.action = PendingAction.NONE
                        session.context = FSMContext.MEMES
                        session.clearDraft()
                        val profile = users.profile(uid)
                        val collectionsMessage = """🔍 <b>Меню подборок</b>

Здесь вы можете:
• 🔍 <b>Поиск</b> - находить фильмы, сериалы, книги и игры по запросу
• ⭐️ <b>Избранное</b> - просматривать сохраненные элементы

Выберите действие:"""
                        ImageManager.sendMessageWithImage(bot, chat, collectionsMessage, profile, parseMode = ParseMode.HTML, replyMarkup = KeyboardFactory.collectionsMenu())
                        return true
                    }
                    PendingAction.SEARCH_TYPE_SELECT -> {
                        // Возврат из меню выбора типа поиска в главное меню
                        session.action = PendingAction.NONE
                        session.context = FSMContext.COLLECTIONS
                        session.clearDraft()
                        val profile = users.profile(uid)
                        val collectionsMessage = """🔍 <b>Меню подборок</b>

Здесь вы можете:
• 🔍 <b>Поиск</b> - находить фильмы, сериалы, книги и игры по запросу
• ⭐️ <b>Избранное</b> - просматривать сохраненные элементы

Выберите действие:"""
                        ImageManager.sendMessageWithImage(bot, chat, collectionsMessage, profile, parseMode = ParseMode.HTML, replyMarkup = KeyboardFactory.collectionsMenu())
                        return true
                    }
                    else -> {
                        // Для всех остальных случаев - главное меню
                        session.action = PendingAction.NONE
                        session.context = FSMContext.MEMES
                        session.clearDraft()
                        val profile = users.profile(uid)
                        val collectionsMessage = "Главное меню."
                        ImageManager.sendMessageWithImage(bot, chat, collectionsMessage, profile, parseMode = ParseMode.HTML, replyMarkup = KeyboardFactory.mainMenu())
                        return true
                    }
                }
            }
            else -> {
                // Обработка текстовых сообщений (поисковые запросы)
                if (session.action == PendingAction.COLLECTION_QUERY) {
                    // Убедимся, что контекст установлен
                    session.context = FSMContext.COLLECTIONS
                    
                    val type = session.data["collection_type"]?.let { RecommendationType.valueOf(it) }
                    val query = text.trim()
                    
                    if (type != null && query.isNotEmpty()) {
                        session.data["collection_query"] = query
                        session.data["collection_offset"] = "0"
                        session.action = PendingAction.COLLECTION_RESULTS
                        
                        runBlocking {
                            try {
                                val result = RecommendationRepository.searchMultiple(type, query, 3, offset = 0)
                                
                                if (result.items.isEmpty()) {
                                    session.action = PendingAction.SEARCH_TYPE_SELECT
                                    bot.screenText(chat, "Ничего не найдено по запросу \"$query\".", replyMarkup = KeyboardFactory.searchTypeMenu())
                                } else {
                                    // Сохраняем все результаты в сессию для навигации
                                    session.data["search_results"] = result.items.joinToString("|") { "${it.id}:${it.type.name}" }
                                    session.data["search_current_index"] = "0"
                                    session.data["search_total_count"] = result.items.size.toString()
                                    session.data["search_query"] = query
                                    session.data["search_type"] = type.name
                                    
                                    val currentCount = result.items.size
                                    val totalCount = result.totalCount
                                    val resultHeader = if (totalCount > currentCount) {
                                        "Найдено ${currentCount} из ${totalCount} результатов по запросу \"$query\":"
                                    } else {
                                        "Найдено ${currentCount} результатов по запросу \"$query\":"
                                    }
                                    
                                    bot.screenText(chat, "$resultHeader\n\nИспользуйте стрелочки для навигации:", parseMode = ParseMode.HTML)

                                    // Показываем первый результат
                                    RouterCollectionsSearch.showSingleSearchResult(bot, chat, uid, result.items, 0, result.hasMore)
                                }
                            } catch (e: Exception) {
                                println("Error searching: ${e.message}")
                                bot.screenText(chat, "Ошибка при поиске.", replyMarkup = KeyboardFactory.collectionsMenu())
                            }
                        }
                        return true
                    }
                }
                return false
            }
        }
    }

    fun handleCallback(bot: Bot, callback: CallbackQuery, users: UserRepository) {
        val chat = ChatId.fromId(callback.message!!.chat.id)
        val uid = SessionStore.get(callback.from.id).data["profile_impersonated_user_id"]?.toLongOrNull() ?: callback.from.id
        val session = SessionStore.get(uid)
        val data = callback.data ?: return

        when {
            data.startsWith("save_item_") -> {
                val itemNumber = data.substringAfter("save_item_").toIntOrNull() ?: return
                val type = session.data["collection_type"]?.let { RecommendationType.valueOf(it) }
                val query = session.data["collection_query"] ?: return
                val offset = session.data["collection_offset"]?.toIntOrNull() ?: 0
                
                if (type == null) {
                    bot.callbackNotice(callback.id, "Ошибка: не определен тип контента.")
                    return
                }
                
                runBlocking {
                    try {
                        val results = RecommendationRepository.searchMultiple(type, query, 3, offset)
                        val itemIndex = itemNumber - 1
                        if (itemIndex in results.items.indices) {
                            val item = results.items[itemIndex]
                            
                            // Проверяем, уже ли в избранном
                            val isAlreadyFavorite = FavoriteRepository.isFavorite(uid, item.id, item.type)
                            
                            if (isAlreadyFavorite) {
                                bot.callbackNotice(callback.id, "Этот ${RouterCollectionsSearch.getTypeDisplayName(item.type).lowercase()} уже есть в избранных")

                            } else {
                                FavoriteRepository.add(uid, item)
                                bot.callbackNotice(callback.id, "В избранные добавлен №$itemNumber")
                                RouterCollectionsSearch.showSingleSearchResult(bot, chat, uid, results.items, itemIndex, results.hasMore)
                            }
                        } else {
                            bot.callbackNotice(callback.id, "Ошибка: элемент не найден.")
                        }
                    } catch (e: Exception) {
                        println("Error saving item to favorites: ${e.message}")
                        bot.callbackNotice(callback.id, "Ошибка при сохранении.")
                    }
                }
            }
            
            data.startsWith("nav_search_") -> {
                val newIndex = data.substringAfter("nav_search_").toIntOrNull()
                if (newIndex == null) {
                    bot.callbackNotice(callback.id, "Ошибка: неверный индекс.")
                    return
                }
                
                // Восстанавливаем результаты из сессии
                val resultsString = session.data["search_results"]
                val type = session.data["search_type"]?.let { RecommendationType.valueOf(it) }
                val query = session.data["search_query"]
                
                if (resultsString != null && type != null && query != null) {
                    runBlocking {
                        try {
                            // Получаем все результаты заново для навигации (с большим лимитом)
                            val allResults = RecommendationRepository.searchMultiple(type, query, 20, offset = 0).items
                            if (newIndex in allResults.indices) {
                                session.data["search_current_index"] = newIndex.toString()
                                RouterCollectionsSearch.showSingleSearchResult(bot, chat, uid, allResults, newIndex, false)
                            }
                        } catch (e: Exception) {
                            println("Error navigating search results: ${e.message}")
                            bot.callbackNotice(callback.id, "Ошибка при навигации.")
                        }
                    }
                } else {
                    bot.callbackNotice(callback.id, "Ошибка: данные поиска не найдены.")
                }
                
                bot.callbackNotice(callback.id)
            }
            
            data.startsWith("save_single_") -> {
                val parts = data.substringAfter("save_single_").split("_")
                if (parts.size != 2) {
                    bot.callbackNotice(callback.id, "Ошибка: неверный формат данных.")
                    return
                }
                
                val itemId = parts[0].toIntOrNull()
                val itemTypeName = parts[1]
                
                if (itemId == null) {
                    bot.callbackNotice(callback.id, "Ошибка: неверный ID элемента.")
                    return
                }
                
                try {
                    val itemType = RecommendationType.valueOf(itemTypeName)
                    val type = session.data["search_type"]?.let { RecommendationType.valueOf(it) }
                    val query = session.data["search_query"]
                    
                    if (type != null && query != null) {
                        runBlocking {
                            try {
                                val result = RecommendationRepository.searchMultiple(type, query, 20, offset = 0)
                                val item = result.items.find { it.id == itemId && it.type == itemType }
                                
                                if (item != null) {
                                    val isAlreadyFavorite = FavoriteRepository.isFavorite(uid, item.id, item.type)
                                    
                                    if (isAlreadyFavorite) {
                                        bot.callbackNotice(callback.id, "Этот ${RouterCollectionsSearch.getTypeDisplayName(item.type).lowercase()} уже есть в избранных")

                                    } else {
                                        FavoriteRepository.add(uid, item)
                                        bot.callbackNotice(callback.id, "✅ Сохранено в избранное!")
                                        val index = result.items.indexOfFirst { it.id == item.id && it.type == item.type }
                                        RouterCollectionsSearch.showSingleSearchResult(bot, chat, uid, result.items, index, result.hasMore)
                                    }
                                } else {
                                    bot.callbackNotice(callback.id, "Ошибка: элемент не найден.")
                                }
                            } catch (e: Exception) {
                                println("Error saving single item to favorites: ${e.message}")
                                bot.callbackNotice(callback.id, "Ошибка при сохранении.")
                            }
                        }
                    }
                } catch (e: Exception) {
                    println("Error parsing item type: ${e.message}")
                    bot.callbackNotice(callback.id, "Ошибка: неверный тип элемента.")
                }
            }
            
            data.startsWith("remove_item_") -> {
                val parts = data.substringAfter("remove_item_").split("_")
                if (parts.size != 2) {
                    bot.callbackNotice(callback.id, "Ошибка: неверный формат данных.")
                    return
                }
                
                val itemId = parts[0].toIntOrNull()
                val itemTypeName = parts[1]
                
                if (itemId == null) {
                    bot.callbackNotice(callback.id, "Ошибка: неверный ID элемента.")
                    return
                }
                
                try {
                    val itemType = RecommendationType.valueOf(itemTypeName)
                    FavoriteRepository.remove(uid, itemId, itemType)
                    bot.callbackNotice(callback.id, "✅ Удалено из избранного!")
                    
                    // Обновляем сообщение с новым состоянием кнопки
                    val type = session.data["search_type"]?.let { RecommendationType.valueOf(it) }
                    val query = session.data["search_query"]
                    val currentIndex = session.data["search_current_index"]?.toIntOrNull() ?: 0
                    
                    if (type != null && query != null) {
                        runBlocking {
                            try {
                                val result = RecommendationRepository.searchMultiple(type, query, 20, offset = 0)
                                if (currentIndex < result.items.size) {
                                    RouterCollectionsSearch.showSingleSearchResult(bot, chat, uid, result.items, currentIndex, result.hasMore)
                                }
                            } catch (e: Exception) {
                                println("Error updating search result after removal: ${e.message}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    println("Error removing from favorites: ${e.message}")
                    bot.callbackNotice(callback.id, "Ошибка при удалении.")
                }
            }
            
            data.startsWith("delete_favorite_") -> {
                val parts = data.substringAfter("delete_favorite_").split("_")
                if (parts.size != 2) {
                    bot.callbackNotice(callback.id, "Ошибка: неверный формат данных.")
                    return
                }
                
                val itemId = parts[0].toIntOrNull()
                val itemTypeName = parts[1]
                
                if (itemId == null) {
                    bot.callbackNotice(callback.id, "Ошибка: неверный ID элемента.")
                    return
                }
                
                try {
                    val itemType = RecommendationType.valueOf(itemTypeName)
                    FavoriteRepository.remove(uid, itemId, itemType)
                    bot.callbackNotice(callback.id, "✅ Удалено из избранного!")
                    
                    // Обновляем список избранных и показываем обновленный интерфейс
                    runBlocking {
                        val updatedFavorites = FavoriteRepository.list(uid)
                        if (updatedFavorites.isEmpty()) {
                            // Если избранных больше нет, остаемся в меню подборок
                            val session = SessionStore.get(uid)
                            session.action = PendingAction.NONE
                            val profile = users.profile(uid)
                            val collectionsMessage = """🔍 <b>Меню подборок</b>

Здесь вы можете:
• 🔍 <b>Поиск</b> - находить фильмы, сериалы, книги и игры по запросу
• ⭐️ <b>Избранное</b> - просматривать сохраненные элементы

Выберите действие:"""
                            bot.screenText(chat, "У вас больше нет избранных элементов.")
                            ImageManager.sendMessageWithImage(
                                bot, chat, collectionsMessage, profile,
                                replyMarkup = KeyboardFactory.collectionsMenu(),
                                parseMode = ParseMode.HTML
                            )
                        } else {
                            // Показываем обновленный список
                            val session = SessionStore.get(uid)
                            val currentIndex = session.data["favorites_index"]?.toIntOrNull() ?: 0
                            
                            // Если удалили последний элемент и он был текущим, показываем предыдущий
                            val newIndex = if (currentIndex >= updatedFavorites.size) {
                                updatedFavorites.size - 1
                            } else {
                                currentIndex
                            }
                            
                            session.data["favorites_index"] = newIndex.toString()
                            session.data["favorites_total"] = updatedFavorites.size.toString()

                            RouterCollectionsFavorites.showFavoriteItem(bot, chat, uid, updatedFavorites, newIndex)
                        }
                    }
                } catch (e: Exception) {
                    println("Error deleting from favorites: ${e.message}")
                    bot.callbackNotice(callback.id, "Ошибка при удалении.")
                }
            }
            
            data.startsWith("nav_favorite_") -> {
                val newIndex = data.substringAfter("nav_favorite_").toIntOrNull()
                if (newIndex == null) {
                    bot.callbackNotice(callback.id, "Ошибка: неверный индекс.")
                    return
                }
                
                val favorites = FavoriteRepository.list(uid)
                if (newIndex < 0 || newIndex >= favorites.size) {
                    bot.callbackNotice(callback.id, "Ошибка: индекс вне диапазона.")
                    return
                }
                
                // Обновляем индекс в сессии
                val session = SessionStore.get(uid)
                session.data["favorites_index"] = newIndex.toString()
                
                // Показываем новый элемент
                runBlocking {
                    RouterCollectionsFavorites.showFavoriteItem(bot, chat, uid, favorites, newIndex)
                }
                
                bot.callbackNotice(callback.id)
            }
        }
    }
}
