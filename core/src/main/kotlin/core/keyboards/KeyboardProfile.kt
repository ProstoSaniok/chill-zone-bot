package core.keyboards

import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import data.models.UserProfile
import data.services.AdminService

object KeyboardProfile {
        fun profileMenu(
                        userProfile: UserProfile? = null,
                        accountSwitchLabel: String? = null
        ): InlineKeyboardMarkup {
                val accountType = userProfile?.accountType
                val panelLabel = when (accountType) {
                        data.models.AccountType.ADMIN -> "🛡️ Админ панель"
                        data.models.AccountType.MODERATOR -> "🛡️ Панель модератора"
                        else -> null
                }

        val rows = mutableListOf<List<String>>()

        rows.addAll(listOf(listOf("✏️ Изменить профиль"), listOf("📊 Статистика аккаунта")))

        if (panelLabel != null) {
            rows.add(listOf(panelLabel))
        }

                if (accountSwitchLabel != null) {
                        rows.add(listOf(accountSwitchLabel))
                }

        rows.add(listOf("⬅️ Обратно"))

        return kb(rows)
    }

    fun editProfileMenu(userProfile: UserProfile? = null): InlineKeyboardMarkup {
        val profileHidden = userProfile?.hidden ?: false
        val usernameHidden = userProfile?.hideUsername ?: false
        val hasAvatar = userProfile?.avatarFileId != null

        return kb(
                listOf(
                        listOf("Изменить имя", "Изменить био"),
                        listOf(if (hasAvatar) "🖼️ Изменить аватарку" else "🖼️ Добавить аватарку"),
                        listOf(
                                if (usernameHidden) "Показать username [👁️]"
                                else "Скрыть username [🙈]",
                                if (profileHidden) "Показать профиль [👁️]"
                                else "Скрыть профиль [🙈]"
                        ),
                        listOf("⬅️ Обратно")
                )
        )
    }

        fun moderatorUserViewMenu(isBanned: Boolean): InlineKeyboardMarkup {
                return kb(
                                listOf(
                                                listOf(if (isBanned) "✅ Разбанить" else "🚫 Забанить"),
                                                listOf("⬅️ Обратно")
                                )
                )
        }

    fun settingsMenu(userProfile: UserProfile? = null): InlineKeyboardMarkup {
        val mediaEnabled = userProfile?.showMedia ?: true

        return kb(
                listOf(
                        listOf(
                                if (mediaEnabled) "Выключить картинки [❌]"
                                else "Включить картинки [✅]"
                        ),
                        listOf("⬅️ Обратно")
                )
        )
    }

    fun statsMenu(): InlineKeyboardMarkup {
        return kb(listOf(listOf("⬅️ Обратно")))
    }

    fun avatarProcessingMenu(minSize: Int): InlineKeyboardMarkup {
        return kb(
                listOf(
                        listOf(
                                "✂️ Обрезать до ${minSize}x${minSize}",
                                "📦 Добавить границы до квадрата"
                        ),
                        listOf("⬅️ Отмена")
                )
        )
    }

    private fun kb(rows: List<List<String>>): InlineKeyboardMarkup {
        return core.ui.UiKeyboard.rows(rows)
    }
}
