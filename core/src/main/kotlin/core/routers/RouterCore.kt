package core.routers

import data.models.*
import data.services.AdminService
import data.services.AvatarService
import core.ui.*
import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.Message
import com.github.kotlintelegrambot.entities.User
import com.github.kotlintelegrambot.entities.ParseMode
import kotlinx.coroutines.*
import core.keyboards.KeyboardFactory
import core.keyboards.KeyboardProfile
import core.ImageManager
import core.SessionStore
import core.FSMContext
import core.PendingAction
import core.Session
import core.FileValidator
import core.routers.routerAdmin.RouterAdmin
import core.routers.routerAdmin.RouterAdminUsers
import core.routers.routerCollections.RouterCollections
import data.repositories.UserRepository
import data.repositories.MemeRepository
import data.repositories.PredictionRepository
import data.repositories.GameRepository
import data.repositories.TestRepository
import data.repositories.EventRepository
import data.repositories.FeedbackRepository

class RouterCore(
    private val users: UserRepository,
    private val memes: MemeRepository,
    private val predictions: PredictionRepository,
    private val games: GameRepository,
    private val tests: TestRepository,
    private val events: EventRepository,
    private val feedback: FeedbackRepository
) {
    private fun chatId(message: Message): Long = message.chat.id
    private fun sender(message: Message): User? = message.from

    private fun displayName(user: User?): String = when {
        user?.username != null -> "@${user.username}"
        !user?.firstName.isNullOrBlank() -> user!!.firstName
        else -> "Пользователь"
    }

    // Проверка бана пользователя
    private fun checkUserBan(bot: Bot, chat: ChatId, uid: Long, serviceName: String): Boolean {
        return if (AdminService.isUserBanned(uid)) {
            val message = """🚫 <b>Доступ ограничен</b>

Вы были забанены и не можете использовать сервис "$serviceName".

Доступные функции:
👤 Профиль
⚙️ Настройки  
💬 Обратная связь

Для разбана обратитесь в службу поддержки."""
            bot.screenText(chat, message, parseMode = ParseMode.HTML, replyMarkup = KeyboardFactory.mainMenu())
            true
        } else {
            false
        }
    }

    fun handle(bot: Bot, message: Message) {
        val actor = message.from ?: return
        ScreenRenderer.transaction(bot, message.chat.id, actor.id) {
            handleMessage(bot, message)
        }
    }

    private fun handleMessage(bot: Bot, message: Message) {
        val sender = sender(message) ?: return
        val chat = ChatId.fromId(chatId(message))
        val uid = sender.id
        val uname = sender.username ?: ""
        val dname = displayName(sender)

        users.ensure(uid, uname, dname)
        users.updateLastActivity(uid)  // Обновляем время последней активности
        val session = SessionStore.get(uid)
        val activeUid = session.data["profile_impersonated_user_id"]?.toLongOrNull() ?: uid
        val text = message.text?.trim()

        // Обработка фото для аватарки профиля
        if (session.action == PendingAction.EDIT_AVATAR && 
            (message.photo != null || message.document != null || message.video != null || 
             message.audio != null || message.voice != null || message.animation != null || message.sticker != null)) {
            
            val (isValid, errorMessage) = FileValidator.validateAvatarImage(message)
            
            if (!isValid) {
                bot.screenText(chat, errorMessage ?: "❌ Невозможно установить эту картинку на аватарку.")
                return
            }
            
            val photo = message.photo!!.lastOrNull() ?: return
            val (width, height) = Pair(photo.width, photo.height)
            
            // Проверяем, квадратное ли изображение
            if (FileValidator.isSquareImage(width, height)) {
                // Изображение квадратное - сразу сохраняем
                users.setAvatar(activeUid, photo.fileId)
                session.action = PendingAction.NONE
                session.clearDraft()
                bot.screenText(chat, "✅ Аватарка сохранена!", replyMarkup = KeyboardFactory.profileMenu(users.profile(activeUid)))
            } else {
                // Изображение не квадратное - предлагаем варианты обработки
                val (minSize, dimension) = FileValidator.getImageProcessingInfo(width, height)
                
                session.action = PendingAction.AVATAR_PROCESSING
                session.data["avatar_file_id"] = photo.fileId
                session.data["avatar_width"] = width.toString()
                session.data["avatar_height"] = height.toString()
                
                val messageText = """📐 <b>Изображение не квадратное</b>

Размеры: ${width}x${height}
По ${dimension} больше на ${maxOf(width, height) - minOf(width, height)}px

Выберите, как установить аватарку:"""
                
                bot.screenText(
                    chat, 
                    messageText, 
                    parseMode = ParseMode.HTML,
                    replyMarkup = KeyboardProfile.avatarProcessingMenu(minSize)
                )
            }
            return
        }

        // Обработка фото для добавления мема (системная функция)
        if (session.action == PendingAction.ADD_MEME && 
            (message.photo != null || message.document != null || message.video != null || 
             message.audio != null || message.voice != null || message.animation != null || message.sticker != null)) {
            if (checkUserBan(bot, chat, activeUid, "😂 Мемы")) return
            
            val (isValid, errorMessage) = FileValidator.validateMemeImage(message)
            
            if (!isValid) {
                bot.screenText(chat, errorMessage ?: "❌ Невозможно добавить этот файл как мем.")
                return
            }
            
            val photo = message.photo!!.lastOrNull() ?: return
            val caption = message.caption.orEmpty()
            memes.addMeme(photo.fileId, activeUid, caption)
            session.action = PendingAction.NONE
            RouterMemes.showMenu(bot, chat, activeUid, "✅ Мем добавлен в общую ленту! Можно добавить ещё один или перейти к просмотру.", session)
            return
        }

        if (text.isNullOrBlank()) return

        // Навигация раздела доступна и во время ожидания фото.
        if (RouterMemes.isNavigation(text) ||
            (text == "⬅️ Обратно" && session.context == FSMContext.MEMES &&
                session.data["meme_screen"] != null &&
                session.action in listOf(PendingAction.NONE, PendingAction.ADD_MEME))) {
            if (checkUserBan(bot, chat, activeUid, "😂 Мемы")) return
            RouterMemes.handleMemeAction(bot, chat, activeUid, text, memes, users, session)
            return
        }
        if ((text.startsWith("/") && text.substringBefore("@") != "/memes") || text in listOf(
                "👤 Профиль", "🗂 Подборки", "🔮 Предсказания", "📝 Тесты", "📅 События",
                "🎮 Мини-игры", "🖼 Пиксель-арт", "💬 Обратная связь", "⚙️ Настройки"
            )) {
            session.data.remove("meme_screen")
            if (session.action == PendingAction.ADD_MEME) session.action = PendingAction.NONE
        }

        // Сначала проверяем системные команды
        when (if (text.startsWith("/")) text.substringBefore("@") else text) {
            "/start", "/start@" -> handleStart(bot, chat, uid)
            "/menu", "/menu@" -> handleMenu(bot, chat, uid)
            "/myid" -> {
                val profile = users.profile(uid)
                val isAdmin = AdminService.isAdmin(uid)
                val myidMessage = """🔍 <b>Информация о пользователе</b>

🆔 Ваш ID: <code>$uid</code>
👤 Имя: ${profile?.displayName ?: "неизвестно"}
🔹 Админ: ${if (isAdmin) "✅ Да" else "❌ Нет"}

📋 Админские ID в системе: ${AdminService.adminIds}"""
                bot.screenText(chat, myidMessage, parseMode = ParseMode.HTML)
            }
            // Команды открытия меню (поддерживают суффикс @BotUsername)
            "/profile" -> RouterProfile.showProfile(bot, chat, uid, users)
            "/collections" ->
                    RouterCollections.handleCollectionAction(bot, chat, activeUid, "🗂 Подборки", users, session)
            "/memes" ->
                    if (!checkUserBan(bot, chat, activeUid, "😂 Мемы"))
                        RouterMemes.handleMemeAction(bot, chat, activeUid, "😂 Мемы", memes, users, session)
            "/predictions" ->
                    RouterPredictions.handlePredictionAction(bot, chat, activeUid, "🔮 Предсказания", predictions, users, session)
            "/tests" ->
                    RouterTests.handleTestAction(bot, chat, activeUid, "📝 Тесты", tests, users, session)
            "/events" ->
                    RouterEvents.handleEventAction(bot, chat, activeUid, "📅 События", events, users, session)
            "/games" ->
                    RouterGames.handleGameAction(bot, chat, activeUid, "🎮 Мини-игры", games, users, session)
            "/reload_admins" -> {
                val newAdmins = AdminService.reloadAdmins()
                val reloadMessage = """🔄 <b>Админские ID перезагружены</b>

📋 Новый список: $newAdmins

🆔 Ваш ID: <code>$uid</code>
🔹 Вы админ: ${if (uid in newAdmins) "✅ Да" else "❌ Нет"}"""
                bot.screenText(chat, reloadMessage, parseMode = ParseMode.HTML)
            }
            "⬅️ Обратно" -> {
                // Сначала проверяем контекст просмотра профиля
                if (session.context == FSMContext.PROFILE_VIEW) {
                    // Если просматривали профиль пользователя - возвращаемся в предыдущий список/действие
                    val prevActionName = session.data["admin_profile_view_prev_action"]
                    val prevAction = if (prevActionName != null) {
                        try {
                            PendingAction.valueOf(prevActionName)
                        } catch (e: Exception) {
                            PendingAction.NONE
                        }
                    } else {
                        PendingAction.NONE
                    }

                    session.context = FSMContext.MEMES
                    session.action = PendingAction.NONE

                    // Восстанавливаем соответствующий список на основе предыдущего action
                    when (prevAction) {
                        PendingAction.ADMIN_USER_LIST -> {
                            session.action = PendingAction.ADMIN_USER_LIST
                            session.data.remove("admin_profile_view_prev_action")
                            RouterAdminUsers.showUserList(bot, chat, uid, session.data["admin_current_index"]?.toIntOrNull() ?: 0)
                        }
                        PendingAction.ADMIN_BANNED_LIST -> {
                            session.action = PendingAction.ADMIN_BANNED_LIST
                            session.data.remove("admin_profile_view_prev_action")
                            RouterAdminUsers.showBannedUsers(bot, chat, uid, users, session.data["admin_current_index"]?.toIntOrNull() ?: 0)
                        }
                        PendingAction.ADMIN_SEARCH_RESULTS,
                        PendingAction.ADMIN_EDIT_BAN_EXPIRY -> {
                            session.action = PendingAction.ADMIN_SEARCH_RESULTS
                            session.data.remove("admin_profile_view_prev_action")
                            val resultsString = session.data["admin_search_results"]
                            if (resultsString != null) {
                                val userIds = resultsString.split("|").mapNotNull { it.toLongOrNull() }
                                val searchUsers = userIds.mapNotNull { users.profile(it) }
                                RouterAdminUsers.showSearchResults(bot, chat, uid, searchUsers, session.data["admin_current_index"]?.toIntOrNull() ?: 0)
                            } else {
                                RouterAdmin.showAdminPanel(bot, chat, uid)
                            }
                        }
                        else -> {
                            // Если не удалось восстановить - возвращаем в админ меню
                            RouterAdmin.showAdminPanel(bot, chat, uid)
                        }
                    }
                }
                // Проверяем, находимся ли мы в контексте редактирования профиля
                else if (session.action in listOf(
                    PendingAction.EDIT_PROFILE,
                    PendingAction.EDIT_NAME,
                    PendingAction.EDIT_BIO,
                    PendingAction.EDIT_AVATAR,
                    PendingAction.AVATAR_PROCESSING
                )) {
                    // Если в контексте редактирования профиля - возвращаемся в меню профиля
                    session.action = PendingAction.NONE
                    session.clearDraft()
                    RouterProfile.showProfile(bot, chat, uid, users)
                } else if (session.context == FSMContext.COLLECTIONS || session.action in listOf(
                        PendingAction.VIEW_FAVORITES,
                        PendingAction.SEARCH_TYPE_SELECT,
                        PendingAction.COLLECTION_QUERY,
                        PendingAction.COLLECTION_RESULTS
                )) {
                    RouterCollections.handleCollectionAction(bot, chat, activeUid, text, users, session)
                } else if (session.action == PendingAction.SEARCH_PREDICTIONS) {
                    RouterPredictions.handlePredictionAction(bot, chat, activeUid, text, predictions, users, session)
                } else if (session.action in listOf(
                        PendingAction.SEARCH_TESTS,
                        PendingAction.TEST_SEARCH_RESULTS,
                        PendingAction.CREATE_TEST_TITLE,
                        PendingAction.CREATE_TEST_KIND,
                        PendingAction.CREATE_TEST_PROMPT,
                        PendingAction.CREATE_TEST_MORE,
                        PendingAction.PLAY_TEST
                )) {
                    // Возврат из подменю тестов в меню тестов
                    RouterTests.handleTestAction(bot, chat, activeUid, text, tests, users, session)
                } else if (session.action in listOf(
                    PendingAction.ADMIN_USER_MANAGEMENT,
                    PendingAction.ADMIN_USER_LIST,
                    PendingAction.ADMIN_BANNED_LIST,
                    PendingAction.ADMIN_SEARCH,
                    PendingAction.ADMIN_SEARCH_RESULTS,
                    PendingAction.ADMIN_EDIT_BAN_EXPIRY
                )) {
                    // Если в админском контексте - передаем в RouterAdmin
                    RouterAdmin.handleAdminAction(bot, chat, uid, text, users, session)
                } else {
                    // Для остальных контекстов - обычная логика
                    handleBack(bot, chat, uid)
                }
            }
            else -> {
                // Перенаправляем в модули если не системная команда
                if (session.action != PendingAction.NONE && handlePending(bot, chat, uid, text, session)) {
                    return
                }
                handleModuleRoutes(bot, chat, uid, activeUid, text, session)
            }
        }
    }

    // Системные команды
    private fun handleStart(bot: Bot, chat: ChatId, uid: Long) {
        ScreenRenderer.home()
        SessionStore.clear(uid)
        val profile = users.profile(uid)
        val welcomeMessage = "Привет! Это Chill Zone Bot — всё для досуга в одном месте."
        bot.screenText(chat, welcomeMessage, replyMarkup = KeyboardFactory.mainMenu())
    }

    private fun handleMenu(bot: Bot, chat: ChatId, uid: Long) {
        ScreenRenderer.home()
        SessionStore.clear(uid)
        val menuMessage = "Главное меню."
        bot.screenText(chat, menuMessage, replyMarkup = KeyboardFactory.mainMenu())
    }

    private fun handleBack(bot: Bot, chat: ChatId, uid: Long) {
        SessionStore.clear(uid)
        bot.screenText(chat, "Главное меню.", replyMarkup = KeyboardFactory.mainMenu())
    }

    // Перенаправление в модули
    private fun handleModuleRoutes(
        bot: Bot,
        chat: ChatId,
        uid: Long,
        activeUid: Long,
        text: String,
        session: Session
    ) {
        when (text) {
            "👤 Профиль" -> RouterProfile.showProfile(bot, chat, uid, users)
            // Кнопки профиля
            "✏️ Изменить профиль", "📊 Статистика аккаунта", "🛡️ Админ панель", "🛡️ Панель модератора", "Изменить имя", "Показать username [👁️]", "Скрыть username [🙈]", "Изменить био", "Показать профиль [👁️]", "Скрыть профиль [🙈]", "🖼️ Добавить аватарку", "🖼️ Изменить аватарку", "⬅️ Назад", "Вернуться в свой профиль", "Сменить аккаунт на пользователя", "Сменить аккаунт на админа" -> {
                RouterProfile.handleProfileAction(bot, chat, uid, text, users, session)
            }
            // Админские кнопки
            "👥 Список пользователей", "🔍 Поиск пользователей", "🚫 Забаненные пользователи", "📊 Статистика",
            " Показать всех", "✅ Показать разбаненных", "🚫 Показать забаненных",
            "⬅️ Предыдущий", "➡️ Следующий", "⬅️ В список", "⬅️ Отмена",
            "🚫 Забанить", "✅ Разбанить", "📝 Написать причину бана" -> {
                RouterAdmin.handleAdminAction(bot, chat, uid, text, users, session)
            }
            "⚙️ Настройки" -> RouterSettings.handleSettingsAction(bot, chat, uid, text, users)
            // Кнопки настроек
            "Включить картинки [✅]", "Выключить картинки [❌]" -> {
                RouterSettings.handleSettingsAction(bot, chat, uid, text, users)
            }
            "🗂 Подборки" -> {
                if (checkUserBan(bot, chat, activeUid, "🗂 Подборки")) return
                RouterCollections.handleCollectionAction(bot, chat, activeUid, text, users, session)
            }
            "🔍 Поиск" -> {
                if (checkUserBan(bot, chat, activeUid, "🔍 Поиск")) return
                RouterCollections.handleCollectionAction(bot, chat, activeUid, text, users, session)
            }
            "⭐ Избранное" -> {
                if (checkUserBan(bot, chat, activeUid, "⭐ Избранное")) return
                RouterCollections.handleCollectionAction(bot, chat, activeUid, text, users, session)
            }
            "😂 Мемы" -> {
                if (checkUserBan(bot, chat, activeUid, "😂 Мемы")) return
                RouterMemes.handleMemeAction(bot, chat, activeUid, text, memes, users, session)
            }
            "Следующий мем", "Добавить мем", "💾 Избр. мем", "Мои избр. мемы" -> {
                if (checkUserBan(bot, chat, activeUid, "😂 Мемы")) return
                RouterMemes.handleMemeAction(bot, chat, activeUid, text, memes, users, session)
            }
            "👍" -> {
                if (checkUserBan(bot, chat, activeUid, "😂 Мемы")) return
                RouterMemes.handleMemeAction(bot, chat, activeUid, text, memes, users, session)
            }
            "👎" -> {
                if (checkUserBan(bot, chat, activeUid, "😂 Мемы")) return
                RouterMemes.handleMemeAction(bot, chat, activeUid, text, memes, users, session)
            }
            "Мои избранные" -> {
                if (checkUserBan(bot, chat, activeUid, "😂 Мемы")) return
                RouterMemes.handleMemeAction(bot, chat, activeUid, text, memes, users, session)
            }
            "🔮 Предсказания" -> {
                if (checkUserBan(bot, chat, activeUid, "🔮 Предсказания")) return
                RouterPredictions.handlePredictionAction(bot, chat, activeUid, text, predictions, users, session)
            }
            "Получить предсказание", "Мои предсказания", "Поиск предсказаний", "Получить ещё", "Добавить в избранные" -> {
                if (checkUserBan(bot, chat, activeUid, "🔮 Предсказания")) return
                RouterPredictions.handlePredictionAction(bot, chat, activeUid, text, predictions, users, session)
            }
            "📝 Тесты" -> {
                if (checkUserBan(bot, chat, activeUid, "📝 Тесты")) return
                RouterTests.handleTestAction(bot, chat, activeUid, text, tests, users, session)
            }
            "📅 События" -> {
                if (checkUserBan(bot, chat, activeUid, "📅 События")) return
                RouterEvents.handleEventAction(bot, chat, activeUid, text, events, users, session)
            }
            "Ближайшие события", "Мои события", "Создать событие" -> {
                if (checkUserBan(bot, chat, activeUid, "📅 События")) return
                RouterEvents.handleEventAction(bot, chat, activeUid, text, events, users, session)
            }
            "⚽ Гол", "🏀 В кольцо", "🎡 Колесо фортуны", "✂️ Камень-ножницы-бумага", "📊 Моя статистика", "🏆 Топ игроков", "🎮 Мини-игры" -> {
                if (checkUserBan(bot, chat, activeUid, "🎮 Мини-игры")) return
                RouterGames.handleGameAction(bot, chat, activeUid, text, games, users, session)
            }
            "Публичный холст", "🖼 Пиксель-арт" -> {
                if (checkUserBan(bot, chat, activeUid, "🖼 Пиксель-арт")) return
                RouterPixelArt.handlePixelAction(bot, chat, activeUid, text, users)
            }
            "Оставить отзыв", "Посмотреть поддержку", "💬 Обратная связь" -> RouterFeedBack.handleFeedbackAction(bot, chat, activeUid, text, feedback, users, session)
            "💾 Сохранить" -> handleSaveAction(bot, chat, activeUid, session)
            "➡️ Ещё" -> handleMoreAction(bot, chat, activeUid, session)
            "Случайный тест", "Создать тест", "Мои тесты", "Поиск тестов" -> {
                if (checkUserBan(bot, chat, activeUid, "📝 Тесты")) return
                RouterTests.handleTestAction(bot, chat, activeUid, text, tests, users, session)
            }
            "🔍 Новый запрос" -> {
                // Передаем обработку в RouterCollections
                if (RouterCollections.handleCollectionAction(bot, chat, uid, text, users, session)) {
                    return
                } else {
                    handleUnknownCommand(bot, chat)
                }
            }
            else -> {
                // Админские кнопки с динамическим текстом
                if (text.startsWith("🔄 Фильтр:")) {
                    RouterAdmin.handleAdminAction(bot, chat, uid, text, users, session)
                    return
                }
                handleUnknownCommand(bot, chat)
            }
        }
    }

    private fun handleUnknownCommand(bot: Bot, chat: ChatId) {
        // Не перекидываем пользователя в другое меню: клавиатура текущего меню сохраняется,
        // поэтому пользователь остаётся там, где был.
        bot.screenText(chat, "🤷 Не понял команду. Вы остались в текущем меню.")
    }

    // Специальные обработчики для контекстных команд
    private fun handleSaveAction(bot: Bot, chat: ChatId, uid: Long, session: Session) {
        when (session.context) {
            core.FSMContext.MEMES -> {
                RouterMemes.handleMemeAction(bot, chat, uid, "💾 Сохранить", memes, users, session)
            }
            core.FSMContext.COLLECTIONS -> {
                RouterCollections.handleCollectionAction(bot, chat, uid, "💾 Сохранить", users, session)
            }
            else -> {
                RouterMemes.handleMemeAction(bot, chat, uid, "💾 Сохранить", memes, users, session)
            }
        }
    }

    private fun handleFavoriteAction(bot: Bot, chat: ChatId, uid: Long, session: Session) {
        when (session.context) {
            core.FSMContext.MEMES -> {
                RouterMemes.handleMemeAction(bot, chat, uid, "⭐ Избранное", memes, users, session)
            }
            core.FSMContext.COLLECTIONS -> {
                RouterCollections.handleCollectionAction(bot, chat, uid, "⭐ Избранное", users, session)
            }
            else -> {
                RouterMemes.handleMemeAction(bot, chat, uid, "⭐ Избранное", memes, users, session)
            }
        }
    }

    private fun handleMoreAction(bot: Bot, chat: ChatId, uid: Long, session: Session) {
        RouterCollections.handleCollectionAction(bot, chat, uid, "➡️ Ещё", users, session)
    }

    // Обработка pending actions - перенаправление в модули
    private fun handlePending(bot: Bot, chat: ChatId, uid: Long, text: String, session: Session): Boolean {
        val activeUid = session.data["profile_impersonated_user_id"]?.toLongOrNull() ?: uid
        return when (session.action) {
            PendingAction.ADD_MEME -> RouterMemes.handleMemeAction(bot, chat, activeUid, text, memes, users, session)
            PendingAction.AVATAR_PROCESSING -> handleAvatarProcessing(bot, chat, activeUid, text, session)
            PendingAction.EDIT_PROFILE, PendingAction.EDIT_NAME, PendingAction.EDIT_BIO -> {
                RouterProfile.handleProfileAction(bot, chat, uid, text, users, session)
            }
            PendingAction.ADMIN_EDIT_BAN_REASON -> {
                // Обработка ввода причины бана
                val targetUserId = session.data["admin_edit_target_user"]?.toLongOrNull()
                if (targetUserId != null) {
                    if (AdminService.setBanReason(targetUserId, text)) {
                        val targetUser = users.profile(targetUserId)
                        if (targetUser != null) {
                            bot.screenText(chat, "✅ Причина бана установлена: \"<b>$text</b>\"", parseMode = ParseMode.HTML)
                            session.action = PendingAction.NONE
                            session.data.remove("admin_edit_target_user")
                            RouterAdminUsers.showUserProfileView(bot, chat, uid, targetUser, users)
                        } else {
                            bot.screenText(chat, "❌ Ошибка: пользователь не найден")
                        }
                    } else {
                        bot.screenText(chat, "❌ Ошибка при сохранении причины бана")
                    }
                } else {
                    bot.screenText(chat, "❌ Ошибка: не указан пользователь")
                }
                true
            }
            PendingAction.RPS_CHOICE -> RouterGames.handleRpsChoice(bot, chat, activeUid, text, games, users, session)
            PendingAction.FEEDBACK_TEXT -> {
                RouterFeedBack.handleFeedbackAction(bot, chat, activeUid, text, feedback, users, session)
            }
            PendingAction.ADD_PREDICTION_TEXT, PendingAction.ADD_PREDICTION_RARITY, PendingAction.SEARCH_PREDICTIONS -> {
                RouterPredictions.handlePredictionAction(bot, chat, activeUid, text, predictions, users, session)
            }
            PendingAction.CREATE_TEST_TITLE, PendingAction.CREATE_TEST_KIND, PendingAction.CREATE_TEST_PROMPT, 
            PendingAction.CREATE_TEST_MORE -> {
                RouterTests.handleTestAction(bot, chat, activeUid, text, tests, users, session)
            }
            PendingAction.PLAY_TEST -> {
                RouterTests.handleTestAnswer(bot, chat, activeUid, text, session, tests, users)
            }
            PendingAction.SEARCH_TESTS, PendingAction.TEST_SEARCH_RESULTS -> {
                RouterTests.handleTestAction(bot, chat, activeUid, text, tests, users, session)
            }
            PendingAction.CREATE_EVENT_TITLE, PendingAction.CREATE_EVENT_DESC, PendingAction.CREATE_EVENT_PLACE,
            PendingAction.CREATE_EVENT_TIME, PendingAction.CREATE_EVENT_MAX, PendingAction.CREATE_EVENT_KIND -> {
                RouterEvents.handleEventAction(bot, chat, activeUid, text, events, users, session)
            }
            PendingAction.SEARCH_TYPE_SELECT, PendingAction.COLLECTION_QUERY, PendingAction.COLLECTION_RESULTS -> {
                RouterCollections.handleCollectionAction(bot, chat, activeUid, text, users, session)
            }
            // Админские действия
            PendingAction.ADMIN_USER_MANAGEMENT,
            PendingAction.ADMIN_USER_LIST,
            PendingAction.ADMIN_BANNED_LIST,
            PendingAction.ADMIN_SEARCH,
            PendingAction.ADMIN_SEARCH_RESULTS,
            PendingAction.ADMIN_EDIT_BAN_EXPIRY,
            PendingAction.ADMIN_EDIT_BAN_REASON,
            PendingAction.ADMIN_EDIT_PROFILE,
            PendingAction.ADMIN_EDIT_NAME,
            PendingAction.ADMIN_EDIT_USERNAME,
            PendingAction.ADMIN_EDIT_BIO -> {
                RouterAdmin.handleAdminAction(bot, chat, uid, text, users, session)
                true
            }
            else -> false
        }
    }
    
    private fun handleAvatarProcessing(bot: Bot, chat: ChatId, uid: Long, text: String, session: Session): Boolean {
        return when {
            text.startsWith("✂️ Обрезать") -> {
                val fileId = session.data["avatar_file_id"] ?: return true
                val processedFilePath = AvatarService.processAvatarImage(fileId, "crop")
                if (processedFilePath != null) {
                    users.setAvatar(uid, processedFilePath)
                    session.action = PendingAction.NONE
                    session.clearDraft()
                    RouterProfile.showProfile(bot, chat, uid, users)
                } else {
                    bot.screenText(chat, "❌ Ошибка при обработке изображения")
                }
                true
            }
            text == "📦 Добавить границы до квадрата" -> {
                val fileId = session.data["avatar_file_id"] ?: return true
                val processedFilePath = AvatarService.processAvatarImage(fileId, "letterbox")
                if (processedFilePath != null) {
                    users.setAvatar(uid, processedFilePath)
                    session.action = PendingAction.NONE
                    session.clearDraft()
                    RouterProfile.showProfile(bot, chat, uid, users)
                } else {
                    bot.screenText(chat, "❌ Ошибка при обработке изображения")
                }
                true
            }
            text == "⬅️ Отмена" -> {
                session.action = PendingAction.NONE
                session.clearDraft()
                RouterProfile.showProfile(bot, chat, uid, users)
                true
            }
            else -> false
        }
    }
    
    // Обработка callback запросов - перенаправление в соответствующие модули
    fun handleCallback(bot: Bot, callback: com.github.kotlintelegrambot.entities.CallbackQuery) {
        val message = callback.message ?: run {
            bot.answerCallbackQuery(callback.id, "Открой /menu в чате с ботом.")
            return
        }
        ScreenRenderer.transaction(bot, message.chat.id, callback.from.id, callback) { action ->
            val uid = callback.from.id
            val session = SessionStore.get(uid)
            val chat = ChatId.fromId(message.chat.id)
            val activeUid = session.data["profile_impersonated_user_id"]?.toLongOrNull() ?: uid
            var command = action ?: return@transaction
            if (command.startsWith("confirm:")) {
                command = command.removePrefix("confirm:")
            } else if (command.startsWith("admin_ban_") || command.startsWith("admin_unban_") ||
                command.startsWith("admin_role_toggle_") || command in listOf("text:🚫 Забанить", "text:✅ Разбанить")) {
                if (!AdminService.isAdmin(uid) && users.profile(uid)?.accountType != AccountType.MODERATOR) {
                    bot.screenText(chat, "Недостаточно прав.")
                    return@transaction
                }
                val target = command.substringAfterLast('_').toLongOrNull()
                    ?: session.data["admin_profile_view_user_id"]?.toLongOrNull()
                ScreenRenderer.pushHistory()
                bot.screenText(chat, "Подтверди действие над пользователем #${target ?: "неизвестен"}: " +
                    when {
                        command.contains("unban") || command == "text:✅ Разбанить" -> "снять блокировку"
                        command.contains("role_toggle") -> "изменить роль"
                        else -> "заблокировать"
                    }, replyMarkup = com.github.kotlintelegrambot.entities.InlineKeyboardMarkup.create(listOf(listOf(
                        com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton.CallbackData("Подтвердить", "confirm:$command"),
                        com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton.CallbackData("Отмена", "text:⬅️ Обратно")
                    ))))
                return@transaction
            }
            if (command == "text:🏠 Главное меню" || command == "text:/menu") {
                ScreenRenderer.home()
                handleMenu(bot, chat, uid)
            } else if (command == "text:❌ Отмена") {
                if (!ScreenRenderer.cancel()) handleMenu(bot, chat, uid)
            } else if (command.startsWith("answer:")) {
                if (checkUserBan(bot, chat, uid, "📝 Тесты") ||
                    (activeUid != uid && checkUserBan(bot, chat, activeUid, "📝 Тесты"))) return@transaction
                if (session.action == PendingAction.PLAY_TEST) RouterTests.handleTestAnswer(bot, chat, activeUid,
                    command.removePrefix("answer:"), session, tests, users)
            } else if (command in listOf("text:⬅️ Обратно", "text:⬅️ Назад", "text:⬅️ Отмена", "text:❌ Отменить добавление")) {
                if (!ScreenRenderer.back()) handleMenu(bot, chat, uid)
            } else if (command.startsWith("text:")) {
                val text = command.removePrefix("text:")
                if (isForwardNavigation(text)) ScreenRenderer.pushHistory()
                // Buttons are commands, never accidental text input into a pending form.
                if (!handleButton(bot, chat, uid, activeUid, text, session)) {
                    handleMessage(bot, message.copy(from = callback.from, text = text))
                }
            } else {
                if (command.startsWith("admin_profile_") || command.startsWith("test_search_start_")) ScreenRenderer.pushHistory()
                handleServiceCallback(bot, callback.copy(data = command))
            }
        }
    }

    private fun isForwardNavigation(text: String): Boolean = text in setOf(
        "👤 Профиль", "🗂 Подборки", "😂 Мемы", "🔮 Предсказания", "📝 Тесты", "📅 События",
        "🎮 Мини-игры", "🖼 Пиксель-арт", "💬 Обратная связь", "⚙️ Настройки",
        "✏️ Изменить профиль", "📊 Статистика аккаунта", "🛡️ Админ панель", "🛡️ Панель модератора",
        "Изменить имя", "Изменить био", "🖼️ Добавить аватарку", "🖼️ Изменить аватарку",
        "🔍 Поиск", "⭐ Избранное", "🎲 Смотреть мемы", "⭐ Избранные мемы", "➕ Добавить мем",
        "Поиск предсказаний", "Мои предсказания", "Создать тест", "Поиск тестов", "Мои тесты",
        "Создать событие", "Мои события", "Оставить отзыв", "Посмотреть поддержку", "Публичный холст",
        "👥 Список пользователей", "🔍 Поиск пользователей", "🚫 Забаненные пользователи",
        "📊 Статистика", "📊 Моя статистика", "🏆 Топ игроков", "✂️ Камень-ножницы-бумага"
    )

    private fun handleButton(bot: Bot, chat: ChatId, uid: Long, activeUid: Long, text: String, session: Session): Boolean {
        if (RouterMemes.isNavigation(text)) {
            if (!checkUserBan(bot, chat, activeUid, "😂 Мемы")) {
                RouterMemes.handleMemeAction(bot, chat, activeUid, text, memes, users, session)
            }
            return true
        }
        if (text == "Вернуться в свой профиль") {
            ScreenRenderer.home()
            return RouterProfile.handleProfileAction(bot, chat, uid, text, users, session)
        }
        if (text.startsWith("Тип бана:") || text == "Изменить время бана" || text == "🕒 Получить точное время бана" ||
            text.startsWith("➕ ") || text.startsWith("➖ ") || text == "⬅️ К профилю") {
            RouterAdmin.handleAdminAction(bot, chat, uid, text, users, session)
            return true
        }
        if (text in listOf("🎬 Фильм", "📺 Сериал", "📚 Книга", "🎮 Игра")) {
            ScreenRenderer.pushHistory()
            return RouterCollections.handleCollectionAction(bot, chat, activeUid, text, users, session)
        }
        if (isForwardNavigation(text) || text in listOf("Получить предсказание", "Получить ещё", "Ближайшие события", "⚽ Гол", "🏀 В кольцо", "🎡 Колесо фортуны", "Случайный тест")) {
            session.action = PendingAction.NONE
            handleModuleRoutes(bot, chat, uid, activeUid, text, session)
            return true
        }
        return false
    }

    private fun handleServiceCallback(bot: Bot, callback: com.github.kotlintelegrambot.entities.CallbackQuery) {
        val uid = callback.from.id
        val uname = callback.from.username ?: ""
        val dname = displayName(callback.from)

        users.ensure(uid, uname, dname)
        users.updateLastActivity(uid)  // Обновляем время последней активности
        val data = callback.data
        val activeUid = SessionStore.get(uid).data["profile_impersonated_user_id"]?.toLongOrNull() ?: uid
        if (!data.startsWith("admin_") && !data.startsWith("ban_") &&
            (AdminService.isUserBanned(uid) || AdminService.isUserBanned(activeUid))) {
            bot.callbackNotice(callback.id, "Доступ ограничен.")
            return
        }

        when {
            // Админские функции
            data.startsWith("admin_") || data.startsWith("ban_add_") || data.startsWith("ban_sub_") || data == "ban_add_cancel" -> {
                RouterAdmin.handleCallback(bot, callback, users)
            }
            // Подборки - сохранение/удаление избранного
            data.startsWith("save_item_") || data.startsWith("delete_favorite_") || data.startsWith("nav_favorite_") || data.startsWith("nav_search_") || data.startsWith("save_single_") || data.startsWith("remove_item_") -> {
                RouterCollections.handleCallback(bot, callback, users)
            }
            data.startsWith("meme_") -> {
                RouterMemes.handleCallback(bot, callback, memes)
            }
            data.startsWith("test_") -> {
                RouterTests.handleCallback(bot, callback, tests, users)
            }
            // Другие callback запросы можно добавить здесь по мере необходимости
            else -> {
                bot.callbackNotice(callback.id, "Неизвестная команда.")
            }
        }
    }
}
