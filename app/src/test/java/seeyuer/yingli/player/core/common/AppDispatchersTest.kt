package seeyuer.yingli.player.core.common

import seeyuer.yingli.player.testing.MainDispatcherRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppDispatchersTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `virtual time deterministically advances main dispatcher state`() =
        runTest(mainDispatcherRule.dispatcher) {
            val states = mutableListOf("idle")

            launch(Dispatchers.Main) {
                states += "running"
                delay(1_000)
                states += "complete"
            }

            runCurrent()
            assertEquals(listOf("idle", "running"), states)

            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(listOf("idle", "running", "complete"), states)
        }
}
