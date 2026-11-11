package data.moderation

import data.schemas.ContentModerationSchema
import data.schemas.PredictionsSchema
import org.junit.Assume.assumeTrue
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class PredictionContentTest {
    @Test fun validatesTextAndRarity() {
        assertFailsWith<IllegalArgumentException> { PredictionContent.validate(" ", "Редкое") }
        assertFailsWith<IllegalArgumentException> { PredictionContent.validate("a".repeat(1001), "Редкое") }
        assertFailsWith<IllegalArgumentException> { PredictionContent.validate("Привет", "неизвестное") }
        PredictionContent.validate("<b>Текст остаётся текстом</b>", "Обычное")
    }
}

/** Opt-in PostgreSQL tests. Every test uses a private schema; production tables are untouched. */
class ContentModerationTest {
    private var schema: String? = null
    private fun connection(): Connection = DriverManager.getConnection(
        System.getenv("DATABASE_URL"), System.getenv("DATABASE_USER"), System.getenv("DATABASE_PASSWORD")
    )
    private fun scoped(): Connection = connection().also { c ->
        c.createStatement().use { it.execute("set search_path to ${requireNotNull(schema)}") }
    }
    private fun service(handlers: List<ModeratedContent> = listOf(PredictionContent)) = ContentModerationService(
        handlers, ::scoped, { it == 2L || it == 3L }, { it == 4L }, { it.contains("flag") }
    )
    @BeforeTest fun setup() {
        assumeTrue("Set MODERATION_INTEGRATION=true to run PostgreSQL tests", System.getenv("MODERATION_INTEGRATION") == "true")
        schema = "moderation_test_" + UUID.randomUUID().toString().replace("-", "")
        connection().use { c -> c.createStatement().use { it.execute("create schema $schema") } }
        scoped().use { c -> c.createStatement().use {
            it.execute("create table users(user_id bigint primary key)")
            it.execute("insert into users values (1), (2), (3), (4)")
            it.execute(PredictionsSchema.createPredictionsTable())
            it.execute(ContentModerationSchema.createSql)
        } }
    }
    @AfterTest fun cleanup() {
        schema?.let { name -> connection().use { c -> c.createStatement().use { it.execute("drop schema $name cascade") } } }
    }
    private fun publishedCount(): Int = scoped().use { c -> c.createStatement().use { s ->
        s.executeQuery("select count(*) from predictions").use { it.next(); it.getInt(1) }
    } }
    private fun submit(author: Long = 1) = service().submit(author, "prediction", "flag <b>Удачный день</b>", "Редкое", UUID.randomUUID())

    @Test fun lifecycleAndIdempotencySurviveNewServiceInstance() {
        val key = UUID.randomUUID()
        val first = service().submit(1, "prediction", "flag Текст", "Редкое", key)
        assertEquals(first.id, service().submit(1, "prediction", "flag Текст", "Редкое", key).id)
        assertTrue(first.flagged)
        assertEquals(0, publishedCount())
        val approved = service().decide(2, first.id, true, "Эпическое")
        assertEquals(SubmissionStatus.APPROVED, approved.status)
        assertEquals("Редкое", approved.proposedMetadata)
        assertEquals("Эпическое", approved.approvedMetadata)
        assertEquals(2L, approved.reviewerId)
        assertNotNull(approved.reviewedAt)
        assertNull(approved.rejectionReason)
        assertEquals(1, publishedCount())
        assertEquals(approved, service().get(1, first.id, false))
        assertFailsWith<IllegalArgumentException> { service().decide(3, first.id, true, "Обычное") }
        assertEquals(1, publishedCount())
    }

    @Test fun rejectionIsFinalAndDoesNotPublish() {
        val item = submit()
        assertFailsWith<IllegalArgumentException> { service().decide(2, item.id, false, reason = " ") }
        val rejected = service().decide(2, item.id, false, reason = " Реклама ")
        assertEquals("Реклама", rejected.rejectionReason)
        assertNull(rejected.publishedId)
        assertNull(rejected.approvedMetadata)
        assertEquals(0, publishedCount())
        assertFailsWith<IllegalArgumentException> { service().decide(3, item.id, true, "Редкое") }
        assertNotEquals(item.id, submit().id)
    }

    @Test fun permissionsOwnershipAndSelfReview() {
        val item = submit()
        assertFailsWith<IllegalArgumentException> { service().get(2, item.id, false) }
        assertFailsWith<IllegalArgumentException> { service().get(1, item.id, true) }
        assertFailsWith<IllegalArgumentException> { service().list(1, "prediction", true, null, 0) }
        assertFailsWith<IllegalArgumentException> { service().decide(1, item.id, true, "Редкое") }
        val own = submit(2)
        assertFailsWith<IllegalArgumentException> { service().decide(2, own.id, true, "Редкое") }
        assertFailsWith<IllegalArgumentException> { submit(4) }
        assertEquals(1, service().list(1, "prediction", false, null, 0).size)
    }

    @Test fun paginationAndFilters() {
        repeat(7) { submit() }
        assertEquals(6, service().list(2, "prediction", true, SubmissionStatus.PENDING, 0).size)
        assertEquals(2, service().list(2, "prediction", true, SubmissionStatus.PENDING, 1).size)
        assertTrue(service().list(1, "prediction", false, SubmissionStatus.APPROVED, 0).isEmpty())
        assertEquals(7, service().pendingCount(2, "prediction"))
        val first = service().nextPending(2, "prediction", 0)!!
        val next = service().nextPending(2, "prediction", first.id)!!
        assertTrue(next.id > first.id)
        service().decide(2, next.id, false, reason = "Дубликат")
        assertTrue(service().nextPending(2, "prediction", first.id)!!.id > next.id)
    }

    @Test fun concurrentDecisionsPublishExactlyOnce() {
        val item = submit()
        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val tasks = listOf(2L, 3L).map { reviewer -> pool.submit(Callable {
                barrier.await(10, TimeUnit.SECONDS)
                runCatching { service().decide(reviewer, item.id, true, "Редкое") }.isSuccess
            }) }
            assertEquals(1, tasks.count { it.get(15, TimeUnit.SECONDS) })
            assertEquals(1, publishedCount())
        } finally { pool.shutdownNow() }
    }

    @Test fun roleRevokedAfterOpeningCardCannotDecide() {
        var allowed = true
        val changingRoles = ContentModerationService(listOf(PredictionContent), ::scoped,
            { allowed && it == 2L }, { false }, { false })
        val item = submit()
        changingRoles.get(2, item.id, true)
        allowed = false
        assertFailsWith<IllegalArgumentException> { changingRoles.decide(2, item.id, true, "Обычное") }
        assertEquals(0, publishedCount())
        assertEquals(SubmissionStatus.PENDING, service().get(1, item.id, false).status)
    }

    @Test fun approvalRacingRejectionHasOneFinalDecision() {
        val item = submit()
        val barrier = CyclicBarrier(2)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val tasks = listOf(true, false).map { approve -> pool.submit(Callable {
                barrier.await(10, TimeUnit.SECONDS)
                runCatching { service().decide(if (approve) 2 else 3, item.id, approve, "Редкое", "Реклама") }.isSuccess
            }) }
            assertEquals(1, tasks.count { it.get(15, TimeUnit.SECONDS) })
            val result = service().get(1, item.id, false)
            assertEquals(if (result.status == SubmissionStatus.APPROVED) 1 else 0, publishedCount())
            assertNotEquals(SubmissionStatus.PENDING, result.status)
        } finally { pool.shutdownNow() }
    }

    @Test fun publisherFailureRollsBackAndAnotherContentAdapterWorks() {
        val adapter = object : ModeratedContent {
            override val kind = "another_service"
            override fun validate(text: String, metadata: String) = Unit
            override fun publish(connection: Connection, submission: Submission, metadata: String): Long {
                PredictionContent.publish(connection, submission, "Обычное")
                error("simulated publication failure")
            }
        }
        val custom = service(listOf(adapter))
        val item = custom.submit(1, adapter.kind, "Текст", "some metadata", UUID.randomUUID())
        assertFailsWith<IllegalStateException> { custom.decide(2, item.id, true, "final metadata") }
        assertEquals(0, publishedCount())
        assertEquals(SubmissionStatus.PENDING, custom.get(1, item.id, false).status)
        assertEquals(SubmissionStatus.REJECTED, custom.decide(2, item.id, false, reason = "Причина").status)
    }
}
