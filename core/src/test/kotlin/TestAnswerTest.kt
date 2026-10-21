package core

import core.routers.RouterTests
import data.models.QuestionKind
import data.models.TestQuestion
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TestAnswerTest {
    private fun question(answer: String) = TestQuestion(
        id = 1, testId = 1, position = 0, kind = QuestionKind.SINGLE,
        prompt = "Choose", options = listOf("First", "Second"), answer = answer
    )

    @Test fun buttonIndexWorksWithTextualCorrectAnswer() {
        assertTrue(RouterTests.isCorrect(question("Second"), "2"))
        assertFalse(RouterTests.isCorrect(question("Second"), "1"))
    }

    @Test fun buttonAndTypedAnswerWorkWithIndexedCorrectAnswer() {
        assertTrue(RouterTests.isCorrect(question("2"), "2"))
        assertTrue(RouterTests.isCorrect(question("2"), "Second"))
        assertFalse(RouterTests.isCorrect(question("2"), "1"))
    }
}
