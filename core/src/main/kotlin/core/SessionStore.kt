package core

enum class PendingAction {
    NONE,
    EDIT_NAME,
    EDIT_USERNAME,
    EDIT_BIO,
    EDIT_AVATAR,
    EDIT_PROFILE,
    AVATAR_PROCESSING,  // Обработка неквадратного изображения для аватарки
    ADD_MEME,
    FEEDBACK_TEXT,
    ADD_PREDICTION_TEXT,
    ADD_PREDICTION_RARITY,
    SEARCH_PREDICTIONS,
    CREATE_TEST_TITLE,
    CREATE_TEST_AUTHOR,
    CREATE_TEST_COUNT,
    CREATE_TEST_KIND,
    CREATE_TEST_PROMPT,
    CREATE_TEST_MORE,
    PLAY_TEST,
    SEARCH_TESTS,
    TEST_SEARCH_RESULTS,
    CREATE_EVENT_TITLE,
    CREATE_EVENT_DESC,
    CREATE_EVENT_PLACE,
    CREATE_EVENT_TIME,
    CREATE_EVENT_MAX,
    CREATE_EVENT_KIND,
    COLLECTION_QUERY,
    COLLECTION_RESULTS,  // Результаты поиска
    SEARCH_TYPE_SELECT,  // Выбор типа поиска (фильмы, сериалы и т.д.)
    RPS_CHOICE,
    TIC_TAC_TOE,
    VIEW_FAVORITES,  // Новый action для просмотра избранных с навигацией
    
    // Админские действия
    ADMIN_USER_MANAGEMENT,
    ADMIN_USER_LIST,
    ADMIN_BANNED_LIST,
    ADMIN_SEARCH,
    ADMIN_SEARCH_RESULTS,
    
    // Новые состояния для редактирования профиля
    ADMIN_EDIT_PROFILE,
    ADMIN_EDIT_NAME,
    ADMIN_EDIT_USERNAME,
    ADMIN_EDIT_BIO,
    ADMIN_EDIT_BAN_REASON,
    ADMIN_EDIT_BAN_EXPIRY,
    ADMIN_FILTER
}

data class Session(
    var action: PendingAction = PendingAction.NONE,
    val data: MutableMap<String, String> = mutableMapOf(),
    var context: FSMContext = FSMContext.MEMES  // По умолчанию контекст мемов
) {
    fun clearDraft() {
        val account = data["profile_impersonated_user_id"]
        data.clear()
        account?.let { data["profile_impersonated_user_id"] = it }
    }
}

object SessionStore {
    private val sessions = java.util.concurrent.ConcurrentHashMap<Pair<Long, Long>, Session>()
    private val owner = ThreadLocal<Pair<Long, Long>?>()

    fun get(chatId: Long): Session = sessions.computeIfAbsent(owner.get() ?: (chatId to chatId)) { Session() }

    fun clear(chatId: Long) {
        // Keep the same object: routers may still hold it during this update.
        val session = get(chatId)
        val account = session.data["profile_impersonated_user_id"]
        session.action = PendingAction.NONE
        session.context = FSMContext.MEMES
        session.data.clear()
        account?.let { session.data["profile_impersonated_user_id"] = it }
    }

    fun <T> withOwner(chatId: Long, actorId: Long, block: () -> T): T {
        val previous = owner.get()
        owner.set(chatId to actorId)
        try {
            return block()
        } finally {
            owner.set(previous)
        }
    }
}
