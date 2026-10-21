package core.routers

import core.ui.*
import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.ParseMode
import core.PendingAction
import core.Session
import core.SessionStore
import core.keyboards.KeyboardFactory
import core.keyboards.KeyboardProfile
import core.routers.routerAdmin.RouterAdmin
import data.*
import data.models.*
import data.repositories.UserRepository
import data.services.AdminService
import data.services.ModerationService
import java.io.File
import java.time.Duration
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object RouterProfile {
    private const val IMPERSONATED_USER_ID = "profile_impersonated_user_id"
    private val dbDateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSS]")

    private fun currentProfileUserId(uid: Long, session: Session): Long {
        return session.data[IMPERSONATED_USER_ID]?.toLongOrNull() ?: uid
    }

    private fun isImpersonating(uid: Long, session: Session): Boolean {
        return session.data[IMPERSONATED_USER_ID]?.toLongOrNull() != null && AdminService.isBootstrapAdmin(uid)
    }

    private fun clearImpersonation(session: Session) {
        session.data.remove(IMPERSONATED_USER_ID)
    }

    private fun parseBanExpiry(value: String): OffsetDateTime {
        return try {
            OffsetDateTime.parse(value)
        } catch (_: Exception) {
            try {
                LocalDateTime.parse(value).atOffset(ZoneOffset.UTC)
            } catch (_: Exception) {
                try {
                    LocalDateTime.parse(value.replace(' ', 'T')).atOffset(ZoneOffset.UTC)
                } catch (_: Exception) {
                    LocalDateTime.parse(value, dbDateTimeFormatter).atOffset(ZoneOffset.UTC)
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

    fun showProfile(bot: Bot, chat: ChatId, uid: Long, users: UserRepository) {
        val session = SessionStore.get(uid)
        session.data["last_menu"] = "profile"
        val viewingUid = currentProfileUserId(uid, session)
        val profile = users.profile(viewingUid)

        if (profile == null) {
            println("ERROR: Профиль пользователя $uid не найден")
            bot.screenText(chat, "❌ Профиль не найден")
            return
        }

        val accountSwitchLabel = if (isImpersonating(uid, session)) "Вернуться в свой профиль" else null

        val statusText =
                if (profile.isBanned) {
                    val banLine =
                            if (profile.banExpiresAt.isNullOrBlank()) {
                                "Время бана: навсегда"
                            } else {
                                val remainingText = formatRemainingBanTime(profile.banExpiresAt)
                                "Время бана: осталось $remainingText"
                            }
                    "🚫 Забанен\n$banLine\n📝 Причина: ${profile.reason ?: "не указана"}"
                } else {
                    "✅ Активен"
                }

        val messageText = buildString {
            append("👤 <b>Профиль пользователя</b>\n\n")

            val nameValue =
                    profile.displayName
                            .trim()
                            .removePrefix("@")
                            .ifBlank { "не указано" }
                            .replaceFirstChar { ch ->
                                if (ch.isLowerCase()) ch.titlecase() else ch.toString()
                            }
            append("🏷️ Имя: $nameValue\n")

            if (profile.hideUsername || profile.username.isBlank()) {
                append("👤 Username: <code>скрыт</code>\n")
            } else {
                append("👤 Username: <code>@${profile.username}</code>\n")
            }

            append("⭐ Рейтинг: ${profile.rating}\n")
            append("👁️ Профиль: ${if (profile.hidden) "скрыт" else "видимый"}\n\n")

            append("📝 <b>Био:</b>\n")
            append("${profile.bio.ifBlank { "не указано" }}\n\n")

            append("<b>Статус:</b>\n")
            append("$statusText\n")
        }

        profile.avatarFileId?.let { fileId ->
            try {
                val file = File(fileId)
                val photoFile: File? =
                        when {
                            file.exists() -> file
                            else -> {
                                val stream =
                                        RouterProfile::class.java.classLoader.getResourceAsStream(
                                                fileId
                                        )
                                if (stream != null) {
                                    val tmp = File.createTempFile("mock_avatar_", ".jpg")
                                    tmp.deleteOnExit()
                                    stream.use { input ->
                                        tmp.outputStream().use { out -> input.copyTo(out) }
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
                            caption = messageText,
                            parseMode = ParseMode.HTML,
                            replyMarkup = KeyboardFactory.profileMenu(profile, accountSwitchLabel)
                    )
                } else {
                    bot.screenPhoto(
                            chatId = chat,
                            photo = fileId,
                            caption = messageText,
                            parseMode = ParseMode.HTML,
                            replyMarkup = KeyboardFactory.profileMenu(profile, accountSwitchLabel)
                    )
                }
            } catch (e: Exception) {
                println("❌ Ошибка при отправке аватарки: ${e.message}")
                bot.screenText(
                        chat,
                        messageText,
                        parseMode = ParseMode.HTML,
                        replyMarkup = KeyboardFactory.profileMenu(profile, accountSwitchLabel)
                )
            }
        }
                ?: run {
                    bot.screenText(
                            chat,
                            messageText,
                            parseMode = ParseMode.HTML,
                            replyMarkup = KeyboardFactory.profileMenu(profile, accountSwitchLabel)
                    )
                }
    }

    private fun showEditProfileForm(bot: Bot, chat: ChatId, uid: Long, users: UserRepository) {
        val session = SessionStore.get(uid)
        val profile = users.profile(currentProfileUserId(uid, session))
        if (profile == null) {
            bot.screenText(chat, "❌ Профиль пользователя не найден")
            return
        }

        val message = buildString {
            append("✏️ <b>Редактирование профиля</b>\n\n")
            append(
                    "🏷️ <i>ИМЯ:</i> <b>${profile.displayName.removePrefix("@").ifBlank { "не указано" }.replaceFirstChar { ch -> if (ch.isLowerCase()) ch.titlecase() else ch.toString() }}</b>\n"
            )
            append(
                    "🔸 <i>Username:</i> <code>${if (profile.username.isBlank()) "не указан" else "@${profile.username}"}</code>\n"
            )
            append("📝 <i>Био:</i> <em>${profile.bio.ifBlank { "не указано" }}</em>\n\n")
            append("👁️ <i>Профиль:</i> ${if (profile.hidden) "скрыт" else "видим"}\n")
            append("👤 <i>Username:</i> ${if (profile.hideUsername) "скрыт" else "показывается"}\n")
        }

        bot.screenText(
                chat,
                message,
                parseMode = ParseMode.HTML,
                replyMarkup = KeyboardProfile.editProfileMenu(profile)
        )
    }

    fun showAdminEditProfileForm(bot: Bot, chat: ChatId, adminUid: Long, targetUser: UserProfile) {
        val session = SessionStore.get(adminUid)
        session.action = PendingAction.ADMIN_EDIT_PROFILE
        session.data["admin_edit_target_user"] = targetUser.userId.toString()

        val usernameDisplay = if (targetUser.hideUsername) "скрыт" else "@${targetUser.username}"

        val nameValue =
                targetUser
                        .displayName
                        .trim()
                        .removePrefix("@")
                        .ifBlank { "не указано" }
                        .replaceFirstChar { ch ->
                            if (ch.isLowerCase()) ch.titlecase() else ch.toString()
                        }

        val message =
                """✏️ <b>Редактирование профиля</b>

🏷️ ИМЯ: $nameValue
🆔 ID: ${targetUser.userId}
👤 Username: $usernameDisplay

Выберите, что хотите изменить:"""

        val actionRows =
                mutableListOf<
                        List<com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton>>()

        actionRows.add(
                listOf(
                        com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
                                .CallbackData(
                                        text = "📝 Имя",
                                        callbackData = "admin_edit_name_${targetUser.userId}"
                                ),
                        com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
                                .CallbackData(
                                        text = "👤 Username",
                                        callbackData = "admin_edit_username_${targetUser.userId}"
                                )
                )
        )

        actionRows.add(
                listOf(
                        com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
                                .CallbackData(
                                        text = "📄 Био",
                                        callbackData = "admin_edit_bio_${targetUser.userId}"
                                ),
                        com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
                                .CallbackData(
                                        text = "👁️ Скрыть профиль",
                                        callbackData = "admin_toggle_hidden_${targetUser.userId}"
                                )
                )
        )

        actionRows.add(
                listOf(
                        com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
                                .CallbackData(
                                        text = "🎬 Медиа",
                                        callbackData = "admin_toggle_media_${targetUser.userId}"
                                ),
                        com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
                                .CallbackData(
                                        text = "👁️ Username",
                                        callbackData = "admin_toggle_username_${targetUser.userId}"
                                )
                )
        )

        val inlineKeyboard =
                com.github.kotlintelegrambot.entities.InlineKeyboardMarkup.create(actionRows)
        bot.screenText(chat, message, parseMode = ParseMode.HTML, replyMarkup = inlineKeyboard)
    }

    fun showAccountStats(bot: Bot, chat: ChatId, targetUser: UserProfile) {
        val message =
                """📊 <b>Дополнительная информация</b>

😂 <b>Статистика мемов:</b>
• Загружено мемов: 0

🔮 <b>Статистика предсказаний:</b>
• Всего выбитых предсказаний: 0
  • Редкие: 0
  • Эпические: 0
  • Легендарные: 0
• Добавлено предсказаний: 0

📝 <b>Статистика тестов:</b>
• Создано тестов: 0
• Пройдено тестов: 0
• Средний процент прохождения: 0%

🎮 <b>Статистика мини-игр:</b>
• Общий рейтинг: ${targetUser.rating}
• Общее количество побед: 0
• Общее количество поражений: 0

⚽ <b>Футбол:</b>
  • Рейтинг: 0
  • Побед: 0
  • Поражений: 0
  • Текущая серия: 0
  • Лучшая серия: 0

🏀 <b>Баскетбол:</b>
  • Рейтинг: 0
  • Побед: 0
  • Поражений: 0
  • Текущая серия: 0
  • Лучшая серия: 0

🎡 <b>Колесо фортуны:</b>
  • Рейтинг: 0
  • Побед: 0
  • Поражений: 0
  • Текущая серия: 0
  • Лучшая серия: 0

✂️ <b>Камень-ножницы-бумага:</b>
  • Рейтинг: 0
  • Побед: 0
  • Поражений: 0
  • Ничьей: 0
  • Текущая серия: 0
  • Лучшая серия: 0"""

        bot.screenText(
                chat,
                message,
                parseMode = ParseMode.HTML,
                replyMarkup = KeyboardProfile.statsMenu()
        )
    }

    fun handleProfileAction(
            bot: Bot,
            chat: ChatId,
            uid: Long,
            text: String,
            users: UserRepository,
            session: Session
    ): Boolean {
        when (text) {
            "✏️ Изменить профиль" -> {
                session.action = PendingAction.EDIT_PROFILE
                showEditProfileForm(bot, chat, uid, users)
                return true
            }
            "📊 Статистика аккаунта" -> {
                session.action = PendingAction.EDIT_PROFILE
                val profile = users.profile(currentProfileUserId(uid, session))
                if (profile != null) {
                    showAccountStats(bot, chat, profile)
                } else {
                    bot.screenText(chat, "❌ Профиль пользователя не найден")
                }
                return true
            }
            "⬅️ Обратно" -> {
                // Возврат в профиль из статистики или настроек
                session.action = PendingAction.NONE
                showProfile(bot, chat, uid, users)
                return true
            }
            "🛡️ Админ панель" -> {
                session.data["return_to_profile_after_admin"] = "true"
                session.action = PendingAction.ADMIN_USER_MANAGEMENT
                RouterAdmin.handleAdminAction(bot, chat, uid, text, users, session)
                return true
            }
            "🛡️ Панель модератора" -> {
                session.data["return_to_profile_after_admin"] = "true"
                session.action = PendingAction.ADMIN_USER_MANAGEMENT
                RouterAdmin.handleModeratorAction(bot, chat, uid, text, users, session)
                return true
            }
            "Вернуться в свой профиль" -> {
                if (isImpersonating(uid, session)) {
                    clearImpersonation(session)
                    session.action = PendingAction.NONE
                    showProfile(bot, chat, uid, users)
                } else {
                    bot.screenText(chat, "❌ Недоступно")
                }
                return true
            }
            "Изменить имя" -> {
                val profileUid = currentProfileUserId(uid, session)
                session.action = PendingAction.EDIT_NAME
                bot.screenText(
                        chat,
                        "Напиши новое имя.",
                        replyMarkup = KeyboardProfile.editProfileMenu(users.profile(profileUid))
                )
                return true
            }
            "Показать username [👁️]", "Скрыть username [🙈]" -> {
                users.toggleHideUsername(currentProfileUserId(uid, session))
                showEditProfileForm(bot, chat, uid, users)
                return true
            }
            "Изменить био" -> {
                val profileUid = currentProfileUserId(uid, session)
                session.action = PendingAction.EDIT_BIO
                bot.screenText(
                        chat,
                        "Напиши новое описание профиля.",
                        replyMarkup = KeyboardProfile.editProfileMenu(users.profile(profileUid))
                )
                return true
            }
            "Показать профиль [👁️]", "Скрыть профиль [🙈]" -> {
                users.toggleHidden(currentProfileUserId(uid, session))
                showEditProfileForm(bot, chat, uid, users)
                return true
            }
            "🖼️ Добавить аватарку", "🖼️ Изменить аватарку" -> {
                session.action = PendingAction.EDIT_AVATAR
                bot.screenText(
                        chat,
                        "Отправь фотографию для аватарки профиля.",
                        replyMarkup = KeyboardProfile.editProfileMenu(users.profile(currentProfileUserId(uid, session)))
                )
                return true
            }
            "⬅️ Назад", "⬅️ Обратно" -> {
                session.action = PendingAction.NONE
                showProfile(bot, chat, uid, users)
                return true
            }
            else -> {
                if (session.action == PendingAction.EDIT_NAME) {
                    val profileUid = currentProfileUserId(uid, session)
                    val moderationResult = ModerationService.checkText(profileUid, text)
                    if (!moderationResult.isAllowed) {
                        if (moderationResult.shouldBan) {
                            ModerationService.banForViolation(
                                    profileUid,
                                    "Использование ненормативной лексики"
                            )
                            ModerationService.clearWarnings(profileUid)
                            bot.screenText(
                                    chat,
                                    "🚫 Вы были заблокированы за использование ненормативной лексики."
                            )
                        } else {
                            ModerationService.addWarning(profileUid)
                            bot.screenText(
                                    chat,
                                    "⚠️ <b>Предупреждение!</b>\n\nВаше сообщение содержит недопустимый контент.\n\nПовторное нарушение приведёт к автоматическому бану на 1 сутки.",
                                    parseMode = ParseMode.HTML
                            )
                        }
                        return true
                    }
                    users.updateName(profileUid, text)
                    session.action = PendingAction.NONE
                    showEditProfileForm(bot, chat, uid, users)
                    return true
                }

                if (session.action == PendingAction.EDIT_BIO) {
                    val profileUid = currentProfileUserId(uid, session)
                    val moderationResult = ModerationService.checkText(profileUid, text)
                    if (!moderationResult.isAllowed) {
                        if (moderationResult.shouldBan) {
                            ModerationService.banForViolation(
                                    profileUid,
                                    "Использование ненормативной лексики"
                            )
                            ModerationService.clearWarnings(profileUid)
                            bot.screenText(
                                    chat,
                                    "🚫 Вы были заблокированы за использование ненормативной лексики."
                            )
                        } else {
                            ModerationService.addWarning(profileUid)
                            bot.screenText(
                                    chat,
                                    "⚠️ <b>Предупреждение!</b>\n\nВаше сообщение содержит недопустимый контент.\n\nПовторное нарушение приведёт к автоматическому бану на 1 сутки.",
                                    parseMode = ParseMode.HTML
                            )
                        }
                        return true
                    }
                    users.updateBio(profileUid, text)
                    session.action = PendingAction.NONE
                    showEditProfileForm(bot, chat, uid, users)
                    return true
                }

                // Админское редактирование профиля
                if (session.action == PendingAction.ADMIN_EDIT_NAME) {
                    val targetUserId = session.data["admin_edit_target_user"]?.toLongOrNull()
                    if (targetUserId != null) {
                        val targetUser = users.profile(targetUserId)
                        if (targetUser != null) {
                            val moderationResult = ModerationService.checkText(targetUserId, text)
                            if (!moderationResult.isAllowed) {
                                if (moderationResult.shouldBan) {
                                    ModerationService.banForViolation(
                                            targetUserId,
                                            "Использование ненормативной лексики"
                                    )
                                    ModerationService.clearWarnings(targetUserId)
                                    bot.screenText(
                                            chat,
                                            "🚫 Пользователь заблокирован за использование ненормативной лексики."
                                    )
                                } else {
                                    ModerationService.addWarning(targetUserId)
                                    bot.screenText(
                                            chat,
                                            "⚠️ <b>Предупреждение пользователю!</b>\n\nЕго сообщение содержит недопустимый контент.\n\nПовторное нарушение приведёт к автоматическому бану на 1 сутки.",
                                            parseMode = ParseMode.HTML
                                    )
                                }
                                return true
                            }
                            users.updateName(targetUserId, text)
                            session.action = PendingAction.NONE
                            bot.screenText(chat, "✅ Имя пользователя изменено на: \"$text\"")
                            showAdminEditProfileForm(
                                    bot,
                                    chat,
                                    uid,
                                    targetUser.copy(displayName = text)
                            )
                        } else {
                            bot.screenText(chat, "❌ Пользователь не найден")
                        }
                    }
                    return true
                }

                if (session.action == PendingAction.ADMIN_EDIT_USERNAME) {
                    val targetUserId = session.data["admin_edit_target_user"]?.toLongOrNull()
                    if (targetUserId != null) {
                        val targetUser = users.profile(targetUserId)
                        if (targetUser != null) {
                            val moderationResult = ModerationService.checkText(targetUserId, text)
                            if (!moderationResult.isAllowed) {
                                if (moderationResult.shouldBan) {
                                    ModerationService.banForViolation(
                                            targetUserId,
                                            "Использование ненормативной лексики"
                                    )
                                    ModerationService.clearWarnings(targetUserId)
                                    bot.screenText(
                                            chat,
                                            "🚫 Пользователь заблокирован за использование ненормативной лексики."
                                    )
                                } else {
                                    ModerationService.addWarning(targetUserId)
                                    bot.screenText(
                                            chat,
                                            "⚠️ <b>Предупреждение пользователю!</b>\n\nЕго сообщение содержит недопустимый контент.\n\nПовторное нарушение приведёт к автоматическому бану на 1 сутки.",
                                            parseMode = ParseMode.HTML
                                    )
                                }
                                return true
                            }
                            val newUsername = text.trim().removePrefix("@")
                            users.updateUsername(targetUserId, newUsername)
                            session.action = PendingAction.NONE
                            bot.screenText(chat, "✅ Username изменен на: \"@$newUsername\"")
                            showAdminEditProfileForm(
                                    bot,
                                    chat,
                                    uid,
                                    targetUser.copy(username = newUsername)
                            )
                        } else {
                            bot.screenText(chat, "❌ Пользователь не найден")
                        }
                    }
                    return true
                }

                if (session.action == PendingAction.ADMIN_EDIT_BIO) {
                    val targetUserId = session.data["admin_edit_target_user"]?.toLongOrNull()
                    if (targetUserId != null) {
                        val targetUser = users.profile(targetUserId)
                        if (targetUser != null) {
                            val moderationResult = ModerationService.checkText(targetUserId, text)
                            if (!moderationResult.isAllowed) {
                                if (moderationResult.shouldBan) {
                                    ModerationService.banForViolation(
                                            targetUserId,
                                            "Использование ненормативной лексики"
                                    )
                                    ModerationService.clearWarnings(targetUserId)
                                    bot.screenText(
                                            chat,
                                            "🚫 Пользователь заблокирован за использование ненормативной лексики."
                                    )
                                } else {
                                    ModerationService.addWarning(targetUserId)
                                    bot.screenText(
                                            chat,
                                            "⚠️ <b>Предупреждение пользователю!</b>\n\nЕго сообщение содержит недопустимый контент.\n\nПовторное нарушение приведёт к автоматическому бану на 1 сутки.",
                                            parseMode = ParseMode.HTML
                                    )
                                }
                                return true
                            }
                            users.updateBio(targetUserId, text)
                            session.action = PendingAction.NONE
                            bot.screenText(chat, "✅ Био изменено")
                            showAdminEditProfileForm(bot, chat, uid, targetUser.copy(bio = text))
                        } else {
                            bot.screenText(chat, "❌ Пользователь не найден")
                        }
                    }
                    return true
                }

                return false
            }
        }
    }
}
