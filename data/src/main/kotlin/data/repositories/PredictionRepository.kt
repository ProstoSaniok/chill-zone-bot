package data.repositories

import data.Db
import data.models.*
import kotlin.random.Random

class PredictionRepository {
    fun add(text: String, rarity: String, authorId: Long?): PredictionItem {
        val id = Db.insertId("insert into predictions(text, rarity, author_id) values (?, ?, ?)") { stmt ->
            stmt.setString(1, text)
            stmt.setString(2, rarity)
            if (authorId == null) stmt.setObject(3, null) else stmt.setLong(3, authorId)
        }
        return PredictionItem(id, text, rarity, authorId)
    }

    fun randomWeighted(): PredictionItem? {
        val byRarity = listOf(
            "Обычное" to 60,
            "Редкое" to 25,
            "Эпическое" to 10,
            "Легендарное" to 5
        )
        val roll = Random.nextInt(100)
        var acc = 0
        val target = byRarity.firstOrNull { (_, w) ->
            acc += w
            roll < acc
        }?.first ?: "Обычное"

        val chosen = Db.single(
            "select id, text, rarity, author_id from predictions where rarity = ? order by random() limit 1",
            bind = { stmt -> stmt.setString(1, target) },
            map = { rs ->
                PredictionItem(
                    rs.getLong("id"),
                    rs.getString("text"),
                    rs.getString("rarity"),
                    rs.getObject("author_id") as? Long
                )
            }
        )

        return chosen ?: Db.single(
            "select id, text, rarity, author_id from predictions order by random() limit 1",
            map = { rs ->
                PredictionItem(
                    rs.getLong("id"),
                    rs.getString("text"),
                    rs.getString("rarity"),
                    rs.getObject("author_id") as? Long
                )
            }
        )
    }

    fun collect(userId: Long, predictionId: Long) {
        Db.execute("insert into prediction_history(user_id, prediction_id) values (?, ?) on conflict do nothing") { stmt ->
            stmt.setLong(1, userId)
            stmt.setLong(2, predictionId)
        }
    }

    fun listCollected(userId: Long, page: Int = 0): List<PredictionItem> {
        return Db.query(
            """
            select p.id, p.text, p.rarity, p.author_id
            from predictions p
            join prediction_history h on h.prediction_id = p.id
            where h.user_id = ?
            order by h.claimed_at desc, p.id desc
            limit 6 offset ?
            """,
            bind = { stmt ->
                stmt.setLong(1, userId)
                stmt.setInt(2, page.coerceIn(0, 100000) * 5)
            },
            map = { rs ->
                PredictionItem(
                    rs.getLong("id"),
                    rs.getString("text"),
                    rs.getString("rarity"),
                    rs.getObject("author_id") as? Long
                )
            }
        )
    }

    fun search(query: String): List<PredictionItem> {
        val pattern = "%${query.trim()}%"
        return Db.query(
            """
            select id, text, rarity, author_id
            from predictions
            where text ilike ? or rarity ilike ?
            order by id desc
            limit 20
            """,
            bind = { stmt ->
                stmt.setString(1, pattern)
                stmt.setString(2, pattern)
            },
            map = { rs ->
                PredictionItem(
                    rs.getLong("id"),
                    rs.getString("text"),
                    rs.getString("rarity"),
                    rs.getObject("author_id") as? Long
                )
            }
        )
    }

    fun byId(id: Long): PredictionItem? {
        return Db.single(
            "select id, text, rarity, author_id from predictions where id = ?",
            bind = { stmt -> stmt.setLong(1, id) },
            map = { rs ->
                PredictionItem(
                    rs.getLong("id"),
                    rs.getString("text"),
                    rs.getString("rarity"),
                    rs.getObject("author_id") as? Long
                )
            }
        )
    }

    fun total(): Int = Db.single("select count(*) as c from predictions", map = { it.getInt("c") }) ?: 0
}
