package dev.brantley.agentbridge.a11y

data class VolumeLevel(
    val index: Int,
    val max: Int,
    val percent: Int,
)

/** Round a 0–100 percent onto a stream's index range. 0 and 100 land on the ends. */
internal fun mediaVolumeIndex(percent: Int, maxIndex: Int): Int {
    if (maxIndex <= 0) return 0
    val clamped = percent.coerceIn(0, 100)
    return ((clamped.toLong() * maxIndex + 50) / 100).toInt()
}

/** Percent the rounded index actually represents, rounded half up. */
internal fun mediaVolumePercent(index: Int, maxIndex: Int): Int {
    if (maxIndex <= 0) return 0
    val clamped = index.coerceIn(0, maxIndex)
    return ((clamped.toLong() * 100 + maxIndex / 2) / maxIndex).toInt()
}
