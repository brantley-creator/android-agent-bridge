package dev.brantley.agentbridge

import dev.brantley.agentbridge.pair.PairLanding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PairLandingTest {
    @Test
    fun successfulPairReturnsHomeAndDoesNotArm() {
        val landing = PairLanding.afterSuccess()
        assertEquals("Paired. Tap Arm session to start.", landing.message)
        assertEquals(PairLanding.MESSAGE, landing.message)
        assertFalse(landing.arm)
        assertTrue(landing.finishPairScreen)
    }
}
