package seeyuer.yingli.player.domain.processing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessingContractsTest {
    private fun task(state: ProcessingTaskState = ProcessingTaskState.QUEUED) = ProcessingTask(
        ProcessingTaskId("task-1"),
        ProcessingProjectId("project-1"),
        state = state,
        createdAtEpochMillis = 10,
        updatedAtEpochMillis = 10,
    )

    @Test
    fun `normal lifecycle reaches success and event replay is deterministic`() {
        val events = listOf(
            ProcessingTaskEvent.Prepare(11),
            ProcessingTaskEvent.Start(12),
            ProcessingTaskEvent.Progressed(ProcessingProgress("cut", 50, 100), 13),
            ProcessingTaskEvent.Succeed(ProcessingOutput("clip.mp4", "content://output/1"), 14),
        )

        val first = ProcessingTaskReducer.replay(task(), events)
        val second = ProcessingTaskReducer.replay(task(), events)

        assertEquals(first, second)
        assertEquals(ProcessingTaskState.SUCCEEDED, first.state)
        assertEquals("clip.mp4", first.outputDisplayName)
        assertEquals(1, first.attempt)
    }

    @Test
    fun `every state has deterministic recovery behavior`() {
        val expected = mapOf(
            ProcessingTaskState.QUEUED to ProcessingTaskState.QUEUED,
            ProcessingTaskState.PREPARING to ProcessingTaskState.QUEUED,
            ProcessingTaskState.RUNNING to ProcessingTaskState.QUEUED,
            ProcessingTaskState.PAUSED to ProcessingTaskState.PAUSED,
            ProcessingTaskState.CANCELING to ProcessingTaskState.CANCELED,
            ProcessingTaskState.SUCCEEDED to ProcessingTaskState.SUCCEEDED,
            ProcessingTaskState.FAILED to ProcessingTaskState.FAILED,
            ProcessingTaskState.CANCELED to ProcessingTaskState.CANCELED,
        )

        expected.forEach { (state, recovered) ->
            val result = ProcessingTaskReducer.reduce(task(state), ProcessingTaskEvent.Recover(20))
            val value = when (result) {
                is ProcessingReduction.Applied -> result.task
                is ProcessingReduction.Ignored -> result.task
                is ProcessingReduction.Rejected -> result.task
            }
            assertEquals(state.name, recovered, value.state)
        }
    }

    @Test
    fun `terminal states reject cancellation and progress regression`() {
        val succeeded = task(ProcessingTaskState.SUCCEEDED).copy(outputDisplayName = "done.mp4")
        assertTrue(
            ProcessingTaskReducer.reduce(succeeded, ProcessingTaskEvent.RequestCancel(20)) is ProcessingReduction.Rejected,
        )
        val running = task(ProcessingTaskState.RUNNING).copy(progress = ProcessingProgress("cut", 70, 100))
        assertTrue(
            ProcessingTaskReducer.reduce(
                running,
                ProcessingTaskEvent.Progressed(ProcessingProgress("cut", 60, 100), 20),
            ) is ProcessingReduction.Rejected,
        )
    }

    @Test
    fun `unknown total never invents a percentage`() {
        val progress = ProcessingProgress("probe", 12)
        val running = task(ProcessingTaskState.RUNNING).copy(progress = progress)

        assertNull(progress.fraction)
        assertTrue(!ProcessingPresentationMapper.map(running).determinate)
    }

    @Test
    fun `scheduler honors priority capacity battery and storage`() {
        val old = task().copy(id = ProcessingTaskId("old"), priority = 0, createdAtEpochMillis = 1, updatedAtEpochMillis = 1)
        val urgent = task().copy(id = ProcessingTaskId("urgent"), priority = 5)

        assertEquals(urgent, ProcessingSchedulerPolicy.next(listOf(old, urgent), 0, 1, SchedulerConditions()))
        assertNull(ProcessingSchedulerPolicy.next(listOf(urgent), 1, 1, SchedulerConditions()))
        assertNull(ProcessingSchedulerPolicy.next(listOf(urgent), 0, 1, SchedulerConditions(batteryLow = true)))
        assertNull(ProcessingSchedulerPolicy.next(listOf(urgent), 0, 1, SchedulerConditions(storageAvailable = false)))
    }

    @Test
    fun `exhausted foreground service quota stops scheduling new tasks`() {
        val queued = task()
        val conditions = SchedulerConditions(foregroundServiceUnavailable = true)

        assertNull(ProcessingSchedulerPolicy.next(listOf(queued), 0, 1, conditions))
        // 闸门只挡调度，不改变任务本身——排队中的任务必须原样留着，下次启动接着做。
        assertEquals(ProcessingTaskState.QUEUED, queued.state)
    }

    @Test
    fun `timeout fails running tasks but never demotes a task that already succeeded`() {
        val running = task(ProcessingTaskState.RUNNING)
        val failed = ProcessingTaskReducer.replay(
            running,
            listOf(ProcessingTaskEvent.Fail("FOREGROUND_SERVICE_TIMEOUT", 20)),
        )
        assertEquals(ProcessingTaskState.FAILED, failed.state)
        assertEquals("FOREGROUND_SERVICE_TIMEOUT", failed.errorCode)

        // 这是 InAppProcessingScheduler.failRunning 先取消后写事件的理由：执行器可能已经提交成功，
        // 此时超时事件必须被忽略，而不是把「确实进了媒体库的输出」改写成失败。
        val succeeded = task(ProcessingTaskState.SUCCEEDED).copy(outputDisplayName = "done.mp4")
        val late = ProcessingTaskReducer.replay(
            succeeded,
            listOf(ProcessingTaskEvent.Fail("FOREGROUND_SERVICE_TIMEOUT", 21)),
        )
        assertEquals(ProcessingTaskState.SUCCEEDED, late.state)
        assertNull(late.errorCode)
    }
}
