package data.moderation

import data.Db
import data.services.AdminService
import data.services.ModerationService
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID

enum class SubmissionStatus(val label: String) {
    PENDING("На проверке"), APPROVED("Одобрено"), REJECTED("Отклонено")
}

data class Submission(
    val id: Long, val kind: String, val authorId: Long, val text: String,
    val proposedMetadata: String, val status: SubmissionStatus, val flagged: Boolean,
    val createdAt: String, val reviewerId: Long?, val reviewedAt: String?,
    val rejectionReason: String?, val approvedMetadata: String?, val publishedId: Long?
)

/** Each content service owns validation and publication; the queue owns the lifecycle. */
interface ModeratedContent {
    val kind: String
    fun validate(text: String, metadata: String)
    fun publish(connection: Connection, submission: Submission, metadata: String): Long
}

/** No Telegram dependencies. A meme adapter can later publish using this same transaction. */
class ContentModerationService(
    handlers: List<ModeratedContent>,
    private val connect: () -> Connection = Db::openConnection,
    private val canReview: (Long) -> Boolean = AdminService::isModerator,
    private val isBanned: (Long) -> Boolean = AdminService::isUserBanned,
    private val flagText: (String) -> Boolean = ModerationService::hasSuspiciousText
) {
    private val handlers = handlers.associateBy { it.kind }

    private fun handler(kind: String) = requireNotNull(handlers[kind]) { "Неизвестный тип материала." }

    fun submit(author: Long, kind: String, text: String, metadata: String, requestKey: UUID): Submission {
        require(!isBanned(author)) { "Отправка материалов недоступна: аккаунт заблокирован." }
        val normalized = text.trim()
        handler(kind).validate(normalized, metadata)
        return connect().use { connection ->
            connection.prepareStatement("""
                insert into content_submissions(kind, author_id, content_text, proposed_metadata, flagged, request_key)
                values (?, ?, ?, ?, ?, ?) on conflict (author_id, request_key)
                do update set request_key = excluded.request_key returning *
            """).use { stmt ->
                stmt.setString(1, kind); stmt.setLong(2, author); stmt.setString(3, normalized)
                stmt.setString(4, metadata); stmt.setBoolean(5, flagText(normalized)); stmt.setObject(6, requestKey)
                stmt.executeQuery().use { it.next(); map(it) }
            }
        }
    }

    fun list(actor: Long, kind: String, review: Boolean, status: SubmissionStatus?, page: Int): List<Submission> {
        handler(kind)
        if (review) require(canReview(actor)) { "Нет прав на проверку заявок." }
        return connect().use { c ->
            c.prepareStatement("""select * from content_submissions where kind = ?
                ${if (review) "" else "and author_id = ?"}
                ${if (status == null) "" else "and status = ?"}
                order by id ${if (review && status == SubmissionStatus.PENDING) "asc" else "desc"}
                limit 6 offset ?""").use { s ->
                var i = 1
                s.setString(i++, kind)
                if (!review) s.setLong(i++, actor)
                if (status != null) s.setString(i++, status.name)
                s.setInt(i, page.coerceIn(0, 100000) * 5)
                s.executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } }
            }
        }
    }

    fun get(actor: Long, id: Long, review: Boolean): Submission {
        if (review) require(canReview(actor)) { "Нет прав на проверку заявок." }
        return connect().use { c ->
            val item = read(c, id)
            require(review || item.authorId == actor) { "Заявка недоступна." }
            item
        }
    }

    fun pendingCount(actor: Long, kind: String): Int {
        require(canReview(actor)) { "Нет прав на проверку заявок." }
        return connect().use { c -> c.prepareStatement(
            "select count(*) from content_submissions where kind = ? and status = 'PENDING'"
        ).use { s -> s.setString(1, kind); s.executeQuery().use { it.next(); it.getInt(1) } } }
    }

    fun nextPending(actor: Long, kind: String, afterId: Long): Submission? {
        require(canReview(actor)) { "Нет прав на проверку заявок." }
        return connect().use { c -> c.prepareStatement(
            "select * from content_submissions where kind = ? and status = 'PENDING' and id > ? order by id limit 1"
        ).use { s ->
            s.setString(1, kind); s.setLong(2, afterId)
            s.executeQuery().use { if (it.next()) map(it) else null }
        } }
    }

    /** Row locking makes publication and the decision atomic even across bot instances. */
    fun decide(actor: Long, id: Long, approve: Boolean, metadata: String = "", reason: String = ""): Submission {
        require(canReview(actor)) { "Нет прав на проверку заявок." }
        require(!isBanned(actor)) { "Аккаунт заблокирован." }
        return connect().use { c ->
            c.autoCommit = false
            try {
                val item = read(c, id, lock = true)
                require(item.status == SubmissionStatus.PENDING) { "По этой заявке уже принято решение." }
                require(item.authorId != actor) { "Свою заявку должен проверить другой сотрудник." }
                val adapter = handler(item.kind)
                if (approve) adapter.validate(item.text, metadata)
                else require(reason.trim().length in 1..500) { "Укажите причину отказа от 1 до 500 символов." }
                val published = if (approve) adapter.publish(c, item, metadata) else null
                c.prepareStatement("""update content_submissions set status = ?, reviewer_id = ?,
                    reviewed_at = now(), rejection_reason = ?, approved_metadata = ?, published_id = ? where id = ?""").use { s ->
                    s.setString(1, if (approve) "APPROVED" else "REJECTED"); s.setLong(2, actor)
                    s.setString(3, if (approve) null else reason.trim()); s.setString(4, if (approve) metadata else null)
                    s.setObject(5, published); s.setLong(6, id); s.executeUpdate()
                }
                val result = read(c, id)
                c.commit()
                result
            } catch (e: Exception) { c.rollback(); throw e }
        }
    }

    private fun read(c: Connection, id: Long, lock: Boolean = false): Submission = c.prepareStatement(
        "select * from content_submissions where id = ?" + if (lock) " for update" else ""
    ).use { s -> s.setLong(1, id); s.executeQuery().use {
        require(it.next()) { "Заявка не найдена." }; map(it)
    } }

    private fun map(rs: ResultSet) = Submission(
        rs.getLong("id"), rs.getString("kind"), rs.getLong("author_id"), rs.getString("content_text"),
        rs.getString("proposed_metadata"), SubmissionStatus.valueOf(rs.getString("status")), rs.getBoolean("flagged"),
        rs.getString("created_at"), (rs.getObject("reviewer_id") as? Number)?.toLong(), rs.getString("reviewed_at"),
        rs.getString("rejection_reason"), rs.getString("approved_metadata"), (rs.getObject("published_id") as? Number)?.toLong()
    )
}
