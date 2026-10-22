package core.routers.routerAdmin

import core.ui.*
import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.ParseMode
import core.FSMContext
import core.PendingAction
import core.SessionStore
import core.keyboards.KeyboardAdmin
import core.keyboards.KeyboardProfile
import core.routers.RouterProfile
import data.models.*
import data.repositories.UserRepository
import data.services.AdminService
import java.io.File
import java.time.Duration
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object RouterAdminUsers {
        private val dbDateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSS]")

        private fun roleLabel(accountType: AccountType): String = accountType.label

        private fun roleButtonText(user: UserProfile): String = roleLabel(user.accountType)

        private fun roleButtonCallback(user: UserProfile, currentUid: Long): String {
                return when {
                        user.userId == currentUid -> "admin_role_locked_${user.userId}"
                        else -> "admin_role_toggle_${user.userId}"
                }
        }

        private fun canEnterAccount(uid: Long, targetUser: UserProfile): Boolean {
                return AdminService.isBootstrapAdmin(uid) && targetUser.userId != uid
        }

        private fun parseBanExpiry(value: String): OffsetDateTime {
                return try {
                        OffsetDateTime.parse(value)
                } catch (_: Exception) {
                        try {
                                LocalDateTime.parse(value).atOffset(ZoneOffset.UTC)
                        } catch (_: Exception) {
                                try {
                                        LocalDateTime.parse(value.replace(' ', 'T'))
                                                .atOffset(ZoneOffset.UTC)
                                } catch (_: Exception) {
                                        LocalDateTime.parse(value, dbDateTimeFormatter)
                                                .atOffset(ZoneOffset.UTC)
                                }
                        }
                }
        }

        private fun formatRemainingBanTime(banExpiresAt: String?): String {
                if (banExpiresAt.isNullOrBlank()) return "0 дней, 0 часов"
                return try {
                        val expires = parseBanExpiry(banExpiresAt)
                        val now = OffsetDateTime.now(expires.offset)
                        val duration = Duration.between(now, expires)
                        val totalHours = duration.toHours().coerceAtLeast(0)
                        val days = (totalHours / 24)
                        val hours = (totalHours % 24)
                        "${days} дней, ${hours} часов"
                } catch (_: Exception) {
                        "0 дней, 0 часов"
                }
        }

        fun showUserList(
                bot: Bot,
                chat: ChatId,
                uid: Long,
                index: Int = 0,
                allowRoleActions: Boolean = true
        ) {
                val session = SessionStore.get(uid)
                val filter = session.data["admin_filter"] ?: "Все"

                // Получаем всех пользователей и применяем фильтр
                val allUsers = AdminService.getAllUsers(50, 0)
                val filteredUsers =
                        when (filter) {
                                "Все" -> allUsers
                                "Разбаненные" -> allUsers.filter { !it.isBanned }
                                "Забаненные" -> allUsers.filter { it.isBanned }
                                else -> allUsers
                        }

                if (filteredUsers.isEmpty()) {
                        val filterMessage =
                                when (filter) {
                                        "Все" -> "пользователей"
                                        "Разбаненные" -> "разбаненных пользователей"
                                        "Забаненные" -> "забаненных пользователей"
                                        else -> "пользователей"
                                }
                        bot.screenText(
                                chat,
                                "❌ $filterMessage не найдены",
                                replyMarkup = KeyboardAdmin.adminUserListMenu(filter)
                        )
                        return
                }

                if (index >= filteredUsers.size) return

                session.action = PendingAction.ADMIN_USER_LIST
                session.data["admin_current_index"] = index.toString()
                session.data["admin_total_count"] = filteredUsers.size.toString()

                // Отображаем информацию о текущей странице
                val startIndex = index + 1
                val endIndex = minOf(index + 5, filteredUsers.size)
                val pageUsers = filteredUsers.subList(startIndex - 1, endIndex)

                val filterText =
                        when (filter) {
                                "Все" -> "всех пользователей"
                                "Разбаненные" -> "разбаненных пользователей"
                                "Забаненные" -> "забаненных пользователей"
                                else -> "пользователей"
                        }

                val message =
                        """👥 <b>Список пользователей</b> ($startIndex-$endIndex из ${filteredUsers.size}) - $filterText

Выберите пользователя:"""

                // Создаем inline клавиатуру с действиями для каждого профиля
                val inlineRows =
                        pageUsers.map { user ->
                                val usernameButtonText =
                                        when {
                                                user.username.isBlank() -> "👤 не указан"
                                                else -> "👤 @${user.username}"
                                        }
                                listOf(
                                        com.github.kotlintelegrambot.entities.keyboard
                                                .InlineKeyboardButton.CallbackData(
                                                text = usernameButtonText,
                                                callbackData = "admin_profile_view_${user.userId}"
                                        ),
                                        com.github.kotlintelegrambot.entities.keyboard
                                                .InlineKeyboardButton.CallbackData(
                                                text =
                                                        if (user.isBanned) "✅ Разбанить"
                                                        else "🚫 Забанить",
                                                callbackData =
                                                        if (user.isBanned)
                                                                "admin_unban_${user.userId}"
                                                        else "admin_ban_${user.userId}"
                                        ),
                                        if (allowRoleActions) {
                                                com.github.kotlintelegrambot.entities.keyboard
                                                        .InlineKeyboardButton.CallbackData(
                                                        text = roleButtonText(user),
                                                        callbackData = roleButtonCallback(user, uid)
                                                )
                                        } else {
                                                null
                                        }
                                )
                                .filterNotNull()
                        }

                // Добавляем навигацию (стрелки) сверху в inline клавиатуру
                val navRows =
                        mutableListOf<
                                List<
                                        com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton>>()

                // Стрелки показываем только если больше 5 пользователей
                if (filteredUsers.size > 5) {
                        // Циклическая навигация: последняя страница -> первая, первая -> последняя
                        val lastPageIndex = ((filteredUsers.size - 1) / 5) * 5
                        val prevIndex = if (index == 0) lastPageIndex else index - 5
                        val nextIndex = if (index + 5 >= filteredUsers.size) 0 else index + 5

                        navRows.add(
                                listOf(
                                        com.github.kotlintelegrambot.entities.keyboard
                                                .InlineKeyboardButton.CallbackData(
                                                text = "⬅️",
                                                callbackData = "admin_users_page_$prevIndex"
                                        ),
                                        com.github.kotlintelegrambot.entities.keyboard
                                                .InlineKeyboardButton.CallbackData(
                                                text = "➡️",
                                                callbackData = "admin_users_page_$nextIndex"
                                        )
                                )
                        )
                }

                val inlineKeyboard =
                        com.github.kotlintelegrambot.entities.InlineKeyboardMarkup.create(
                                navRows + inlineRows
                        )

                bot.screenText(
                        chatId = chat,
                        text = message,
                        parseMode = ParseMode.HTML,
                        replyMarkup = KeyboardAdmin.adminUserListMenu(filter)
                )
                bot.screenText(
                        chatId = chat,
                        text = "",
                        replyMarkup = inlineKeyboard
                )
        }

        fun showBannedUsers(
                bot: Bot,
                chat: ChatId,
                uid: Long,
                users: UserRepository,
                index: Int = 0
        ) {
                val bannedUsers = AdminService.getBannedUsers(50)
                if (bannedUsers.isEmpty()) {
                        bot.screenText(
                                chat,
                                "✅ Забаненных пользователей нет",
                                replyMarkup = KeyboardAdmin.adminBackOnlyMenu()
                        )
                        return
                }

                if (index >= bannedUsers.size) return

                val bannedUserInfo = bannedUsers[index]
                val bannedUser = users.profile(bannedUserInfo.userId) // Загружаем полный профиль
                if (bannedUser == null) {
                        bot.screenText(
                                chat,
                                "❌ Профиль не найден",
                                replyMarkup = KeyboardAdmin.adminMenu()
                        )
                        return
                }

                val session = SessionStore.get(uid)
                session.action = PendingAction.ADMIN_BANNED_LIST
                session.data["admin_current_index"] = index.toString()
                session.data["admin_total_count"] = bannedUsers.size.toString()

                val nameValue =
                        bannedUser
                                .displayName
                                .trim()
                                .removePrefix("@")
                                .ifBlank { "не указано" }
                                .replaceFirstChar { ch ->
                                        if (ch.isLowerCase()) ch.titlecase() else ch.toString()
                                }

                val statusText = run {
                        val banLine =
                                if (bannedUser.banExpiresAt.isNullOrBlank()) {
                                        "Бан: навсегда"
                                } else {
                                        "Бан: осталось ${formatRemainingBanTime(bannedUser.banExpiresAt)}"
                                }
                        """🚫 Забанен
$banLine
📝 Причина: ${bannedUser.reason ?: "не указана"}"""
                }

                val message = buildString {
                        append(
                                "👤 <b>Профиль пользователя</b> (${index + 1} из ${bannedUsers.size})\n\n"
                        )
                        append("🏷️ Имя: $nameValue\n")
                        append("🆔 ID: ${bannedUser.userId}\n")
                        append("👤 Username: <code>@${bannedUser.username}</code>\n")
                        append("⭐ Рейтинг: ${bannedUser.rating}\n")
                        append("👁️ Профиль: ${if (bannedUser.hidden) "скрыт" else "видимый"}\n\n")

                        append("<b>Статус:</b>\n")
                        append("$statusText\n\n")

                        append("📝 <b>Био:</b>\n")
                        append("${bannedUser.bio.ifBlank { "не указано" }}\n")
                }

                bot.screenText(
                        chat,
                        message,
                        parseMode = ParseMode.HTML,
                        replyMarkup = KeyboardAdmin.adminBannedNav(bannedUsers.size)
                )

                bot.screenText(
                        chat,
                        "Действия:",
                        replyMarkup = KeyboardAdmin.adminBannedInline(bannedUser)
                )
        }

        fun showSearchResults(
                bot: Bot,
                chat: ChatId,
                uid: Long,
                users: List<UserProfile>,
                index: Int = 0,
                allowRoleActions: Boolean = true
        ) {
                if (users.isEmpty()) return

                val session = SessionStore.get(uid)
                val query = session.data["admin_search_query"] ?: ""
                val filter = session.data["admin_search_filter"] ?: "Все"

                // Применяем фильтр к результатам поиска
                val filteredUsers =
                        when (filter) {
                                "Все" -> users
                                "Разбаненные" -> users.filter { !it.isBanned }
                                "Забаненные" -> users.filter { it.isBanned }
                                else -> users
                        }

                if (filteredUsers.isEmpty()) {
                        bot.screenText(
                                chat,
                                "❌ По вашему поиску и фильтру не найдено пользователей",
                                replyMarkup = KeyboardAdmin.adminSearchMenu(filter)
                        )
                        return
                }

                val startIndex = index + 1
                val endIndex = minOf(index + 5, filteredUsers.size)
                val pageUsers = filteredUsers.subList(startIndex - 1, endIndex)

                session.action = PendingAction.ADMIN_SEARCH_RESULTS
                session.data["admin_search_results"] =
                        filteredUsers.joinToString("|") { "${it.userId}" }
                session.data["admin_current_index"] = index.toString()
                session.data["admin_total_count"] = filteredUsers.size.toString()

                val message =
                        """🔍 <b>Поиск по</b> "$query" ($startIndex-$endIndex из ${filteredUsers.size})

Выберите пользователя:"""

                val inlineRows =
                        pageUsers.map { user ->
                                val usernameButtonText =
                                        when {
                                                user.username.isBlank() -> "👤 не указан"
                                                else -> "👤 @${user.username}"
                                        }
                                listOf(
                                        com.github.kotlintelegrambot.entities.keyboard
                                                .InlineKeyboardButton.CallbackData(
                                                text = usernameButtonText,
                                                callbackData = "admin_profile_view_${user.userId}"
                                        ),
                                        com.github.kotlintelegrambot.entities.keyboard
                                                .InlineKeyboardButton.CallbackData(
                                                text =
                                                        if (user.isBanned) "✅ Разбанить"
                                                        else "🚫 Забанить",
                                                callbackData =
                                                        if (user.isBanned)
                                                                "admin_unban_${user.userId}"
                                                        else "admin_ban_${user.userId}"
                                        ),
                                        if (allowRoleActions) {
                                                com.github.kotlintelegrambot.entities.keyboard
                                                        .InlineKeyboardButton.CallbackData(
                                                        text = roleButtonText(user),
                                                        callbackData = roleButtonCallback(user, uid)
                                                )
                                        } else {
                                                null
                                        }
                                )
                                .filterNotNull()
                        }

                val navRows =
                        mutableListOf<
                                List<
                                        com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton>>()

                // Стрелки показываем только если больше 5 пользователей
                if (filteredUsers.size > 5) {
                        // Циклическая навигация: последняя страница -> первая, первая -> последняя
                        val lastPageIndex = ((filteredUsers.size - 1) / 5) * 5
                        val prevIndex = if (index == 0) lastPageIndex else index - 5
                        val nextIndex = if (index + 5 >= filteredUsers.size) 0 else index + 5

                        navRows.add(
                                listOf(
                                        com.github.kotlintelegrambot.entities.keyboard
                                                .InlineKeyboardButton.CallbackData(
                                                text = "⬅️",
                                                callbackData = "admin_search_page_$prevIndex"
                                        ),
                                        com.github.kotlintelegrambot.entities.keyboard
                                                .InlineKeyboardButton.CallbackData(
                                                text = "➡️",
                                                callbackData = "admin_search_page_$nextIndex"
                                        )
                                )
                        )
                }

                val inlineKeyboard =
                        com.github.kotlintelegrambot.entities.InlineKeyboardMarkup.create(
                                navRows + inlineRows
                        )

                bot.screenText(
                        chatId = chat,
                        text = message,
                        parseMode = ParseMode.HTML,
                        replyMarkup = KeyboardAdmin.adminSearchMenu(filter)
                )
                bot.screenText(chatId = chat, text = "", replyMarkup = inlineKeyboard)
        }

        fun showUserProfileView(
                bot: Bot,
                chat: ChatId,
                uid: Long,
                targetUser: UserProfile,
                users: UserRepository
        ) {
                ScreenRenderer.refreshOnRestore {
                        users.profile(targetUser.userId)?.let {
                                showUserProfileView(bot, chat, uid, it, users)
                        }
                }
                // Если это свой профиль, то показываем как обычный профиль с полным доступом
                if (uid == targetUser.userId) {
                        RouterProfile.showProfile(bot, chat, uid, users)
                        return
                }

                val session = SessionStore.get(uid)
                // Сохраняем текущий action чтобы вернуться в нужный список
                session.data["admin_profile_view_prev_action"] = session.action.name
                session.context = FSMContext.PROFILE_VIEW
                session.data["admin_profile_view_user_id"] = targetUser.userId.toString()

                val currentAdmin = AdminService.isAdmin(uid)
                val canModerate = currentAdmin || AdminService.isModerator(uid)

                // Username видна только если не скрыта, или для админа
                val usernameDisplay =
                        if (currentAdmin) {
                                "@${targetUser.username}"
                        } else {
                                if (targetUser.hideUsername || targetUser.username.isBlank()) {
                                        "<code>скрыт</code>"
                                } else {
                                        "@${targetUser.username}"
                                }
                        }

                val nameValue =
                        targetUser
                                .displayName
                                .trim()
                                .removePrefix("@")
                                .ifBlank { "не указано" }
                                .replaceFirstChar { ch ->
                                        if (ch.isLowerCase()) ch.titlecase() else ch.toString()
                                }

                val banRemainingText =
                        if (targetUser.isBanned && !targetUser.banExpiresAt.isNullOrBlank()) {
                                formatRemainingBanTime(targetUser.banExpiresAt)
                        } else {
                                null
                        }

                val statusText =
                        if (targetUser.isBanned) {
                                val banLine =
                                        if (targetUser.banExpiresAt.isNullOrBlank()) {
                                                "Время бана: навсегда"
                                        } else {
                                                "Время бана: осталось $banRemainingText"
                                        }
                                "🚫 Забанен\n$banLine\n📝 Причина: ${targetUser.reason ?: "не указана"}"
                        } else {
                                "✅ Активен"
                        }

                val message = buildString {
                        append("👤 <b>Профиль пользователя</b>\n\n")
                        append("🏷️ Имя: $nameValue\n")
                        append("👑 Тип аккаунта: ${roleLabel(targetUser.accountType)}\n")

                        if (currentAdmin) {
                                append("🆔 ID: ${targetUser.userId}\n")
                        }

                        append(
                                "👤 Username: <code>${usernameDisplay.replace("<code>", "").replace("</code>", "")}</code>\n"
                        )
                        append("⭐ Рейтинг: ${targetUser.rating}\n")
                        append("👁️ Профиль: ${if (targetUser.hidden) "скрыт" else "видимый"}\n\n")

                        append("<b>Статус:</b>\n")
                        append("$statusText\n\n")

                        append("📝 <b>Био:</b>\n")
                        append("${targetUser.bio.ifBlank { "не указано" }}\n")
                }

                val keyboard =
                        if (currentAdmin) {
                                val buttons =
                                        mutableListOf<
                                                List<
                                                        com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton>>()

                                buttons.add(
                                        listOf(
                                                com.github.kotlintelegrambot.entities.keyboard
                                                        .InlineKeyboardButton.CallbackData(
                                                        text =
                                                                if (targetUser.accountType ==
                                                                                AccountType
                                                                                        .MODERATOR
                                                                ) {
                                                                        "👑 Убрать модератора"
                                                                } else {
                                                                        "👑 Сделать модератором"
                                                                },
                                                        callbackData =
                                                                "admin_role_toggle_${targetUser.userId}"
                                                )
                                        )
                                )

                                buttons.add(
                                        listOf(
                                                com.github.kotlintelegrambot.entities.keyboard
                                                        .InlineKeyboardButton.CallbackData(
                                                        text =
                                                                if (targetUser.isBanned)
                                                                        "✅ Разбанить"
                                                                else "🚫 Забанить",
                                                        callbackData =
                                                                if (targetUser.isBanned)
                                                                        "admin_unban_${targetUser.userId}"
                                                                else
                                                                        "admin_ban_${targetUser.userId}"
                                                )
                                        )
                                )

                                if (canEnterAccount(uid, targetUser)) {
                                        buttons.add(
                                                listOf(
                                                        com.github.kotlintelegrambot.entities
                                                                .keyboard.InlineKeyboardButton
                                                                .CallbackData(
                                                                        text = "🔑 Войти в аккаунт",
                                                                        callbackData =
                                                                                "admin_impersonate_${targetUser.userId}"
                                                                )
                                                )
                                        )
                                }

                                if (targetUser.isBanned) {
                                        buttons.add(
                                                listOf(
                                                        com.github.kotlintelegrambot.entities
                                                                .keyboard.InlineKeyboardButton
                                                                .CallbackData(
                                                                        text =
                                                                                "📝 Написать причину бана",
                                                                        callbackData =
                                                                                "admin_ban_reason_${targetUser.userId}"
                                                                )
                                                )
                                        )
                                }

                                buttons.add(
                                        listOf(
                                                com.github.kotlintelegrambot.entities.keyboard
                                                        .InlineKeyboardButton.CallbackData(
                                                        text = "⬅️ Обратно",
                                                        callbackData = "admin_back_to_user_list"
                                                )
                                        )
                                )

                                com.github.kotlintelegrambot.entities.InlineKeyboardMarkup.create(
                                        buttons
                                )
                        } else if (canModerate) {
                                KeyboardProfile.moderatorUserViewMenu(targetUser.isBanned)
                        } else {
                                KeyboardAdmin.profileViewBackMenu()
                        }

                // Если есть аватарка, отправляем фото с подписью
                targetUser.avatarFileId?.let { fileId ->
                        try {
                                val file = File(fileId)
                                val photoFile: File? =
                                        when {
                                                file.exists() -> file
                                                else -> {
                                                        val stream =
                                                                RouterAdminUsers::class.java
                                                                        .classLoader
                                                                        .getResourceAsStream(fileId)
                                                        if (stream != null) {
                                                                val tmp =
                                                                        File.createTempFile(
                                                                                "mock_avatar_",
                                                                                ".jpg"
                                                                        )
                                                                tmp.deleteOnExit()
                                                                stream.use { input ->
                                                                        tmp.outputStream().use { out
                                                                                ->
                                                                                input.copyTo(out)
                                                                        }
                                                                }
                                                                tmp
                                                        } else {
                                                                null
                                                        }
                                                }
                                        }

                                if (photoFile != null && photoFile.exists()) {
                                        bot.screenPhoto(
                                                chatId = chat,
                                                photo = photoFile,
                                                caption = message,
                                                parseMode = ParseMode.HTML,
                                                replyMarkup = keyboard
                                        )
                                        return
                                } else {
                                        // fallback: возможно это Telegram file_id
                                        bot.screenPhoto(
                                                chatId = chat,
                                                photo = fileId,
                                                caption = message,
                                                parseMode = ParseMode.HTML,
                                                replyMarkup = keyboard
                                        )
                                        return
                                }
                        } catch (e: Exception) {
                                println("❌ Ошибка при отправке аватарки: ${e.message}")
                        }
                }

                // Если нет аватарки или произошла ошибка, отправляем только текст
                bot.screenText(
                        chatId = chat,
                        text = message,
                        parseMode = ParseMode.HTML,
                        replyMarkup = keyboard
                )
        }

        fun showUserProfileView(bot: Bot, chat: ChatId, targetUser: UserProfile) {
                val usernameDisplay =
                        if (targetUser.hideUsername || targetUser.username.isBlank()) {
                                "скрыт"
                        } else {
                                "@${targetUser.username}"
                        }

                val nameValue =
                        targetUser
                                .displayName
                                .trim()
                                .removePrefix("@")
                                .ifBlank { "не указано" }
                                .replaceFirstChar { ch ->
                                        if (ch.isLowerCase()) ch.titlecase() else ch.toString()
                                }

                val statusText =
                        if (targetUser.isBanned) {
                                """🚫 Забанен
📅 Дата бана: ${targetUser.bannedAt ?: "неизвестно"}
📝 Причина: ${targetUser.reason ?: "не указана"}"""
                        } else {
                                "✅ Активен"
                        }

                val message = buildString {
                        append("👤 <b>Профиль пользователя</b>\n\n")
                        append("🏷️ Имя: $nameValue\n")
                        append("👤 Username: <code>$usernameDisplay</code>\n")
                        append("⭐ Рейтинг: ${targetUser.rating}\n")
                        append("👁️ Профиль: ${if (targetUser.hidden) "скрыт" else "видимый"}\n\n")

                        append("<b>Статус:</b>\n")
                        append("$statusText\n\n")

                        append("📝 <b>Био:</b>\n")
                        append("${targetUser.bio.ifBlank { "не указано" }}\n")
                }

                val inlineButtons =
                        listOf(
                                listOf(
                                        com.github.kotlintelegrambot.entities.keyboard
                                                .InlineKeyboardButton.CallbackData(
                                                text = "⬅️ Обратно",
                                                callbackData = "admin_back_to_user_list"
                                        )
                                )
                        )

                val keyboard =
                        com.github.kotlintelegrambot.entities.InlineKeyboardMarkup.create(
                                inlineButtons
                        )

                // Если есть аватарка, отправляем фото с подписью
                targetUser.avatarFileId?.let { fileId ->
                        try {
                                val file = File(fileId)
                                val photoFile: File? =
                                        when {
                                                file.exists() -> file
                                                else -> {
                                                        val stream =
                                                                RouterAdminUsers::class.java
                                                                        .classLoader
                                                                        .getResourceAsStream(fileId)
                                                        if (stream != null) {
                                                                val tmp =
                                                                        File.createTempFile(
                                                                                "mock_avatar_",
                                                                                ".jpg"
                                                                        )
                                                                tmp.deleteOnExit()
                                                                stream.use { input ->
                                                                        tmp.outputStream().use { out
                                                                                ->
                                                                                input.copyTo(out)
                                                                        }
                                                                }
                                                                tmp
                                                        } else {
                                                                null
                                                        }
                                                }
                                        }

                                if (photoFile != null && photoFile.exists()) {
                                        bot.screenPhoto(
                                                chatId = chat,
                                                photo = photoFile,
                                                caption = message,
                                                parseMode = ParseMode.HTML,
                                                replyMarkup = keyboard
                                        )
                                        return
                                } else {
                                        bot.screenPhoto(
                                                chatId = chat,
                                                photo = fileId,
                                                caption = message,
                                                parseMode = ParseMode.HTML,
                                                replyMarkup = keyboard
                                        )
                                        return
                                }
                        } catch (e: Exception) {
                                println("❌ Ошибка при отправке аватарки: ${e.message}")
                        }
                }

                // Если нет аватарки или произошла ошибка, отправляем только текст
                bot.screenText(
                        chatId = chat,
                        text = message,
                        parseMode = ParseMode.HTML,
                        replyMarkup = keyboard
                )
        }

        fun showUserProfile(bot: Bot, chat: ChatId, targetUser: UserProfile) {
                val usernameDisplay =
                        if (targetUser.hideUsername || targetUser.username.isBlank()) {
                                "скрыт"
                        } else {
                                "@${targetUser.username}"
                        }

                val nameValue =
                        targetUser
                                .displayName
                                .trim()
                                .removePrefix("@")
                                .ifBlank { "не указано" }
                                .replaceFirstChar { ch ->
                                        if (ch.isLowerCase()) ch.titlecase() else ch.toString()
                                }

                val statusText =
                        if (targetUser.isBanned) {
                                """🚫 Забанен
📅 Дата бана: ${targetUser.bannedAt ?: "неизвестно"}
📝 Причина: ${targetUser.reason ?: "не указана"}"""
                        } else {
                                "✅ Активен"
                        }

                val message = buildString {
                        append("👤 <b>Профиль пользователя</b>\n\n")
                        append("🏷️ Имя: $nameValue\n")
                        append("👤 Username: <code>$usernameDisplay</code>\n")
                        append("⭐ Рейтинг: ${targetUser.rating}\n")
                        append("👁️ Профиль: ${if (targetUser.hidden) "скрыт" else "видимый"}\n\n")

                        append("<b>Статус:</b>\n")
                        append("$statusText\n\n")

                        append("📝 <b>Био:</b>\n")
                        append("${targetUser.bio.ifBlank { "не указано" }}\n")
                }

                // Создаем inline клавиатуру с действиями
                val actionRows =
                        mutableListOf<
                                List<
                                        com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton>>()

                // Кнопка редактирования профиля
                actionRows.add(
                        listOf(
                                com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
                                        .CallbackData(
                                                text = "✏️ Редактировать",
                                                callbackData =
                                                        "admin_edit_profile_${targetUser.userId}"
                                        )
                        )
                )

                // Кнопка бана/разбана
                actionRows.add(
                        listOf(
                                com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
                                        .CallbackData(
                                                text =
                                                        if (targetUser.isBanned) "✅ Разбанить"
                                                        else "🚫 Забанить",
                                                callbackData =
                                                        if (targetUser.isBanned)
                                                                "admin_unban_${targetUser.userId}"
                                                        else "admin_ban_${targetUser.userId}"
                                        )
                        )
                )

                // Кнопка написания причины бана (только если забанен)
                if (targetUser.isBanned) {
                        actionRows.add(
                                listOf(
                                        com.github.kotlintelegrambot.entities.keyboard
                                                .InlineKeyboardButton.CallbackData(
                                                text = "📝 Написать причину бана",
                                                callbackData =
                                                        "admin_ban_reason_${targetUser.userId}"
                                        )
                                )
                        )
                }

                // Кнопка возврата
                actionRows.add(
                        listOf(
                                com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
                                        .CallbackData(
                                                text = "⬅️ Обратно",
                                                callbackData = "admin_back_to_search"
                                        )
                        )
                )

                val inlineKeyboard =
                        com.github.kotlintelegrambot.entities.InlineKeyboardMarkup.create(
                                actionRows
                        )
                bot.screenText(
                        chat,
                        message,
                        parseMode = ParseMode.HTML,
                        replyMarkup = inlineKeyboard
                )
        }
}
