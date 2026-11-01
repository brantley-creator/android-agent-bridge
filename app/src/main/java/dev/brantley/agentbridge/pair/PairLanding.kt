package dev.brantley.agentbridge.pair

/**
 * Where a successful pair leaves the phone.
 * Pairing stores the device token and nothing else. It does not arm.
 */
object PairLanding {
    const val MESSAGE = "Paired. Tap Arm session to start."
    const val EXTRA_JUST_PAIRED = "dev.brantley.agentbridge.extra.JUST_PAIRED"

    fun afterSuccess(): Result = Result(message = MESSAGE, arm = false, finishPairScreen = true)

    data class Result(
        val message: String,
        val arm: Boolean,
        val finishPairScreen: Boolean,
    )
}
