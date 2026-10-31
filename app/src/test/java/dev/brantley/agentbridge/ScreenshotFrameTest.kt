package dev.brantley.agentbridge

import dev.brantley.agentbridge.a11y.maxPngBytes
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotFrameTest {
    @Test
    fun pngBudgetStaysInsideTheOneMebibyteFrame() {
        val png = maxPngBytes()
        val base64 = ((png + 2) / 3) * 4
        assertTrue(png > 500_000)
        assertTrue(base64 + 512 <= 1024 * 1024)
    }
}
