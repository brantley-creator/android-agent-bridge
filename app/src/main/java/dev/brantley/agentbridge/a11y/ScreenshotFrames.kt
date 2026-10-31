package dev.brantley.agentbridge.a11y

/** Same cap the relay enforces. Frames over this are dropped and the socket is closed. */
internal const val MAX_SCREENSHOT_FRAME_BYTES = 1024 * 1024

/**
 * Largest PNG that still fits in a JSON frame after base64 and a small envelope.
 * The relay limit stays at 1 MiB. The phone shrinks the image instead of raising it.
 */
internal fun maxPngBytes(maxFrameBytes: Int = MAX_SCREENSHOT_FRAME_BYTES, overhead: Int = 512): Int {
    val budget = (maxFrameBytes - overhead).coerceAtLeast(0)
    return (budget / 4) * 3
}
