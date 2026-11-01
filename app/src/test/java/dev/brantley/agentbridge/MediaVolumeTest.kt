package dev.brantley.agentbridge

import dev.brantley.agentbridge.a11y.mediaVolumeIndex
import dev.brantley.agentbridge.a11y.mediaVolumePercent
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaVolumeTest {
    @Test
    fun percentRoundsOntoTheStreamIndex() {
        assertEquals(0, mediaVolumeIndex(0, 15))
        assertEquals(15, mediaVolumeIndex(100, 15))
        assertEquals(8, mediaVolumeIndex(50, 15))
        assertEquals(0, mediaVolumeIndex(1, 15))
        assertEquals(1, mediaVolumeIndex(4, 15))
        assertEquals(0, mediaVolumeIndex(50, 0))
        assertEquals(0, mediaVolumePercent(0, 15))
        assertEquals(100, mediaVolumePercent(15, 15))
        assertEquals(53, mediaVolumePercent(8, 15))
        assertEquals(40, mediaVolumePercent(6, 15))
    }
}