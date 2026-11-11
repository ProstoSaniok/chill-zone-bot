package data.services

import data.Db
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

object ModerationService {
    private val badWords = listOf(
        "fuck", "shit", "damn", "ass", "bitch", "bastard", "cunt", "dick", "pussy",
        "nigger", "nigga", "faggot", "retard", "whore", "slut", "douche", "crap",
        "fucking", "fucked", "fuckin", "fucker", "motherfucker", "bullshit",
        "бля", "блядь", "пизда", "ебать", "ебал", "ебёт", "хрен", "хрень",
        "сука", "сучки", "сучий", "срака", "ссака", "ссака", "ссаки",
        "дерьмо", "говно", "гомик", "пидор", "педик", "шлюха", "блядина",
        "ебанат", "ебучий", "мудак", "мудила", "мудило", "скотина", "скотины",
        "тупой", "тупорылый", "дебил", "идиот", "кретин", "дурак", "дурость",
        "чмошник", "чмо", "охуеть", "охуенный", "охуен", "пиздатый", "пиздец",
        "трахать", "трахал", "трахает", "трах", "выёбывается", "выёбываться",
        "ебашить", "ебашу", "ебашит", "отъебись", "пошёл_нахуй", "иди_нахуй",
        "нахуй", "ёбаный", "ёба", "выеб", "выёб", "вжопу", "в_жопу", "жопа"
    )

    private val badWordPatterns: List<Pattern> = badWords.mapNotNull { word ->
        try {
            Pattern.compile(word, Pattern.CASE_INSENSITIVE)
        } catch (e: PatternSyntaxException) {
            null
        }
    }

    data class ModerationResult(
        val isAllowed: Boolean,
        val warningCount: Int,
        val shouldBan: Boolean
    )

    /** Advisory signal only: no warnings or bans and no database reads. */
    fun hasSuspiciousText(text: String): Boolean = badWordPatterns.any { it.matcher(text.lowercase()).find() }

    fun checkText(userId: Long, text: String): ModerationResult {
        if (text.isBlank()) return ModerationResult(true, 0, false)

        val lowerText = text.lowercase()
        val hasBadWord = badWordPatterns.any { pattern -> pattern.matcher(lowerText).find() }

        if (!hasBadWord) return ModerationResult(true, 0, false)

        val warningCount = getWarningCount(userId)
        val shouldBan = warningCount >= 1

        return ModerationResult(false, warningCount, shouldBan)
    }

    fun addWarning(userId: Long): Boolean {
        return try {
            Db.execute(
                """
                insert into moderation_warnings (user_id, warned_at)
                values (?, ?)
                """
            ) { stmt ->
                stmt.setLong(1, userId)
                stmt.setTimestamp(2, Timestamp.from(Instant.now()))
            }
            true
        } catch (e: Exception) {
            println("Error adding warning for user $userId: ${e.message}")
            false
        }
    }

    fun getWarningCount(userId: Long): Int {
        return try {
            Db.single(
                """
                select count(*) as count from moderation_warnings
                where user_id = ?
                and warned_at > now() - interval '24 hours'
                """,
                bind = { stmt -> stmt.setLong(1, userId) },
                map = { rs -> rs.getInt("count") }
            ) ?: 0
        } catch (e: Exception) {
            println("Error getting warning count for user $userId: ${e.message}")
            0
        }
    }

    fun banForViolation(userId: Long, reason: String): Boolean {
        return try {
            Db.execute(
                """
                insert into banned_users (user_id, reason, ban_expires_at)
                values (?, ?, ?)
                on conflict (user_id) do update set
                    reason = excluded.reason,
                    banned_at = now(),
                    ban_expires_at = now() + interval '1 day'
                """
            ) { stmt ->
                stmt.setLong(1, userId)
                stmt.setString(2, reason)
                stmt.setTimestamp(3, Timestamp.from(Instant.now().plus(1, ChronoUnit.DAYS)))
            }
            true
        } catch (e: Exception) {
            println("Error banning user $userId for violation: ${e.message}")
            false
        }
    }

    fun clearWarnings(userId: Long): Boolean {
        return try {
            Db.execute(
                """
                delete from moderation_warnings where user_id = ?
                """
            ) { stmt ->
                stmt.setLong(1, userId)
            }
            true
        } catch (e: Exception) {
            println("Error clearing warnings for user $userId: ${e.message}")
            false
        }
    }
}
