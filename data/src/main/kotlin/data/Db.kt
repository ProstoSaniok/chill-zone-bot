package data

import io.github.cdimascio.dotenv.dotenv
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement

object Db {
    private val env = dotenv()
    private fun required(name: String): String {
        return (env[name] ?: System.getenv(name) ?: error("$name is not set")).trim()
    }

    private val url = required("DATABASE_URL")
    private val user = required("DATABASE_USER")
    private val pass = required("DATABASE_PASSWORD")

    val connection: Connection by lazy {
        val conn = DriverManager.getConnection(url, user, pass)
        conn
    }

    /** Independent connection for operations that own their transaction. */
    fun openConnection(): Connection = DriverManager.getConnection(url, user, pass)

    fun raw(sql: String) {
        connection.createStatement().use { it.execute(sql) }
    }

    fun execute(sql: String, bind: (PreparedStatement) -> Unit = {}): Int {
        return try {
            connection.prepareStatement(sql).use { stmt ->
                bind(stmt)
                stmt.executeUpdate()
            }
        } catch (e: Exception) {
            println("ERROR: Ошибка выполнения SQL: ${e.message}")
            throw e
        }
    }

    fun insertId(sql: String, bind: (PreparedStatement) -> Unit = {}): Long {
        connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { stmt ->
            bind(stmt)
            stmt.executeUpdate()
            stmt.generatedKeys.use { keys ->
                return if (keys.next()) keys.getLong(1) else 0L
            }
        }
    }

    fun <T> query(sql: String, bind: (PreparedStatement) -> Unit = {}, map: (ResultSet) -> T): List<T> {
        connection.prepareStatement(sql).use { stmt ->
            bind(stmt)
            stmt.executeQuery().use { rs ->
                val out = mutableListOf<T>()
                while (rs.next()) out += map(rs)
                return out
            }
        }
    }

    fun <T> single(sql: String, bind: (PreparedStatement) -> Unit = {}, map: (ResultSet) -> T): T? {
        return query(sql, bind, map).firstOrNull()
    }
}
