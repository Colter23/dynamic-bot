package top.colter.dynamic.task

import java.time.Clock
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import top.colter.dynamic.core.task.TaskDefinition
import top.colter.dynamic.core.task.TaskSchedule
import top.colter.dynamic.core.task.TaskScheduler
import top.colter.dynamic.core.task.TaskSnapshot
import top.colter.dynamic.core.task.TaskStatus
import top.colter.dynamic.core.tools.loggerFor

private val logger = loggerFor<DefaultTaskScheduler>()

public class DefaultTaskScheduler(
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val delayProvider: suspend (Duration) -> Unit = { duration -> delay(duration) },
) : TaskScheduler {
    private val runtimes: ConcurrentHashMap<String, TaskRuntime> = ConcurrentHashMap()

    override fun start(task: TaskDefinition): Boolean {
        val runtime = runtimes.compute(task.id) { _, existing ->
            if (existing?.isRunning() == true) existing else TaskRuntime(task, scope, clock, delayProvider)
        } ?: return false
        return runtime.start()
    }

    override fun start(id: String): Boolean {
        return runtimes[id]?.start() == true
    }

    override suspend fun stop(id: String): Boolean {
        return runtimes[id]?.stop() == true
    }

    override suspend fun restart(id: String): Boolean {
        val runtime = runtimes[id] ?: return false
        runtime.stop()
        return runtime.start()
    }

    override suspend fun stopAll() {
        runtimes.values.toList().forEach { it.stop() }
    }

    override suspend fun shutdown() {
        stopAll()
        runtimes.clear()
    }

    override fun isRunning(id: String): Boolean {
        return runtimes[id]?.isRunning() == true
    }

    override fun snapshot(id: String): TaskSnapshot? {
        return runtimes[id]?.snapshot()
    }

    override fun snapshots(): List<TaskSnapshot> {
        return runtimes.values
            .map { it.snapshot() }
            .sortedBy { it.id }
    }
}

private class TaskRuntime(
    private val task: TaskDefinition,
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val delayProvider: suspend (Duration) -> Unit,
) {
    private val lock: Any = Any()

    @Volatile
    private var status: TaskStatus = TaskStatus.COMPLETED
    @Volatile
    private var nextRunAtMillis: Long? = null
    @Volatile
    private var lastRunAtMillis: Long? = null
    @Volatile
    private var lastSuccessAtMillis: Long? = null
    @Volatile
    private var runCount: Long = 0
    @Volatile
    private var lastErrorSummary: String? = null
    @Volatile
    private var job: Job? = null

    fun start(): Boolean {
        synchronized(lock) {
            if (job?.isActive == true) return false
            status = TaskStatus.RUNNING
            nextRunAtMillis = null
            job = scope.launch(CoroutineName("task-${task.id}")) {
                try {
                    runLoop()
                    finish(TaskStatus.COMPLETED)
                } catch (_: CancellationException) {
                    finish(TaskStatus.CANCELLED)
                } catch (t: Throwable) {
                    recordFailure(t)
                    logger.error(t) { "任务运行异常：taskId=${task.id}" }
                    finish(TaskStatus.FAILED)
                }
            }
            return true
        }
    }

    suspend fun stop(): Boolean {
        val currentJob = synchronized(lock) { job }
        if (currentJob?.isActive != true) return false

        currentJob.cancelAndJoin()
        finish(TaskStatus.CANCELLED)
        return true
    }

    fun isRunning(): Boolean {
        return job?.isActive == true && status == TaskStatus.RUNNING
    }

    fun snapshot(): TaskSnapshot {
        return TaskSnapshot(
            id = task.id,
            name = task.name,
            description = task.description,
            status = status,
            schedule = task.schedule,
            retryBackoffMillis = task.retryBackoff.inWholeMilliseconds,
            nextRunAtMillis = nextRunAtMillis,
            lastRunAtMillis = lastRunAtMillis,
            lastSuccessAtMillis = lastSuccessAtMillis,
            runCount = runCount,
            lastErrorSummary = lastErrorSummary,
        )
    }

    private suspend fun runLoop() {
        when (val schedule = task.schedule) {
            TaskSchedule.Once -> runOnce()
            is TaskSchedule.FixedDelay -> runFixedDelay(schedule)
            is TaskSchedule.Cron -> runCron(schedule)
        }
    }

    private suspend fun runOnce() {
        while (currentCoroutineContext().isActive) {
            if (runRound()) return
            delayFor(task.retryBackoff)
        }
    }

    private suspend fun runFixedDelay(schedule: TaskSchedule.FixedDelay) {
        if (!schedule.runImmediately) {
            delayFor(schedule.delay)
        }

        while (currentCoroutineContext().isActive) {
            val success = runRound()
            delayFor(if (success) schedule.delay else task.retryBackoff)
        }
    }

    private suspend fun runCron(schedule: TaskSchedule.Cron) {
        while (currentCoroutineContext().isActive) {
            delayUntil(schedule.nextRunAtMillis(clock))

            var success = runRound()
            while (!success && currentCoroutineContext().isActive) {
                delayFor(task.retryBackoff)
                success = runRound()
            }
        }
    }

    private suspend fun runRound(): Boolean {
        synchronized(lock) {
            nextRunAtMillis = null
        }

        return try {
            task.action()
            synchronized(lock) {
                lastErrorSummary = null
                lastSuccessAtMillis = clock.millis()
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            recordFailure(t)
            logger.error(t) { "任务执行失败：taskId=${task.id}" }
            false
        } finally {
            synchronized(lock) {
                runCount += 1
                lastRunAtMillis = clock.millis()
            }
        }
    }

    private suspend fun delayFor(duration: Duration) {
        delayUntil(clock.millis() + duration.inWholeMilliseconds.coerceAtLeast(1L))
    }

    private suspend fun delayUntil(runAtMillis: Long) {
        val delayMillis = (runAtMillis - clock.millis()).coerceAtLeast(1L)
        synchronized(lock) {
            nextRunAtMillis = runAtMillis
        }
        delayProvider(delayMillis.milliseconds)
        synchronized(lock) {
            if (nextRunAtMillis == runAtMillis) {
                nextRunAtMillis = null
            }
        }
    }

    private fun TaskSchedule.Cron.nextRunAtMillis(clock: Clock): Long {
        val now = ZonedDateTime.now(clock.withZone(zone))
        return nextRunAfter(now).toInstant().toEpochMilli()
    }

    private fun recordFailure(t: Throwable) {
        synchronized(lock) {
            lastErrorSummary = t.message ?: t::class.simpleName ?: "未知错误"
        }
    }

    private fun finish(finalStatus: TaskStatus) {
        synchronized(lock) {
            status = finalStatus
            nextRunAtMillis = null
            job = null
        }
    }
}
