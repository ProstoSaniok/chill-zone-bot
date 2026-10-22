package core

import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
import core.ui.ScreenRenderer
import core.ui.UiKeyboard
import core.ui.HistoryEntry
import core.ui.Screen
import core.ui.SessionSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NavigationTest {
    private fun entry(destination: String?, action: PendingAction, depth: Int) = HistoryEntry(
        Screen(destination.orEmpty(), destination = destination, depth = depth),
        SessionSnapshot.of(Session(action = action))
    )

    @Test fun completedProfileEditReturnsToProfileWithOneBack() {
        val profile = entry("👤 Профиль", PendingAction.NONE, 1)
        val history = ArrayDeque(listOf(
            entry("🏠 Главное меню", PendingAction.NONE, 0), profile,
            entry("✏️ Изменить профиль", PendingAction.EDIT_PROFILE, 2),
            entry(null, PendingAction.EDIT_NAME, 3)
        ))
        assertEquals(2, ScreenRenderer.finishFormHistory(history, "✏️ Изменить профиль"))
        assertEquals(profile, history.removeLast())
    }

    @Test fun avatarReturningToProfileDoesNotDuplicateProfile() {
        val menu = entry("🏠 Главное меню", PendingAction.NONE, 0)
        val history = ArrayDeque(listOf(menu,
            entry("👤 Профиль", PendingAction.NONE, 1),
            entry("✏️ Изменить профиль", PendingAction.EDIT_PROFILE, 2)
        ))
        assertEquals(1, ScreenRenderer.finishFormHistory(history, "👤 Профиль"))
        assertEquals(menu, history.removeLast())
    }

    @Test fun completingAnotherFormPreservesItsParent() {
        val parent = entry("📅 События", PendingAction.NONE, 1)
        val history = ArrayDeque(listOf(parent, entry(null, PendingAction.CREATE_EVENT_TITLE, 2)))
        assertEquals(null, ScreenRenderer.finishFormHistory(history, null))
        assertEquals(parent, history.single())
    }

    private fun commands(depth: Int, main: Boolean = false): List<List<String>> {
        val rows = UiKeyboard.rows(listOf(listOf("⬅️ Обратно", "🏠 Главное меню"))).inlineKeyboard
        return ScreenRenderer.navigationRows(rows, depth, main).map { row ->
            row.map { (it as InlineKeyboardButton.CallbackData).callbackData }
        }
    }

    @Test fun homeAppearsBesideBackOnlyAtDepthTwoAndThree() {
        for (depth in 0..5) {
            val rows = commands(depth)
            assertEquals(depth in 2..3, rows.flatten().contains("text:🏠 Главное меню"))
            assertEquals(1, rows.flatten().count { it == "text:⬅️ Обратно" })
            if (depth in 2..3) assertEquals(listOf("text:⬅️ Обратно", "text:🏠 Главное меню"), rows.single())
        }
    }

    @Test fun mainMenuDoesNotGainNavigation() {
        val rows = ScreenRenderer.navigationRows(emptyList(), 0, true)
        assertTrue(rows.isEmpty())
        assertFalse(commands(0, true).flatten().contains("text:🏠 Главное меню"))
    }

    @Test fun missingBackGetsTheSameDepthRule() {
        for (depth in 1..4) {
            val rows = ScreenRenderer.navigationRows(emptyList(), depth, false)
            assertEquals(if (depth in 2..3) 2 else 1, rows.single().size)
        }
    }
}
