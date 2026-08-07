package seeyuer.yingli.player.core.foundation

import seeyuer.yingli.player.testing.AppContainerFixtureBuilder
import seeyuer.yingli.player.testing.FakeAppClock
import seeyuer.yingli.player.testing.SequenceIdGenerator
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AppContainerTest {
    @Test
    fun `constructor assembly replaces stable dependencies without framework objects`() {
        val clock = FakeAppClock(Instant.parse("2030-01-01T00:00:00Z"))
        val ids = SequenceIdGenerator(next = 7)

        val container = AppContainerFixtureBuilder().apply {
            this.clock = clock
            idGenerator = ids
        }.build()

        assertSame(clock, container.clock)
        assertEquals("test-id-7", container.idGenerator.newId())
        assertEquals(Instant.parse("2030-01-01T00:00:00Z"), container.clock.now())
    }
}
