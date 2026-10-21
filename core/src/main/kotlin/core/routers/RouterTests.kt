package core.routers

import core.ui.*
import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.CallbackQuery
import com.github.kotlintelegrambot.entities.ParseMode
import core.ImageManager
import core.PendingAction
import core.Session
import core.SessionStore
import core.FSMContext
import core.keyboards.KeyboardFactory
import core.keyboards.KeyboardTests
import data.models.*
import data.repositories.TestRepository
import data.repositories.UserRepository
import data.services.ModerationService

object RouterTests {
    private const val PAGE_SIZE = 5

    private fun showTestsMenu(bot: Bot, chat: ChatId, uid: Long, tests: TestRepository, users: UserRepository) {
        val session = SessionStore.get(uid)
        session.data["last_menu"] = "tests"
        session.action = PendingAction.NONE
        session.context = FSMContext.MEMES
        val profile = users.profile(uid)
        val testsMessage = """📝 <b>Меню тестов</b>

Здесь вы можете:
• <b>Случайный тест</b> - пройти случайный тест
• <b>Мои тесты</b> - посмотреть список тестов
• <b>Поиск тестов</b> - найти тест по названию

Выберите действие:"""
        ImageManager.sendMessageWithImage(
                bot,
                chat,
                testsMessage,
                profile,
                parseMode = ParseMode.HTML,
                replyMarkup = KeyboardFactory.testsMenu()
        )
    }

    private fun showTestSearchPrompt(bot: Bot, chat: ChatId, uid: Long, users: UserRepository) {
        val session = SessionStore.get(uid)
        session.action = PendingAction.SEARCH_TESTS
        session.data["last_menu"] = "tests"
        val profile = users.profile(uid)
        ImageManager.sendMessageWithImage(
                bot,
                chat,
                "Напиши название теста для поиска.",
                profile,
                parseMode = ParseMode.HTML, replyMarkup = KeyboardTests.testsSearchNavMenu())
    }

    private fun showTestSearchResults(
            bot: Bot,
            chat: ChatId,
            uid: Long,
            tests: List<TestSummary>,
            index: Int,
            query: String
    ) {
        if (tests.isEmpty()) return
        val session = SessionStore.get(uid)
        val safeIndex = index.coerceIn(0, maxOf(0, tests.size - 1))
        val endIndex = minOf(safeIndex + PAGE_SIZE, tests.size)
        val page = tests.subList(safeIndex, endIndex)
        session.action = PendingAction.TEST_SEARCH_RESULTS
        session.data["test_search_results"] = tests.joinToString("|") { it.id.toString() }
        session.data["test_search_query"] = query
        session.data["test_search_index"] = safeIndex.toString()
        session.data["last_menu"] = "tests"

        val message = """🔎 <b>Поиск по</b> "$query" (${safeIndex + 1}-$endIndex из ${tests.size})

Выберите тест:"""

        val rows = mutableListOf<List<com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton>>()
        page.forEach { test ->
            rows.add(
                    listOf(
                            com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton.CallbackData(
                                    text = "📝 ${test.title}",
                                    callbackData = "test_search_start_${test.id}"
                            )
                )
            )
        }
        if (tests.size > PAGE_SIZE) {
            val prevIndex = if (safeIndex == 0) ((tests.size - 1) / PAGE_SIZE) * PAGE_SIZE else safeIndex - PAGE_SIZE
            val nextIndex = if (safeIndex + PAGE_SIZE >= tests.size) 0 else safeIndex + PAGE_SIZE
            rows.add(
                    listOf(
                            com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton.CallbackData(
                                    text = "⬅️",
                                    callbackData = "test_search_page_$prevIndex"
                            ),
                            com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton.CallbackData(
                                    text = "➡️",
                                    callbackData = "test_search_page_$nextIndex"
                            )
                    )
            )
        }

        bot.screenText(chat, message, parseMode = ParseMode.HTML, replyMarkup = KeyboardTests.testsSearchResultsMenu())
        bot.screenText(chat, "", replyMarkup = com.github.kotlintelegrambot.entities.InlineKeyboardMarkup.create(rows))
    }

    fun startRandomTest(
            bot: Bot,
            chat: ChatId,
            uid: Long,
            tests: TestRepository,
            users: UserRepository
    ) {
        val test = tests.randomTest()
        if (test == null) {
            bot.screenText(chat, "Пока нет тестов.", replyMarkup = KeyboardFactory.testsMenu())
            return
        }
        val runId = tests.createRun(uid, test.id)
        val session = SessionStore.get(uid)
        session.action = PendingAction.PLAY_TEST
        session.clearDraft()
        session.data["last_menu"] = "tests"
        session.data["run_id"] = runId.toString()
        session.data["test_id"] = test.id.toString()
        session.data["index"] = "0"
        session.data["correct"] = "0"
        sendTestQuestion(bot, chat, runId, tests)
    }

    fun showUserTests(bot: Bot, chat: ChatId, tests: TestRepository) {
        val list = tests.listTests()
        if (list.isEmpty()) {
            bot.screenText(chat, "Пока тестов нет.", replyMarkup = KeyboardFactory.testsMenu())
            return
        }
        val my = list.take(10).joinToString("\n") { "• #${it.id} ${it.title}" }
        bot.screenText(
                chat,
                "Доступные тесты:\n$my",
                parseMode = ParseMode.HTML,
                replyMarkup = KeyboardFactory.testsMenu()
        )
    }

    fun handleTestAction(
            bot: Bot,
            chat: ChatId,
            uid: Long,
            text: String,
            tests: TestRepository,
            users: UserRepository,
            session: Session
    ): Boolean {
        when (text) {
            "📝 Тесты" -> {
                showTestsMenu(bot, chat, uid, tests, users)
                return true
            }
            "Случайный тест" -> {
                startRandomTest(bot, chat, uid, tests, users)
                return true
            }
            "Мои тесты" -> {
                SessionStore.get(uid).data["last_menu"] = "tests"
                showUserTests(bot, chat, tests)
                return true
            }
            "Создать тест" -> {
                SessionStore.get(uid).data["last_menu"] = "tests"
                session.action = PendingAction.CREATE_TEST_TITLE
                session.clearDraft()
                val profile = users.profile(uid)
                ImageManager.sendMessageWithImage(bot, chat, "Придумай название теста.", profile, replyMarkup = KeyboardFactory.testsMenu())
                return true
            }
            "Поиск тестов" -> {
                showTestSearchPrompt(bot, chat, uid, users)
                return true
            }
            "⬅️ Обратно" -> {
                // Возврат в главное меню или в меню тестов
                if (session.action in
                        listOf(
                                PendingAction.SEARCH_TESTS,
                                PendingAction.TEST_SEARCH_RESULTS,
                                PendingAction.CREATE_TEST_TITLE,
                                PendingAction.CREATE_TEST_KIND,
                                PendingAction.CREATE_TEST_PROMPT,
                                PendingAction.CREATE_TEST_MORE,
                                PendingAction.PLAY_TEST
                        )
                ) {
                    session.action = PendingAction.NONE
                    session.clearDraft()
                    showTestsMenu(bot, chat, uid, tests, users)
                } else {
                    // Возврат в главное меню
                    SessionStore.clear(uid)
                    bot.screenText(chat, "Главное меню.", replyMarkup = KeyboardFactory.mainMenu())
                }
                return true
            }
            else -> {
                if (session.action == PendingAction.SEARCH_TESTS) {
                        val query = text.trim()
                        if (query.isBlank()) {
                            showTestSearchPrompt(bot, chat, uid, users)
                            return true
                        }

                        val results = tests.listTests().filter {
                            it.title.contains(query, ignoreCase = true)
                        }
                        if (results.isEmpty()) {
                            bot.screenText(
                                    chat,
                                    "❌ Ничего не найдено по запросу: \"$query\"",
                                    replyMarkup = KeyboardTests.testsSearchNavMenu()
                            )
                        } else {
                            showTestSearchResults(bot, chat, uid, results, 0, query)
                        }
                        return true
                }

                when (session.action) {
                    PendingAction.CREATE_TEST_TITLE,
                    PendingAction.CREATE_TEST_KIND,
                    PendingAction.CREATE_TEST_PROMPT,
                    PendingAction.CREATE_TEST_MORE -> {
                        val moderationResult = ModerationService.checkText(uid, text)
                        if (!moderationResult.isAllowed) {
                            if (moderationResult.shouldBan) {
                                ModerationService.banForViolation(
                                        uid,
                                        "Использование ненормативной лексики"
                                )
                                ModerationService.clearWarnings(uid)
                                bot.screenText(
                                        chat,
                                        "🚫 Вы были заблокированы за использование ненормативной лексики."
                                )
                                session.action = PendingAction.NONE
                                session.clearDraft()
                            } else {
                                ModerationService.addWarning(uid)
                                bot.screenText(
                                        chat,
                                        "⚠️ <b>Предупреждение!</b>\n\nВаше сообщение содержит недопустимый контент.\n\nПовторное нарушение приведёт к автоматическому бану на 1 сутки.",
                                        parseMode = ParseMode.HTML
                                )
                            }
                            return true
                        }
                    }
                    else -> {}
                }

                when (session.action) {
                    PendingAction.CREATE_TEST_TITLE -> {
                        session.data["test_title"] = text
                        session.action = PendingAction.CREATE_TEST_KIND
                        bot.screenText(chat, "Выбери тип теста:", replyMarkup = KeyboardFactory.testKinds())
                        return true
                    }
                    PendingAction.CREATE_TEST_KIND -> {
                        val kind = text.lowercase()
                        if (kind !in listOf("single", "multi", "number", "match")) {
                            bot.screenText(
                                    chat,
                                    "Неверный тип. Используй: single, multi, number, match"
                            )
                            return true
                        }
                        session.data["test_kind"] = kind
                        session.action = PendingAction.CREATE_TEST_PROMPT
                        bot.screenText(chat, "Напиши вопрос:")
                        return true
                    }
                    PendingAction.CREATE_TEST_PROMPT -> {
                        session.data["test_prompt"] = text
                        session.action = PendingAction.CREATE_TEST_MORE
                        bot.screenText(
                                chat,
                                "Напиши варианты ответа (через |) и правильный ответ после |:\nПример: Вариант1 | Вариант2 | Вариант3 | 1"
                        )
                        return true
                    }
                    PendingAction.CREATE_TEST_MORE -> {
                        val title = session.data["test_title"] ?: ""
                        val kind = session.data["test_kind"] ?: "single"
                        val prompt = session.data["test_prompt"] ?: ""

                        val parts = text.split("|").map { it.trim() }
                        val options = parts.dropLast(1)
                        val answer = parts.last()

                        ScreenRenderer.persistent()
                        tests.createTestFull(title, prompt, kind, answer, options)
                        session.action = PendingAction.NONE
                        session.clearDraft()
                        bot.screenText(
                                chat,
                                "✅ Тест создан!\n\n📌 $title",
                                replyMarkup = KeyboardFactory.testsMenu()
                        )
                        return true
                    }
                    else -> return false
                }
            }
        }
    }

    fun handleTestAnswer(
            bot: Bot,
            chat: ChatId,
            uid: Long,
            text: String,
            session: Session,
            tests: TestRepository,
            users: UserRepository
    ): Boolean {
        val runId = session.data["run_id"]?.toLongOrNull() ?: return false
        val testId = session.data["test_id"]?.toLongOrNull() ?: return false
        val index = session.data["index"]?.toIntOrNull() ?: 0
        val questions = tests.questions(testId)
        if (index >= questions.size) {
            session.action = PendingAction.NONE
            session.clearDraft()
            bot.screenText(chat, "Тест уже завершён.", replyMarkup = KeyboardFactory.testsMenu())
            return true
        }

        val q = questions[index]
        val correct = isCorrect(q, text)
        val newCorrect = session.data["correct"]?.toIntOrNull()?.plus(if (correct) 1 else 0) ?: 0
        tests.saveAnswer(runId, q.id, text, correct)
        tests.updateRun(runId, index + 1, newCorrect, index + 1 >= questions.size)

        if (correct) users.addRating(uid, 1)

        if (index + 1 >= questions.size) {
            ScreenRenderer.persistent()
            session.action = PendingAction.NONE
            session.clearDraft()
            bot.screenText(
                    chat,
                    "Тест завершён. Результат: $newCorrect/${questions.size}",
                    replyMarkup = KeyboardFactory.testsMenu()
            )
            return true
        }

        session.data["index"] = (index + 1).toString()
        session.data["correct"] = newCorrect.toString()
        sendTestQuestion(bot, chat, runId, tests)
        return true
    }

    private fun sendTestQuestion(bot: Bot, chat: ChatId, runId: Long, tests: TestRepository) {
        val runInfo = tests.getRun(runId) ?: return
        val testId = runInfo.second
        val index = runInfo.third
        val questions = tests.questions(testId)
        if (index >= questions.size) return

        val q = questions[index]
        val header = "Вопрос ${index + 1}/${questions.size}:\n${q.prompt}"
        when (q.kind) {
            QuestionKind.SINGLE ->
                    bot.screenText(
                            chat,
                            header +
                                    "\n\nВыбери ответ:",
                            replyMarkup = com.github.kotlintelegrambot.entities.InlineKeyboardMarkup.create(
                                q.options.mapIndexed { optionIndex, option -> listOf(
                                    com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton.CallbackData(
                                        option, "answer:${optionIndex + 1}"
                                    )
                                ) }
                            )
                    )
            QuestionKind.MULTI ->
                    bot.screenText(
                            chat,
                            header + "\n\nОтправь несколько вариантов через запятую.",
                            parseMode = ParseMode.HTML,
                            replyMarkup = KeyboardFactory.testsMenu()
                    )
            QuestionKind.NUMBER ->
                    bot.screenText(
                            chat,
                            header + "\n\nОтветь числом.",
                            parseMode = ParseMode.HTML,
                            replyMarkup = KeyboardFactory.testsMenu()
                    )
            QuestionKind.MATCH ->
                    bot.screenText(
                            chat,
                            header + "\n\nОтправь пары в виде левый=правый;левый2=правый2.",
                            parseMode = ParseMode.HTML,
                            replyMarkup = KeyboardFactory.testsMenu()
                    )
        }
    }

    fun handleCallback(bot: Bot, callback: CallbackQuery, tests: TestRepository, users: UserRepository) {
        val chat = ChatId.fromId(callback.message!!.chat.id)
        val uid = SessionStore.get(callback.from.id).data["profile_impersonated_user_id"]?.toLongOrNull() ?: callback.from.id
        val data = callback.data ?: return
        val session = SessionStore.get(uid)

        when {
            data == "test_back_to_menu" -> {
                showTestsMenu(bot, chat, uid, tests, users)
                bot.callbackNotice(callback.id)
            }
            data.startsWith("test_search_start_") -> {
                val testId = data.substringAfter("test_search_start_").toLongOrNull() ?: return
                val test = tests.getTest(testId)
                if (test == null) {
                    bot.callbackNotice(callback.id, "❌ Тест не найден")
                    return
                }

                val runId = tests.createRun(uid, test.id)
                session.action = PendingAction.PLAY_TEST
                session.clearDraft()
                session.data["last_menu"] = "tests"
                session.data["run_id"] = runId.toString()
                session.data["test_id"] = test.id.toString()
                session.data["index"] = "0"
                session.data["correct"] = "0"
                sendTestQuestion(bot, chat, runId, tests)
                bot.callbackNotice(callback.id)
            }
            data.startsWith("test_search_page_") -> {
                val pageIndex = data.substringAfter("test_search_page_").toIntOrNull() ?: return
                val query = session.data["test_search_query"] ?: return
                val results = tests.listTests().filter { it.title.contains(query, ignoreCase = true) }
                if (results.isNotEmpty()) {
                    showTestSearchResults(bot, chat, uid, results, pageIndex, query)
                    bot.callbackNotice(callback.id)
                }
            }
        }
    }

    internal fun isCorrect(q: TestQuestion, answer: String): Boolean {
        val user = answer.trim().lowercase()
        return when (q.kind) {
            QuestionKind.SINGLE -> {
                val expected = q.answer.trim()
                val optionIndex = expected.toIntOrNull()
                val selected = user.toIntOrNull()?.let { q.options.getOrNull(it - 1) }
                    ?.trim()?.lowercase() ?: user
                if (optionIndex != null && optionIndex in 1..q.options.size) {
                    q.options[optionIndex - 1].trim().lowercase() == selected ||
                            user == expected.lowercase()
                } else {
                    expected.lowercase() == selected
                }
            }
            QuestionKind.MULTI -> {
                val expected =
                        q.answer.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
                val got = user.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
                expected == got
            }
            QuestionKind.NUMBER -> q.answer.trim() == user
            QuestionKind.MATCH -> normalizePairs(q.answer) == normalizePairs(answer)
        }
    }

    private fun normalizePairs(text: String): Map<String, String> {
        return text.split(";", ",")
                .mapNotNull { token ->
                    val idx = token.indexOf("=")
                    if (idx <= 0) return@mapNotNull null
                    token.substring(0, idx).trim().lowercase() to
                            token.substring(idx + 1).trim().lowercase()
                }
                .toMap()
    }
}
