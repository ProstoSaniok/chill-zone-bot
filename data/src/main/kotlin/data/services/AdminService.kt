package data.services

import data.Db
import data.models.AccountType
import data.models.*
import io.github.cdimascio.dotenv.dotenv
import java.time.Duration
import java.time.ZoneOffset

object AdminService {
    private val env = dotenv()
    private val url = (env["DATABASE_URL"] ?: System.getenv("DATABASE_URL") ?: error("DATABASE_URL is not set")).trim()
    private val user = (env["DATABASE_USER"] ?: System.getenv("DATABASE_USER") ?: "").trim()
    private val pass = (env["DATABASE_PASSWORD"] ?: System.getenv("DATABASE_PASSWORD") ?: "").trim()

    val adminIds: List<Long> =
            env["ADMINS_ID"]?.removeSurrounding("[", "]")?.split(",")?.mapNotNull {
                it.trim().toLongOrNull()
            }
                    ?: emptyList()

    fun isAdmin(userId: Long): Boolean {
        return getAccountType(userId) == AccountType.ADMIN
    }

    fun isModerator(userId: Long): Boolean {
        return when (getAccountType(userId)) {
            AccountType.MODERATOR, AccountType.ADMIN -> true
            else -> false
        }
    }

    fun isBootstrapAdmin(userId: Long): Boolean {
        return userId in adminIds
    }

    fun resolveAccountType(userId: Long, storedType: AccountType): AccountType {
        return if (isBootstrapAdmin(userId)) AccountType.ADMIN else storedType
    }

    fun getAccountType(userId: Long): AccountType {
        if (isBootstrapAdmin(userId)) return AccountType.ADMIN
        return try {
            Db.single(
                    "select coalesce(account_type, 'user') as account_type from users where user_id = ?",
                    bind = { stmt -> stmt.setLong(1, userId) },
                    map = { rs -> AccountType.fromDb(rs.getString("account_type")) }
            ) ?: AccountType.USER
        } catch (e: Exception) {
            println("Error getting account type for user $userId: ${e.message}")
            AccountType.USER
        }
    }

    fun setAccountType(userId: Long, accountType: AccountType): Boolean {
        return try {
            Db.execute(
                    "update users set account_type = ?, updated_at = now() where user_id = ?"
            ) { stmt ->
                stmt.setString(1, accountType.dbValue)
                stmt.setLong(2, userId)
            }
            true
        } catch (e: Exception) {
            println("Error setting account type for user $userId: ${e.message}")
            false
        }
    }

    fun toggleModeratorUserType(userId: Long): AccountType {
        val currentType = getAccountType(userId)
        val nextType = when (currentType) {
            AccountType.MODERATOR -> AccountType.USER
            AccountType.USER -> AccountType.MODERATOR
            AccountType.ADMIN -> AccountType.ADMIN
        }
        setAccountType(userId, nextType)
        return nextType
    }

    fun toggleAdminType(userId: Long): AccountType {
        val currentType = getAccountType(userId)
        val nextType = if (currentType == AccountType.ADMIN) AccountType.USER else AccountType.ADMIN
        setAccountType(userId, nextType)
        return nextType
    }

    fun reloadAdmins(): List<Long> {
        val envValue = env["ADMINS_ID"]
        val newAdminIds =
                envValue?.removeSurrounding("[", "]")?.split(",")?.mapNotNull {
                    it.trim().toLongOrNull()
                }
                        ?: emptyList()
        return newAdminIds
    }

    fun banUser(userId: Long, reason: String? = null): Boolean {
        return try {
            Db.execute(
                    """
                insert into banned_users (user_id, reason)
                values (?, ?)
                on conflict (user_id) do update set reason = excluded.reason, banned_at = now()
                """
            ) { stmt ->
                stmt.setLong(1, userId)
                stmt.setString(2, reason)
            }
            true
        } catch (e: Exception) {
            println("Error banning user $userId: ${e.message}")
            false
        }
    }

    fun unbanUser(userId: Long): Boolean {
        return try {
            Db.execute(
                    """
                delete from banned_users where user_id = ?
                """
            ) { stmt -> stmt.setLong(1, userId) }
            true
        } catch (e: Exception) {
            println("Error unbanning user $userId: ${e.message}")
            false
        }
    }

    fun setBanReason(userId: Long, reason: String): Boolean {
        return try {
            Db.execute(
                    """
                update banned_users set reason = ?, banned_at = now() where user_id = ?
                """
            ) { stmt ->
                stmt.setString(1, reason)
                stmt.setLong(2, userId)
            }
            true
        } catch (e: Exception) {
            println("Error setting ban reason for user $userId: ${e.message}")
            false
        }
    }

    fun isUserBanned(userId: Long): Boolean {
        return try {
            Db.single(
                    """
                select count(*) as count
                from banned_users
                where user_id = ?
                  and (ban_expires_at is null or ban_expires_at > now())
                """,
                    bind = { stmt -> stmt.setLong(1, userId) },
                    map = { rs -> rs.getInt("count") > 0 }
            )
                    ?: false
        } catch (e: Exception) {
            println("Error checking ban status for user $userId: ${e.message}")
            false
        }
    }

    fun searchUsers(query: String, limit: Int = 10): List<UserProfile> {
        return try {
            val normalizedQuery = query.trim()
            val usernameQuery = normalizedQuery.removePrefix("@")
            val usernameSearchPattern = "%${usernameQuery.lowercase()}%"
            val displayNameSearchPattern = "%${normalizedQuery.lowercase()}%"
            Db.query(
                    """
                select u.user_id, u.username, u.display_name, u.bio, u.hidden, u.show_media, u.rating, u.hide_username, u.account_type,
                       case when bu.user_id is not null then true else false end as is_banned
                from users u
                left join banned_users bu on u.user_id = bu.user_id
                where cast(u.user_id as text) = ? 
                   or lower(u.username) like ? 
                   or lower(u.display_name) like ?
                order by u.user_id asc
                limit ?
                """,
                    bind = { stmt ->
                        stmt.setString(1, normalizedQuery) // Для точного совпадения ID
                        stmt.setString(2, usernameSearchPattern) // Для частичного совпадения username
                        stmt.setString(3, displayNameSearchPattern) // Для частичного совпадения display_name
                        stmt.setInt(4, limit)
                    },
                    map = { rs ->
                        UserProfile(
                                userId = rs.getLong("user_id"),
                                username = rs.getString("username") ?: "",
                                displayName = rs.getString("display_name") ?: "",
                                bio = rs.getString("bio") ?: "",
                                hidden = rs.getBoolean("hidden"),
                                showMedia = rs.getBoolean("show_media"),
                                rating = rs.getInt("rating"),
                                hideUsername = rs.getBoolean("hide_username"),
                                isBanned = rs.getBoolean("is_banned"),
                                accountType = resolveAccountType(
                                    rs.getLong("user_id"),
                                    AccountType.fromDb(rs.getString("account_type"))
                                )
                        )
                    }
            )
        } catch (e: Exception) {
            println("Error searching users with query '$query': ${e.message}")
            emptyList()
        }
    }

    fun getBannedUsers(limit: Int = 20): List<BannedUser> {
        return try {
            Db.query(
                    """
                select bu.user_id, u.username, u.display_name, bu.banned_at, bu.reason, bu.ban_expires_at
                from banned_users bu
                join users u on bu.user_id = u.user_id
                where (bu.ban_expires_at is null or bu.ban_expires_at > now())
                order by bu.banned_at desc
                limit ?
                """,
                    bind = { stmt -> stmt.setInt(1, limit) },
                    map = { rs ->
                        BannedUser(
                                userId = rs.getLong("user_id"),
                                username = rs.getString("username") ?: "",
                                displayName = rs.getString("display_name") ?: "",
                                bannedAt = rs.getTimestamp("banned_at").toString(),
                                reason = rs.getString("reason"),
                                banExpiresAt = rs.getString("ban_expires_at")
                        )
                    }
            )
        } catch (e: Exception) {
            println("Error getting banned users: ${e.message}")
            emptyList()
        }
    }

    fun getAllUsers(limit: Int = 20, offset: Int = 0): List<UserProfile> {
        return try {
            Db.query(
                    """
                select u.user_id, u.username, u.display_name, u.bio, u.hidden, u.show_media, u.rating, u.hide_username, u.account_type,
                       case when bu.user_id is not null then true else false end as is_banned
                from users u
                left join banned_users bu on u.user_id = bu.user_id
                  order by u.user_id asc
                limit ? offset ?
                """,
                    bind = { stmt ->
                        stmt.setInt(1, limit)
                        stmt.setInt(2, offset)
                    },
                    map = { rs ->
                        UserProfile(
                                userId = rs.getLong("user_id"),
                                username = rs.getString("username") ?: "",
                                displayName = rs.getString("display_name") ?: "",
                                bio = rs.getString("bio") ?: "",
                                hidden = rs.getBoolean("hidden"),
                                showMedia = rs.getBoolean("show_media"),
                                rating = rs.getInt("rating"),
                                hideUsername = rs.getBoolean("hide_username"),
                                isBanned = rs.getBoolean("is_banned"),
                                accountType = resolveAccountType(
                                    rs.getLong("user_id"),
                                    AccountType.fromDb(rs.getString("account_type"))
                                )
                        )
                    }
            )
        } catch (e: Exception) {
            println("Error getting all users: ${e.message}")
            emptyList()
        }
    }

    fun getUsersCount(): Int {
        return try {
            Db.single(
                    """
                select count(*) as count from users
                """,
                    bind = {},
                    map = { rs -> rs.getInt("count") }
            )
                    ?: 0
        } catch (e: Exception) {
            println("Error getting users count: ${e.message}")
            0
        }
    }

    fun getBannedUsersCount(): Int {
        return try {
            Db.single(
                    """
                select count(*) as count from banned_users
                """,
                    bind = {},
                    map = { rs -> rs.getInt("count") }
            )
                    ?: 0
        } catch (e: Exception) {
            println("Error getting banned users count: ${e.message}")
            0
        }
    }

    fun getActiveUsersCountNow(minutesThreshold: Int = 3): Int {
        return try {
            Db.single(
                    """
                select count(*) as count from users 
                where last_activity_at > now() - interval '$minutesThreshold minutes'
                """,
                    bind = {},
                    map = { rs -> rs.getInt("count") }
            )
                    ?: 0
        } catch (e: Exception) {
            println("Error getting active users count: ${e.message}")
            0
        }
    }

    fun setBanExpiry(userId: Long, days: Int): Boolean {
        return try {
            Db.execute(
                    """
                update banned_users 
                set ban_expires_at = now() + interval '$days days'
                where user_id = ?
                """
            ) { stmt -> stmt.setLong(1, userId) }
            true
        } catch (e: Exception) {
            println("Error setting ban expiry for user $userId: ${e.message}")
            false
        }
    }

    fun addBanDays(userId: Long, days: Int): Boolean {
        return try {
            // ensure row exists
            Db.execute(
                    """
                insert into banned_users (user_id)
                values (?)
                on conflict (user_id) do nothing
                """
            ) { stmt -> stmt.setLong(1, userId) }

            val updated =
                    Db.execute(
                            """
                update banned_users
                set ban_expires_at = coalesce(ban_expires_at, now()) + interval '$days days'
                where user_id = ?
                """
                    ) { stmt -> stmt.setLong(1, userId) }
            updated > 0
        } catch (e: Exception) {
            println("Error adding ban days for user $userId: ${e.message}")
            false
        }
    }

    data class BanExactTime(
            val serverNowIsoUtc: String,
            val banExpiresAtIsoUtc: String?,
            val remainingText: String,
            val username: String,
            val bannedAtIsoUtc: String?
    )

    fun getBanExactTime(userId: Long): BanExactTime? {
        return try {
            Db.single(
                    """
                select bu.ban_expires_at, now() as server_now, u.username, bu.banned_at
                from banned_users bu
                join users u on bu.user_id = u.user_id
                where bu.user_id = ?
                """.trimIndent(),
                    bind = { stmt -> stmt.setLong(1, userId) },
                    map = { rs ->
                        val serverNow =
                                rs.getTimestamp("server_now").toInstant().atOffset(ZoneOffset.UTC)

                        val expiresInstant = rs.getTimestamp("ban_expires_at")?.toInstant()
                        val expires = expiresInstant?.atOffset(ZoneOffset.UTC)

                        val bannedAtInstant = rs.getTimestamp("banned_at")?.toInstant()
                        val bannedAt = bannedAtInstant?.atOffset(ZoneOffset.UTC)

                        val remainingText =
                                if (expiresInstant == null) {
                                    "∞"
                                } else {
                                    val remaining =
                                            Duration.between(serverNow.toInstant(), expiresInstant)
                                    if (remaining.isNegative || remaining.isZero) {
                                        "0"
                                    } else {
                                        val totalSeconds = remaining.seconds
                                        val days = totalSeconds / 86400
                                        val hours = (totalSeconds % 86400) / 3600
                                        val minutes = (totalSeconds % 3600) / 60
                                        val seconds = totalSeconds % 60
                                        "${days}д ${hours}ч ${minutes}м ${seconds}с"
                                    }
                                }

                        BanExactTime(
                                serverNowIsoUtc = serverNow.toString(),
                                banExpiresAtIsoUtc = expires?.toString(),
                                remainingText = remainingText,
                                username = rs.getString("username") ?: "",
                                bannedAtIsoUtc = bannedAt?.toString()
                        )
                    }
            )
        } catch (e: Exception) {
            println("Error getting exact ban time for user $userId: ${e.message}")
            null
        }
    }

    fun removeBanExpiry(userId: Long): Boolean {
        return try {
            Db.execute(
                    """
                update banned_users 
                set ban_expires_at = null
                where user_id = ?
                """
            ) { stmt -> stmt.setLong(1, userId) }
            true
        } catch (e: Exception) {
            println("Error removing ban expiry for user $userId: ${e.message}")
            false
        }
    }

    fun setBanTemporary(userId: Long): Boolean {
        return try {
            val updated =
                    Db.execute(
                            """
                insert into banned_users (user_id, ban_expires_at)
                values (?, now() + interval '10 days')
                on conflict (user_id) do update
                set ban_expires_at = excluded.ban_expires_at
                where banned_users.ban_expires_at is null or banned_users.ban_expires_at < now()
                """
                    ) { stmt -> stmt.setLong(1, userId) }
            updated > 0
        } catch (e: Exception) {
            println("Error setting ban temporary for user $userId: ${e.message}")
            false
        }
    }

    fun addBanHours(userId: Long, hours: Int): Boolean {
        return try {
            // ensure row exists
            Db.execute(
                    """
                insert into banned_users (user_id)
                values (?)
                on conflict (user_id) do nothing
                """
            ) { stmt -> stmt.setLong(1, userId) }

            val updated =
                    Db.execute(
                            """
                update banned_users
                set ban_expires_at = coalesce(ban_expires_at, now()) + interval '$hours hours'
                where user_id = ?
                """
                    ) { stmt -> stmt.setLong(1, userId) }
            updated > 0
        } catch (e: Exception) {
            println("Error adding ban hours for user $userId: ${e.message}")
            false
        }
    }

    fun setBanExactDuration(userId: Long, days: Int, hours: Int, minutes: Int): Boolean {
        return try {
            // ensure row exists
            Db.execute(
                    """
                insert into banned_users (user_id)
                values (?)
                on conflict (user_id) do nothing
                """.trimIndent()
            ) { stmt -> stmt.setLong(1, userId) }

            val updated =
                    Db.execute(
                            """
                update banned_users
                set ban_expires_at = now() + make_interval(days => ?, hours => ?, mins => ?)
                where user_id = ?
                """.trimIndent()
                    ) { stmt ->
                        stmt.setInt(1, days)
                        stmt.setInt(2, hours)
                        stmt.setInt(3, minutes)
                        stmt.setLong(4, userId)
                    }
            updated > 0
        } catch (e: Exception) {
            println("Error setting exact ban duration for user $userId: ${e.message}")
            false
        }
    }

    data class ExpiredBan(val userId: Long, val username: String, val displayName: String)

    fun getExpiredBans(): List<ExpiredBan> {
        return try {
            Db.query(
                    """
                select bu.user_id, u.username, u.display_name
                from banned_users bu
                join users u on bu.user_id = u.user_id
                where bu.ban_expires_at is not null and bu.ban_expires_at <= now()
                """,
                    map = { rs ->
                        ExpiredBan(
                                userId = rs.getLong("user_id"),
                                username = rs.getString("username") ?: "",
                                displayName = rs.getString("display_name") ?: ""
                        )
                    }
            )
        } catch (e: Exception) {
            println("Error getting expired bans: ${e.message}")
            emptyList()
        }
    }
}
