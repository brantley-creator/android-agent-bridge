package dev.brantley.agentbridge.safety

enum class ConfirmMode {
    OFF,
    WRITES,
    EVERY,
    ;

    val wire: String
        get() = name.lowercase()

    companion object {
        fun fromWire(value: String): ConfirmMode =
            when (value.lowercase()) {
                "off" -> OFF
                "every" -> EVERY
                else -> WRITES
            }
    }
}

data class SafetyConfig(
    val confirmMode: ConfirmMode = ConfirmMode.WRITES,
    val idleTimeoutMin: Int = 10,
    val sessionTimeoutMin: Int = 60,
    val disarmOnLock: Boolean = true,
    val screenshotsEnabled: Boolean = false,
    val removedDefaults: Set<String> = emptySet(),
    val extraDeny: Set<String> = emptySet(),
)

object Policy {
    const val SELF = "dev.brantley.agentbridge"
    const val SETTINGS = "com.android.settings"
    val WRITES = setOf("tap", "type", "swipe", "scroll", "key", "launch", "volume")

    val STARTER_DENY =
        setOf(
            SELF,
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.x8bit.bitwarden",
            "com.onepassword.android",
            "com.lastpass.lpandroid",
            "com.dashlane",
            "keepass2android.keepass2android",
            "keepass2android.keepass2android_nonet",
            "com.google.android.apps.authenticator2",
            "com.azure.authenticator",
            "com.authy.authy",
            "com.google.android.apps.walletnfcrel",
            "com.paypal.android.p2pmobile",
            "com.venmo",
            "com.squareup.cash",
            "com.chase.sig.android",
            "com.wf.wellsfargomobile",
            "com.infonow.bofa",
            "com.usaa.mobile.android.usaa",
        )

    fun isSettings(packageName: String): Boolean =
        packageName == SETTINGS || packageName.startsWith("$SETTINGS.")

    fun isFloor(packageName: String): Boolean {
        if (packageName == SELF) return true
        val managed = packageName.startsWith("com.android.") || packageName.startsWith("com.google.android.")
        if (!managed) return false
        return packageName.contains("permissioncontroller") || packageName.contains("packageinstaller")
    }

    fun isDenied(packageName: String, config: SafetyConfig): Boolean {
        if (packageName.isBlank()) return false
        if (isFloor(packageName)) return true
        if (packageName in config.extraDeny) return true
        if (packageName in config.removedDefaults) return false
        return packageName in STARTER_DENY
    }

    fun displayDeny(config: SafetyConfig): List<Pair<String, Boolean>> {
        val floor = STARTER_DENY.filter(::isFloor)
        val editable = ((STARTER_DENY - floor.toSet()) - config.removedDefaults) + config.extraDeny
        return (floor.map { it to true } + editable.map { it to false })
            .distinctBy { it.first }
            .sortedBy { it.first }
    }

    fun needsConfirm(
        method: String,
        foreground: String,
        launchTarget: String?,
        config: SafetyConfig,
        grantUntil: Long,
        now: Long,
    ): Boolean {
        if (method == "status") return false
        val write = method in WRITES
        val settings = isSettings(foreground) || (launchTarget != null && isSettings(launchTarget))
        if (settings) return true
        val granted = write && now < grantUntil
        return when (config.confirmMode) {
            ConfirmMode.OFF -> false
            ConfirmMode.EVERY -> !granted
            ConfirmMode.WRITES -> write && !granted
        }
    }
}
