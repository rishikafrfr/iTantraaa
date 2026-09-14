package isro.itantra.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClockSyncTest {
    @Test
    fun `offset and rtt computed correctly`() {
        val cs = ClockSync()
        val t0 = 1_000_000L
        val t1 = 5_000_500L
        val t3 = 1_001_000L
        val s = cs.add(t0, t1, t3)
        assertEquals(1000L, s.rttUs)
        assertEquals((5000500L - 1000000L + 5000500L - 1001000L) / 2, s.offsetUs)
        assertEquals(1_001_500L, cs.toLocal(5_001_500L))
        assertEquals(500L, cs.uncertaintyUs())
    }

    @Test
    fun `best sample wins sliding window`() {
        val cs = ClockSync()
        cs.add(1_000_000, 5_005_000, 1_010_000)
        cs.add(2_000_000, 6_000_500, 2_001_000)
        assertEquals(1000L, cs.best()!!.rttUs)
    }

    @Test
    fun `ping pong payload round trip`() {
        val cs = ClockSync()
        val ping = cs.pingPayload(42L)
        val pong = cs.pongPayloadFromPing(ping, 99L)!!
        val (t0, t1) = cs.parsePong(pong)!!
        assertEquals(42L, t0)
        assertEquals(99L, t1)
        assertTrue(cs.best() == null)
    }
}
