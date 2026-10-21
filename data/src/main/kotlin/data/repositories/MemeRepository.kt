package data.repositories

import data.Db
import data.models.*

class MemeRepository {
    fun addMeme(fileId: String, uploaderId: Long?, caption: String = ""): MemeItem {
        val id = Db.insertId(
            "insert into memes(file_id, uploader_id, caption) values (?, ?, ?)"
        ) { stmt ->
            stmt.setString(1, fileId)
            if (uploaderId == null) stmt.setObject(2, null) else stmt.setLong(2, uploaderId)
            stmt.setString(3, caption)
        }
        return MemeItem(id, fileId, uploaderId, caption)
    }

    fun getById(id: Long): MemeItem? {
        return Db.single(
            "select id, file_id, uploader_id, caption from memes where id = ?",
            bind = { stmt -> stmt.setLong(1, id) },
            map = { rs ->
                MemeItem(
                    rs.getLong("id"),
                    rs.getString("file_id"),
                    rs.getObject("uploader_id") as? Long,
                    rs.getString("caption") ?: ""
                )
            }
        )
    }

    fun randomUnseen(userId: Long): MemeItem? {
        fun pickUnseen(): MemeItem? {
            return Db.single(
                """
                select m.id, m.file_id, m.uploader_id, m.caption
                from memes m
                left join meme_history h
                  on h.meme_id = m.id and h.user_id = ?
                where h.meme_id is null
                order by random()
                limit 1
                """,
                bind = { stmt -> stmt.setLong(1, userId) },
                map = { rs ->
                    MemeItem(
                        rs.getLong("id"),
                        rs.getString("file_id"),
                        rs.getObject("uploader_id") as? Long,
                        rs.getString("caption") ?: ""
                    )
                }
            )
        }

        val unseen = pickUnseen()
        if (unseen != null) return unseen

        Db.execute("delete from meme_history where user_id = ?") { stmt -> stmt.setLong(1, userId) }
        return pickUnseen()
    }

    fun markSeen(userId: Long, memeId: Long) {
        Db.execute("insert into meme_history(user_id, meme_id) values (?, ?) on conflict do nothing") { stmt ->
            stmt.setLong(1, userId)
            stmt.setLong(2, memeId)
        }
    }

    fun counts(memeId: Long): MemeVotes {
        val likes = Db.single(
            "select count(*) as c from meme_votes where meme_id = ? and vote = 1",
            bind = { stmt -> stmt.setLong(1, memeId) },
            map = { rs -> rs.getInt("c") }
        ) ?: 0

        val dislikes = Db.single(
            "select count(*) as c from meme_votes where meme_id = ? and vote = -1",
            bind = { stmt -> stmt.setLong(1, memeId) },
            map = { rs -> rs.getInt("c") }
        ) ?: 0

        return MemeVotes(likes, dislikes)
    }

    fun vote(userId: Long, memeId: Long, vote: Int): MemeVotes {
        Db.execute(
            """
            insert into meme_votes(user_id, meme_id, vote)
            values (?, ?, ?)
            on conflict (user_id, meme_id) do update set vote = excluded.vote, created_at = now()
            """
        ) { stmt ->
            stmt.setLong(1, userId)
            stmt.setLong(2, memeId)
            stmt.setInt(3, vote)
        }
        return counts(memeId)
    }

    fun isFavorite(userId: Long, memeId: Long): Boolean = Db.single(
            "select 1 as x from meme_favorites where user_id = ? and meme_id = ?",
            bind = { stmt ->
                stmt.setLong(1, userId)
                stmt.setLong(2, memeId)
            },
            map = { true }
        ) ?: false

    fun userVote(userId: Long, memeId: Long): Int = Db.single(
        "select vote from meme_votes where user_id = ? and meme_id = ?",
        bind = { stmt ->
            stmt.setLong(1, userId)
            stmt.setLong(2, memeId)
        },
        map = { it.getInt("vote") }
    ) ?: 0

    fun toggleFavorite(userId: Long, memeId: Long): Boolean {
        if (isFavorite(userId, memeId)) {
            Db.execute("delete from meme_favorites where user_id = ? and meme_id = ?") { stmt ->
                stmt.setLong(1, userId)
                stmt.setLong(2, memeId)
            }
            return false
        }

        Db.execute("insert into meme_favorites(user_id, meme_id) values (?, ?) on conflict do nothing") { stmt ->
            stmt.setLong(1, userId)
            stmt.setLong(2, memeId)
        }
        return true
    }

    fun favorites(userId: Long): List<MemeItem> {
        return Db.query(
            """
            select m.id, m.file_id, m.uploader_id, m.caption
            from memes m
            join meme_favorites f on f.meme_id = m.id
            where f.user_id = ?
            order by f.created_at desc
            """,
            bind = { stmt -> stmt.setLong(1, userId) },
            map = { rs ->
                MemeItem(
                    rs.getLong("id"),
                    rs.getString("file_id"),
                    rs.getObject("uploader_id") as? Long,
                    rs.getString("caption") ?: ""
                )
            }
        )
    }

    fun total(): Int {
        return Db.single("select count(*) as c from memes", map = { it.getInt("c") }) ?: 0
    }
}
