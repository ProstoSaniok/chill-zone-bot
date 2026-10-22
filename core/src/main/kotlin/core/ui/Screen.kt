package core.ui

import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import com.github.kotlintelegrambot.entities.ParseMode
import com.github.kotlintelegrambot.entities.TelegramFile
import core.FSMContext
import core.PendingAction
import core.Session

data class Screen(
    val text: String,
    val keyboard: InlineKeyboardMarkup? = null,
    val photo: TelegramFile? = null,
    val parseMode: ParseMode? = null,
    val persistent: Boolean = false,
    val refresh: (() -> Unit)? = null,
    val destination: String? = null,
    val depth: Int = 0
)

data class SessionSnapshot(
    val action: PendingAction,
    val context: FSMContext,
    val data: Map<String, String>
) {
    fun restore(session: Session) {
        session.action = action
        session.context = context
        session.data.clear()
        session.data.putAll(data)
    }

    companion object {
        fun of(session: Session) = SessionSnapshot(session.action, session.context, session.data.toMap())
    }
}

data class HistoryEntry(val screen: Screen, val session: SessionSnapshot)

class UiSession {
    var messageId: Long? = null
    var screen: Screen? = null
    var actions: Map<String, String> = emptyMap()
    val retiredActions = LinkedHashMap<String, HistoryEntry>()
    var page = 0
    var renderedText: String? = null
    var renderedParseMode: ParseMode? = null
    val history = ArrayDeque<HistoryEntry>()
}
