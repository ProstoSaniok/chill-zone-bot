package core.routers

import com.github.kotlintelegrambot.Bot
import com.github.kotlintelegrambot.entities.ChatId
import com.github.kotlintelegrambot.entities.InlineKeyboardMarkup
import com.github.kotlintelegrambot.entities.keyboard.InlineKeyboardButton
import core.PendingAction
import core.Session
import core.keyboards.KeyboardFactory
import core.ui.ScreenRenderer
import core.ui.screenText
import data.moderation.*
import data.services.AdminService
import java.util.UUID

/** Telegram presentation for the shared moderation service; no publication SQL in routers. */
object RouterContentModeration {
    private val service = ContentModerationService(listOf(PredictionContent))
    private const val kind = "prediction"
    private val reasons = listOf("Реклама или спам", "Оскорбления или недопустимый контент", "Текст не соответствует формату предсказания", "Дубликат предсказания")
    fun pendingCount(actor: Long): String = try { service.pendingCount(actor, kind).toString() } catch (_: java.sql.SQLException) { "недоступно" }
    private fun keyboard(vararg rows: List<Pair<String, String>>) = InlineKeyboardMarkup.create(
        rows.map { row -> row.map { (label, action) -> InlineKeyboardButton.CallbackData(label, "moderation:$action") } }
    )
    private val home = listOf("В меню предсказаний" to "home")
    val entryActions = setOf("Предложить предсказание", "Мои заявки", "Заявки на предсказания")
    fun isInput(session: Session) = session.action in setOf(PendingAction.ADD_PREDICTION_TEXT, PendingAction.MODERATION_REJECTION_REASON)

    fun entry(bot: Bot, chat: ChatId, actor: Long, text: String, session: Session): Boolean {
        if (text !in entryActions) return false
        safely(bot, chat) {
            session.action = PendingAction.NONE
            when (text) {
                "Предложить предсказание" -> start(bot, chat, actor, session)
                "Мои заявки" -> list(bot, chat, actor, false, "ALL", 0, session)
                else -> list(bot, chat, actor, true, "PENDING", 0, session)
            }
        }
        return true
    }

    private fun start(bot: Bot, chat: ChatId, actor: Long, session: Session) {
        require(!AdminService.isUserBanned(actor)) { "Отправка материалов недоступна: аккаунт заблокирован." }
        session.data.keys.filter { it.startsWith("submission_") }.toList().forEach { session.data.remove(it) }
        session.data["submission_key"] = UUID.randomUUID().toString()
        session.action = PendingAction.ADD_PREDICTION_TEXT
        bot.screenText(chat, "Напишите предсказание: от 1 до 1000 символов.\nБез рекламы, оскорблений и запрещённого контента. Текст появится в сервисе только после проверки сотрудником.")
    }

    fun input(bot: Bot, chat: ChatId, actor: Long, text: String?, session: Session) {
        safely(bot, chat) {
            val value = text?.trim().orEmpty()
            when (session.action) {
                PendingAction.ADD_PREDICTION_TEXT -> {
                    require(!AdminService.isUserBanned(actor)) { "Отправка материалов недоступна: аккаунт заблокирован." }
                    require(value.length in 1..1000) { "Нужен текст от 1 до 1000 символов. Попробуйте ещё раз." }
                    session.data["submission_text"] = value
                    session.action = PendingAction.NONE
                    bot.screenText(chat, "Предложите редкость. Проверяющий сможет её изменить.", replyMarkup = keyboard(
                        *PredictionContent.rarities.mapIndexed { i, label -> listOf(label to "rarity:$i") }.toTypedArray(), home
                    ))
                }
                PendingAction.MODERATION_REJECTION_REASON -> {
                    require(AdminService.isModerator(actor)) { "Нет прав на проверку заявок." }
                    require(value.length in 1..500) { "Укажите причину отказа от 1 до 500 символов." }
                    val id = requireNotNull(session.data["rejection_id"]?.toLongOrNull()) { "Откройте заявку заново." }
                    val item = service.get(actor, id, true)
                    require(item.status == SubmissionStatus.PENDING) { "По этой заявке уже принято решение." }
                    confirmRejection(bot, chat, id, value, session)
                }
                else -> Unit
            }
        }
    }

    fun callback(bot: Bot, chat: ChatId, actor: Long, command: String, session: Session) {
        safely(bot, chat) {
            val p = command.removePrefix("moderation:").split(':')
            when (p[0]) {
                "home" -> {
                    session.action = PendingAction.NONE
                    bot.screenText(chat, "🔮 Меню предсказаний", replyMarkup = KeyboardFactory.predictionsMenu())
                }
                "start" -> {
                    ScreenRenderer.pushHistory()
                    start(bot, chat, actor, session)
                }
                "edit" -> {
                    require(session.data["submission_key"] != null) { "Начните новую заявку." }
                    session.action = PendingAction.ADD_PREDICTION_TEXT
                    bot.screenText(chat, "Введите новый текст предсказания (1–1000 символов).")
                }
                "rarity" -> {
                    val rarity = PredictionContent.rarities[p[1].toInt()]
                    val text = requireNotNull(session.data["submission_text"]) { "Начните новую заявку." }
                    session.data["submission_rarity"] = rarity
                    bot.screenText(chat, "Предпросмотр\n\n$text\n\nПредложенная редкость: $rarity\nПосле отправки изменить заявку нельзя.", replyMarkup = keyboard(
                        listOf("Отправить на проверку" to "submit"), listOf("Изменить текст" to "edit"), home
                    ))
                }
                "submit" -> {
                    val item = service.submit(actor, kind,
                        requireNotNull(session.data["submission_text"]) { "Начните новую заявку." },
                        requireNotNull(session.data["submission_rarity"]) { "Выберите редкость." },
                        UUID.fromString(requireNotNull(session.data["submission_key"]) { "Начните новую заявку." }))
                    session.action = PendingAction.NONE
                    session.data.keys.filter { it.startsWith("submission_") }.toList().forEach { session.data.remove(it) }
                    ScreenRenderer.persistent()
                    card(bot, chat, actor, item.id, false, session)
                }
                "list" -> list(bot, chat, actor, p[1] == "review", p[2], p[3].toInt(), session)
                "card" -> card(bot, chat, actor, p[2].toLong(), p[1] == "review", session)
                "next" -> {
                    val next = service.nextPending(actor, kind, p[1].toLong())
                    if (next != null) card(bot, chat, actor, next.id, true, session)
                    else {
                        ScreenRenderer.notice("Дальше ожидающих заявок нет.")
                        list(bot, chat, actor, true, "PENDING", 0, session)
                    }
                }
                "approve" -> {
                    val item = reviewable(actor, p[1].toLong())
                    bot.screenText(chat, "Заявка №${item.id}\n\n${item.text}\n\nПредложено: ${item.proposedMetadata}\nВыберите итоговую редкость:", replyMarkup = keyboard(
                        *PredictionContent.rarities.mapIndexed { i, label -> listOf(label to "approval:${item.id}:$i") }.toTypedArray(),
                        listOf("К заявке" to "card:review:${item.id}")
                    ))
                }
                "approval" -> {
                    val item = reviewable(actor, p[1].toLong())
                    val rarity = PredictionContent.rarities[p[2].toInt()]
                    bot.screenText(chat, "Опубликовать заявку №${item.id}?\n\n${item.text}\n\nИтоговая редкость: $rarity", replyMarkup = keyboard(
                        listOf("Подтвердить публикацию" to "publish:${item.id}:${p[2]}"),
                        listOf("К заявке" to "card:review:${item.id}")
                    ))
                }
                "publish" -> {
                    val item = service.decide(actor, p[1].toLong(), true, PredictionContent.rarities[p[2].toInt()])
                    finish(bot, chat, actor, item, session)
                }
                "reason" -> {
                    val item = reviewable(actor, p[1].toLong())
                    bot.screenText(chat, "Выберите причину отказа для заявки №${item.id}:", replyMarkup = keyboard(
                        *reasons.mapIndexed { i, reason -> listOf(reason to "preset:${item.id}:$i") }.toTypedArray(),
                        listOf("Написать свою причину" to "custom:${item.id}"),
                        listOf("К заявке" to "card:review:${item.id}")
                    ))
                }
                "preset" -> {
                    val item = reviewable(actor, p[1].toLong())
                    confirmRejection(bot, chat, item.id, reasons[p[2].toInt()], session)
                }
                "custom" -> {
                    val item = reviewable(actor, p[1].toLong())
                    session.data["rejection_id"] = item.id.toString()
                    session.action = PendingAction.MODERATION_REJECTION_REASON
                    bot.screenText(chat, "Напишите причину отказа для заявки №${item.id} (1–500 символов). Автор увидит этот текст.")
                }
                "reject" -> {
                    require(session.data["rejection_id"] == p[1] && session.data["rejection_key"] == p[2]) { "Откройте заявку и укажите причину заново." }
                    val item = service.decide(actor, p[1].toLong(), false,
                        reason = requireNotNull(session.data["rejection_reason"]))
                    session.data.keys.filter { it.startsWith("rejection_") }.toList().forEach { session.data.remove(it) }
                    finish(bot, chat, actor, item, session)
                }
            }
        }
    }

    private fun reviewable(actor: Long, id: Long): Submission {
        val item = service.get(actor, id, true)
        require(item.status == SubmissionStatus.PENDING) { "По этой заявке уже принято решение." }
        require(item.authorId != actor) { "Свою заявку должен проверить другой сотрудник." }
        return item
    }

    private fun list(bot: Bot, chat: ChatId, actor: Long, review: Boolean, filter: String, page: Int, session: Session) {
        val status = if (filter == "ALL") null else SubmissionStatus.valueOf(filter)
        val items = service.list(actor, kind, review, status, page)
        session.action = PendingAction.NONE
        val mode = if (review) "review" else "mine"
        session.data["moderation_${mode}_filter"] = filter
        session.data["moderation_${mode}_page"] = page.toString()
        ScreenRenderer.refreshOnRestore { list(bot, chat, actor, review, filter, page, session) }
        val rows = mutableListOf<List<Pair<String, String>>>()
        val filters = listOf("ALL", "PENDING", "APPROVED", "REJECTED")
        val nextFilter = filters[(filters.indexOf(filter) + 1) % filters.size]
        rows += listOf("Фильтр: ${status?.label ?: "Все"}" to "list:$mode:$nextFilter:0")
        items.take(5).forEach { rows += listOf("№${it.id} · ${it.status.label} · ${it.text.take(25)}" to "card:$mode:${it.id}") }
        val nav = mutableListOf<Pair<String, String>>()
        if (page > 0) nav += "Предыдущая страница" to "list:$mode:$filter:${page - 1}"
        if (items.size > 5) nav += "Следующая страница" to "list:$mode:$filter:${page + 1}"
        if (nav.isNotEmpty()) rows += nav
        if (!review) rows += listOf("Предложить" to "start")
        bot.screenText(chat, (if (review) "Заявки на предсказания · ожидают: ${service.pendingCount(actor, kind)}" else "Мои заявки") +
            "\nФильтр: ${status?.label ?: "Все"} · страница ${page + 1}" + if (items.isEmpty()) "\nЗаявок нет." else "\nВыберите заявку.",
            replyMarkup = keyboard(*rows.toTypedArray()))
    }

    private fun card(bot: Bot, chat: ChatId, actor: Long, id: Long, review: Boolean, session: Session) {
        val item = service.get(actor, id, review)
        session.action = PendingAction.NONE
        ScreenRenderer.refreshOnRestore { card(bot, chat, actor, id, review, session) }
        val rows = mutableListOf<List<Pair<String, String>>>()
        if (review && item.status == SubmissionStatus.PENDING && item.authorId != actor) {
            rows += listOf("Одобрить" to "approve:$id", "Отклонить" to "reason:$id")
        }
        val mode = if (review) "review" else "mine"
        val filter = session.data["moderation_${mode}_filter"] ?: if (review) "PENDING" else "ALL"
        val page = session.data["moderation_${mode}_page"] ?: "0"
        if (review) rows += listOf("Следующая заявка" to "next:$id")
        rows += listOf("К списку заявок" to "list:$mode:$filter:$page")
        if (!review) rows += listOf("Предложить" to "start")
        bot.screenText(chat, buildString {
            append("Заявка №$id\nСтатус: ${item.status.label}\nОтправлено: ${item.createdAt}\n")
            if (review) append("Автор: ${item.authorId}\n")
            append("\n${item.text}\n\nПредложенная редкость: ${item.proposedMetadata}")
            item.approvedMetadata?.let { append("\nИтоговая редкость: $it") }
            item.rejectionReason?.let { append("\nПричина отказа: $it") }
            item.reviewedAt?.let { append("\nДата решения: $it") }
            if (review) {
                item.reviewerId?.let { append("\nПроверил: $it") }
                append(if (item.flagged) "\n⚠️ Фильтр отметил подозрительный текст. Проверьте вручную." else "\nАвтофильтр: отметок нет. Нужна ручная проверка.")
            }
        }, replyMarkup = keyboard(*rows.toTypedArray()))
    }

    private fun finish(bot: Bot, chat: ChatId, actor: Long, item: Submission, session: Session) {
        ScreenRenderer.persistent()
        // Notification is independent of screen rendering and cannot roll back the committed decision.
        try {
            val sent = bot.sendMessage(ChatId.fromId(item.authorId), "Заявка на предсказание №${item.id}: ${item.status.label}.\n" +
                (item.rejectionReason?.let { "Причина отказа: $it" } ?: "Итоговая редкость: ${item.approvedMetadata}") +
                "\nПодробности: /predictions → Мои заявки.")
            if (!sent.isSuccess) System.err.println("Submission ${item.id}: decision notification failed")
        } catch (_: Exception) { System.err.println("Submission ${item.id}: decision notification failed") }
        card(bot, chat, actor, item.id, true, session)
    }

    private fun confirmRejection(bot: Bot, chat: ChatId, id: Long, reason: String, session: Session) {
        session.data["rejection_id"] = id.toString()
        session.data["rejection_reason"] = reason
        session.data["rejection_key"] = UUID.randomUUID().toString()
        session.action = PendingAction.NONE
        bot.screenText(chat, "Отклонить заявку №$id?\n\nПричина отказа: $reason", replyMarkup = keyboard(
            listOf("Подтвердить отказ" to "reject:$id:${session.data["rejection_key"]}"),
            listOf("К заявке" to "card:review:$id")
        ))
    }

    private inline fun safely(bot: Bot, chat: ChatId, action: () -> Unit) {
        try { action() }
        catch (e: IllegalArgumentException) {
            bot.screenText(chat, e.message ?: "Некорректное действие.", replyMarkup = keyboard(home))
        }
        catch (_: java.sql.SQLException) {
            bot.screenText(chat, "Не удалось обработать заявку. Попробуйте ещё раз; результат можно проверить в списке заявок.", replyMarkup = keyboard(home))
        }
    }
}
