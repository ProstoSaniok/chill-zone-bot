package data.models

enum class QuestionKind {
    SINGLE, MULTI, NUMBER, TEXT, MATCH, COLOR
}

data class NewTestQuestion(
    val kind: QuestionKind,
    val prompt: String,
    val options: List<String>,
    val answer: String
)

data class TestQuestion(
    val id: Long,
    val testId: Long,
    val position: Int,
    val kind: QuestionKind,
    val prompt: String,
    val options: List<String>,
    val answer: String
)

data class TestSummary(
    val id: Long,
    val title: String,
    val authorId: Long?,
    val authorName: String? = null,
    val questionCount: Int = 0
)
