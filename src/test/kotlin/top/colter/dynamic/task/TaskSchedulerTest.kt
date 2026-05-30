package top.colter.dynamic.task

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import top.colter.dynamic.core.task.TaskDefinition
import top.colter.dynamic.core.task.TaskSchedule
import top.colter.dynamic.core.task.TaskScheduler
import top.colter.dynamic.core.task.TaskStatus

@OptIn(ExperimentalCoroutinesApi::class)
class TaskSchedulerTest {
    @Test
    fun oneShotTaskRunsOnceAndCompletes(): Unit = runTest {
        val clock = MutableClock()
        val scheduler = taskScheduler(clock)
        val counter = AtomicInteger(0)

        assertTrue(
            scheduler.start(
                TaskDefinition(
                    id = "one",
                    schedule = TaskSchedule.Once,
                    action = { counter.incrementAndGet() },
                    retryBackoff = 10.milliseconds,
                )
            )
        )
        runCurrent()

        assertEquals(1, counter.get())
        val snapshot = assertNotNull(scheduler.snapshot("one"))
        assertEquals(TaskStatus.COMPLETED, snapshot.status)
        assertEquals(1, snapshot.runCount)
        assertEquals(clock.millis(), snapshot.lastRunAtMillis)
        assertEquals(clock.millis(), snapshot.lastSuccessAtMillis)
        assertNull(snapshot.nextRunAtMillis)
        assertNull(snapshot.lastErrorSummary)
    }

    @Test
    fun fixedDelayRunsImmediatelyAndThenAfterDelay(): Unit = runTest {
        val clock = MutableClock()
        val scheduler = taskScheduler(clock)
        val counter = AtomicInteger(0)

        scheduler.start(
            TaskDefinition(
                id = "interval",
                schedule = TaskSchedule.FixedDelay(50.milliseconds),
                action = { counter.incrementAndGet() },
                retryBackoff = 10.milliseconds,
            )
        )
        runCurrent()

        assertEquals(1, counter.get())
        assertEquals(clock.millis() + 50, scheduler.snapshot("interval")?.nextRunAtMillis)

        advanceTimeBy(49)
        runCurrent()
        assertEquals(1, counter.get())

        advanceTimeBy(1)
        runCurrent()
        assertEquals(2, counter.get())
        scheduler.stop("interval")
    }

    @Test
    fun fixedDelayCanSkipImmediateRun(): Unit = runTest {
        val clock = MutableClock()
        val scheduler = taskScheduler(clock)
        val counter = AtomicInteger(0)

        scheduler.start(
            TaskDefinition(
                id = "interval",
                schedule = TaskSchedule.FixedDelay(50.milliseconds, runImmediately = false),
                action = { counter.incrementAndGet() },
                retryBackoff = 10.milliseconds,
            )
        )
        runCurrent()

        assertEquals(0, counter.get())
        assertEquals(clock.millis() + 50, scheduler.snapshot("interval")?.nextRunAtMillis)

        advanceTimeBy(50)
        runCurrent()
        assertEquals(1, counter.get())
        scheduler.stop("interval")
    }

    @Test
    fun cronSchedulesNextWallClockRun(): Unit = runTest {
        val clock = MutableClock(Instant.parse("2026-01-01T00:00:59.950Z"))
        val scheduler = taskScheduler(clock)
        val counter = AtomicInteger(0)

        scheduler.start(
            TaskDefinition(
                id = "cron",
                schedule = TaskSchedule.Cron("* * * * *", ZoneOffset.UTC),
                action = { counter.incrementAndGet() },
                retryBackoff = 10.milliseconds,
            )
        )
        runCurrent()

        assertEquals(0, counter.get())
        assertEquals(Instant.parse("2026-01-01T00:01:00Z").toEpochMilli(), scheduler.snapshot("cron")?.nextRunAtMillis)

        advanceTimeBy(50)
        runCurrent()
        assertEquals(1, counter.get())
        scheduler.stop("cron")
    }

    @Test
    fun oneShotFailuresRetryUntilSuccess(): Unit = runTest {
        val clock = MutableClock()
        val scheduler = taskScheduler(clock)
        val counter = AtomicInteger(0)

        scheduler.start(
            TaskDefinition(
                id = "retry-once",
                schedule = TaskSchedule.Once,
                action = {
                    if (counter.incrementAndGet() == 1) error("first round failed")
                },
                retryBackoff = 10.milliseconds,
            )
        )
        runCurrent()

        val failedSnapshot = assertNotNull(scheduler.snapshot("retry-once"))
        assertEquals(TaskStatus.RUNNING, failedSnapshot.status)
        assertEquals(1, failedSnapshot.runCount)
        assertEquals("first round failed", failedSnapshot.lastErrorSummary)
        assertEquals(clock.millis() + 10, failedSnapshot.nextRunAtMillis)

        advanceTimeBy(10)
        runCurrent()

        val completedSnapshot = assertNotNull(scheduler.snapshot("retry-once"))
        assertEquals(TaskStatus.COMPLETED, completedSnapshot.status)
        assertEquals(2, counter.get())
        assertEquals(2, completedSnapshot.runCount)
        assertNull(completedSnapshot.lastErrorSummary)
        assertEquals(clock.millis(), completedSnapshot.lastSuccessAtMillis)
    }

    @Test
    fun concurrentStartOnlyStartsOneRuntime(): Unit = runTest {
        val clock = MutableClock()
        val scheduler = taskScheduler(clock)
        val starts = AtomicInteger(0)
        val task = TaskDefinition(
            id = "same",
            schedule = TaskSchedule.FixedDelay(100.milliseconds),
            action = {},
            retryBackoff = 10.milliseconds,
        )

        repeat(20) {
            launch {
                if (scheduler.start(task)) {
                    starts.incrementAndGet()
                }
            }
        }
        runCurrent()

        assertEquals(1, starts.get())
        assertTrue(scheduler.isRunning("same"))
        scheduler.stop("same")
    }

    @Test
    fun stopCancelsRunningTaskAndAllowsRestart(): Unit = runTest {
        val clock = MutableClock()
        val scheduler = taskScheduler(clock)
        val firstCounter = AtomicInteger(0)
        val secondCounter = AtomicInteger(0)

        scheduler.start(
            TaskDefinition(
                id = "restart",
                schedule = TaskSchedule.FixedDelay(100.milliseconds),
                action = { firstCounter.incrementAndGet() },
                retryBackoff = 10.milliseconds,
            )
        )
        runCurrent()

        assertTrue(scheduler.stop("restart"))
        assertFalse(scheduler.isRunning("restart"))
        assertEquals(TaskStatus.CANCELLED, scheduler.snapshot("restart")?.status)

        assertTrue(
            scheduler.start(
                TaskDefinition(
                    id = "restart",
                    schedule = TaskSchedule.FixedDelay(100.milliseconds),
                    action = { secondCounter.incrementAndGet() },
                    retryBackoff = 10.milliseconds,
                )
            )
        )
        runCurrent()

        assertEquals(1, firstCounter.get())
        assertEquals(1, secondCounter.get())
        assertTrue(scheduler.isRunning("restart"))
        scheduler.stop("restart")
    }

    @Test
    fun stopAllCancelsRunningTasksAndSnapshotsAreSorted(): Unit = runTest {
        val clock = MutableClock()
        val scheduler = taskScheduler(clock)

        scheduler.start(
            TaskDefinition(
                id = "b",
                schedule = TaskSchedule.FixedDelay(100.milliseconds),
                action = {},
                retryBackoff = 10.milliseconds,
            )
        )
        scheduler.start(
            TaskDefinition(
                id = "a",
                schedule = TaskSchedule.FixedDelay(100.milliseconds),
                action = {},
                retryBackoff = 10.milliseconds,
            )
        )
        runCurrent()

        scheduler.stopAll()

        assertEquals(listOf("a", "b"), scheduler.snapshots().map { it.id })
        assertTrue(scheduler.snapshots().all { it.status == TaskStatus.CANCELLED })
    }

    @Test
    fun definitionsAndSchedulesValidateInputs() {
        assertFailsWith<IllegalArgumentException> {
            TaskDefinition(
                id = "",
                schedule = TaskSchedule.Once,
                action = {},
                retryBackoff = 10.milliseconds,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TaskDefinition(
                id = "bad-backoff",
                schedule = TaskSchedule.Once,
                action = {},
                retryBackoff = 0.milliseconds,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TaskSchedule.FixedDelay(0.milliseconds)
        }
        assertFailsWith<IllegalArgumentException> {
            TaskSchedule.Cron("bad expression")
        }
    }

    @Test
    fun cronCanCalculateNextRunFromGivenTime() {
        val cron = TaskSchedule.Cron("* * * * *", ZoneOffset.UTC)
        val now = ZonedDateTime.parse("2026-01-01T00:00:30Z")

        assertEquals(30_000, cron.timeToNextRun(now).inWholeMilliseconds)
        assertEquals(ZonedDateTime.parse("2026-01-01T00:01:00Z"), cron.nextRunAfter(now))
    }

    private fun TestScope.taskScheduler(clock: MutableClock): TaskScheduler {
        return DefaultTaskScheduler(
            scope = backgroundScope,
            clock = clock,
            delayProvider = { duration ->
                delay(duration)
                clock.advance(duration)
            },
        )
    }

    private class MutableClock(
        private var current: Instant = Instant.parse("2026-01-01T00:00:00Z"),
        private val clockZone: ZoneId = ZoneOffset.UTC,
    ) : Clock() {
        override fun getZone(): ZoneId = clockZone

        override fun withZone(zone: ZoneId): Clock {
            return MutableClock(current, zone)
        }

        override fun instant(): Instant = current

        fun advance(duration: Duration) {
            current = current.plusMillis(duration.inWholeMilliseconds)
        }
    }
}
