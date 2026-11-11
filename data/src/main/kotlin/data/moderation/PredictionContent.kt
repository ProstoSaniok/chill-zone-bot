package data.moderation

import java.sql.Connection

/** Prediction-specific validation and publication adapter for the shared queue. */
object PredictionContent : ModeratedContent {
    override val kind = "prediction"
    val rarities = listOf("Обычное", "Редкое", "Эпическое", "Легендарное")
    override fun validate(text: String, metadata: String) {
        require(text.isNotBlank() && text.length <= 1000) { "Введите текст от 1 до 1000 символов." }
        require(metadata in rarities) { "Выберите редкость из списка." }
    }
    override fun publish(connection: Connection, submission: Submission, metadata: String): Long {
        validate(submission.text, metadata)
        return connection.prepareStatement(
            "insert into predictions(text, rarity, author_id) values (?, ?, ?) returning id"
        ).use { stmt ->
            stmt.setString(1, submission.text)
            stmt.setString(2, metadata)
            stmt.setLong(3, submission.authorId)
            stmt.executeQuery().use { it.next(); it.getLong(1) }
        }
    }
}
