package data.repositories

import data.Db
import data.models.*
import java.sql.ResultSet

class TestRepository {
    fun createTest(title: String, authorId: Long?, authorName: String? = null): Long {
        return Db.insertId("insert into tests(title, author_id, author_name) values (?, ?, ?)") { stmt ->
            stmt.setString(1, title)
            if (authorId == null) stmt.setObject(2, null) else stmt.setLong(2, authorId)
            stmt.setString(3, authorName)
        }
    }
    
    fun createTestFull(title: String, prompt: String, kind: String, answer: String, options: List<String>, authorId: Long? = null): Long {
        val testId = createTest(title, authorId)
        addQuestion(testId, 0, QuestionKind.valueOf(kind.uppercase()), prompt, options, answer)
        return testId
    }

    fun addQuestion(testId: Long, position: Int, kind: QuestionKind, prompt: String, options: List<String>, answer: String) {
        Db.execute(
            "insert into test_questions(test_id, position, kind, prompt, options_text, answer_text) values (?, ?, ?, ?, ?, ?)"
        ) { stmt ->
            stmt.setLong(1, testId)
            stmt.setInt(2, position)
            stmt.setString(3, kind.name)
            stmt.setString(4, prompt)
            stmt.setString(5, options.joinToString("|||"))
            stmt.setString(6, answer)
        }
    }

    private val summarySql = """
        select t.id, t.title, t.author_id, t.author_name, count(q.id)::integer as question_count
        from tests t left join test_questions q on q.test_id = t.id
    """.trimIndent()

    private fun summary(rs: ResultSet): TestSummary {
        val authorId = rs.getLong("author_id").let { if (rs.wasNull()) null else it }
        return TestSummary(rs.getLong("id"), rs.getString("title"), authorId,
            rs.getString("author_name"), rs.getInt("question_count"))
    }

    fun listTests(authorId: Long? = null): List<TestSummary> = Db.query(
        summarySql + (if (authorId == null) "" else " where t.author_id = ?") +
            " group by t.id order by t.created_at desc",
        bind = { if (authorId != null) it.setLong(1, authorId) },
        map = ::summary
    )

    fun getTest(testId: Long): TestSummary? {
        return Db.single(
            "$summarySql where t.id = ? group by t.id",
            bind = { stmt -> stmt.setLong(1, testId) },
            map = ::summary
        )
    }

    fun questions(testId: Long): List<TestQuestion> {
        return Db.query(
            "select id, test_id, position, kind, prompt, options_text, answer_text from test_questions where test_id = ? order by position asc, id asc",
            bind = { stmt -> stmt.setLong(1, testId) },
            map = { rs ->
                TestQuestion(
                    id = rs.getLong("id"),
                    testId = rs.getLong("test_id"),
                    position = rs.getInt("position"),
                    kind = QuestionKind.valueOf(rs.getString("kind")),
                    prompt = rs.getString("prompt"),
                    options = rs.getString("options_text")?.split("|||")?.filter { it.isNotBlank() } ?: emptyList(),
                    answer = rs.getString("answer_text") ?: ""
                )
            }
        )
    }

    fun randomTest(): TestSummary? {
        return Db.single(
            "$summarySql group by t.id having count(q.id) > 0 order by random() limit 1",
            map = ::summary
        )
    }

    fun createTestWithQuestions(title: String, authorId: Long, authorName: String,
                                questions: List<NewTestQuestion>): Long {
        require(questions.size in 1..20)
        val testId = createTest(title, authorId, authorName)
        try {
            questions.forEachIndexed { index, question ->
                addQuestion(testId, index, question.kind, question.prompt, question.options, question.answer)
            }
        } catch (failure: Exception) {
            Db.execute("delete from tests where id = ?") { it.setLong(1, testId) }
            throw failure
        }
        return testId
    }

    fun createRun(userId: Long, testId: Long): Long {
        return Db.insertId("insert into test_runs(user_id, test_id) values (?, ?)") { stmt ->
            stmt.setLong(1, userId)
            stmt.setLong(2, testId)
        }
    }

    fun getRun(runId: Long): Triple<Long, Long, Int>? {
        return Db.single(
            "select user_id, test_id, current_index, correct, finished from test_runs where id = ?",
            bind = { stmt -> stmt.setLong(1, runId) },
            map = { rs -> Triple(rs.getLong("user_id"), rs.getLong("test_id"), rs.getInt("current_index")) }
        )
    }

    fun updateRun(runId: Long, currentIndex: Int, correct: Int, finished: Boolean = false) {
        Db.execute(
            """
            update test_runs
            set current_index = ?, correct = ?, finished = ?, finished_at = case when ? then now() else finished_at end
            where id = ?
            """
        ) { stmt ->
            stmt.setInt(1, currentIndex)
            stmt.setInt(2, correct)
            stmt.setBoolean(3, finished)
            stmt.setBoolean(4, finished)
            stmt.setLong(5, runId)
        }
    }

    fun saveAnswer(runId: Long, questionId: Long, answer: String, correct: Boolean) {
        Db.execute(
            "insert into test_answers(run_id, question_id, answer_text, correct) values (?, ?, ?, ?)"
        ) { stmt ->
            stmt.setLong(1, runId)
            stmt.setLong(2, questionId)
            stmt.setString(3, answer)
            stmt.setBoolean(4, correct)
        }
    }

    fun answersForRun(runId: Long): Int {
        return Db.single(
            "select count(*) as c from test_answers where run_id = ?",
            bind = { stmt -> stmt.setLong(1, runId) },
            map = { rs -> rs.getInt("c") }
        ) ?: 0
    }

    fun total(): Int = Db.single("select count(*) as c from tests", map = { it.getInt("c") }) ?: 0
}
