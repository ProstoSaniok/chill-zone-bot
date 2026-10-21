package core

import core.routers.RouterTests
import data.models.QuestionKind
import data.models.TestQuestion
import data.models.NewTestQuestion
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

    @Test fun draftQuestionsValidateOptionsAndNumbers() {
        val single = RouterTests.parseQuestion(QuestionKind.SINGLE, "Цвет?", "Красный | Синий | 2")
        assertTrue(single == NewTestQuestion(QuestionKind.SINGLE, "Цвет?", listOf("Красный", "Синий"), "2"))
        assertTrue(RouterTests.parseQuestion(QuestionKind.MULTI, "Цвета?", "Красный | Синий | 1,2") != null)
        assertTrue(RouterTests.parseQuestion(QuestionKind.MULTI, "Цвета?", "Красный | Синий | 1,3") == null)
        assertTrue(RouterTests.parseQuestion(QuestionKind.NUMBER, "Сколько?", "abc") == null)
    }

    @Test fun textAndColorAnswersAreChecked() {
        val text = question("Привет").copy(kind = QuestionKind.TEXT)
        assertTrue(RouterTests.isCorrect(text, " привет "))
        val color = question("красный=red;синий=blue").copy(kind = QuestionKind.COLOR)
        assertTrue(RouterTests.isCorrect(color, "синий=blue;красный=red"))
        assertFalse(RouterTests.isCorrect(color, "синий=green;красный=red"))
        assertTrue(RouterTests.parseQuestion(QuestionKind.COLOR, "Перевод", "красный=red;синий=blue") != null)
        assertTrue(RouterTests.parseQuestion(QuestionKind.COLOR, "Перевод", "красный=;синий=blue") == null)
    }
}
