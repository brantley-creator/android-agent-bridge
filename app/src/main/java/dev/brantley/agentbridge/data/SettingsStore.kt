package dev.brantley.agentbridge.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.brantley.agentbridge.safety.ConfirmMode
import dev.brantley.agentbridge.safety.SafetyConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "agent_bridge_settings")

data class AppSettings(
    val relayUrl: String = "",
    val room: String = "",
    val paired: Boolean = false,
    val confirmMode: String = "writes",
    val idleTimeoutMin: Int = 10,
    val sessionTimeoutMin: Int = 60,
    val disarmOnLock: Boolean = true,
    val screenshotsEnabled: Boolean = false,
    val removedDefaults: Set<String> = emptySet(),
    val extraDeny: Set<String> = emptySet(),
)

fun AppSettings.toSafety(): SafetyConfig =
    SafetyConfig(
        confirmMode = ConfirmMode.fromWire(confirmMode),
        idleTimeoutMin = idleTimeoutMin.coerceIn(1, 60),
        sessionTimeoutMin = sessionTimeoutMin.coerceIn(5, 240),
        disarmOnLock = disarmOnLock,
        screenshotsEnabled = screenshotsEnabled,
        removedDefaults = removedDefaults,
        extraDeny = extraDeny,
    )

class SettingsStore(private val context: Context) {
    private val relayUrlKey = stringPreferencesKey("relay_url")
    private val roomKey = stringPreferencesKey("room")
    private val pairedKey = booleanPreferencesKey("paired")
    private val confirmModeKey = stringPreferencesKey("confirm_mode")
    private val idleKey = intPreferencesKey("idle_timeout_min")
    private val sessionKey = intPreferencesKey("session_timeout_min")
    private val disarmOnLockKey = booleanPreferencesKey("disarm_on_lock")
    private val screenshotsKey = booleanPreferencesKey("screenshots_enabled")
    private val removedKey = stringSetPreferencesKey("removed_defaults")
    private val extraKey = stringSetPreferencesKey("extra_deny")

    val settings: Flow<AppSettings> =
        context.settingsDataStore.data.map { prefs ->
            AppSettings(
                relayUrl = prefs[relayUrlKey].orEmpty(),
                room = prefs[roomKey].orEmpty(),
                paired = prefs[pairedKey] ?: false,
                confirmMode = prefs[confirmModeKey] ?: "writes",
                idleTimeoutMin = prefs[idleKey] ?: 10,
                sessionTimeoutMin = prefs[sessionKey] ?: 60,
                disarmOnLock = prefs[disarmOnLockKey] ?: true,
                screenshotsEnabled = prefs[screenshotsKey] ?: false,
                removedDefaults = prefs[removedKey] ?: emptySet(),
                extraDeny = prefs[extraKey] ?: emptySet(),
            )
        }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.settingsDataStore.edit { prefs ->
            val current =
                AppSettings(
                    relayUrl = prefs[relayUrlKey].orEmpty(),
                    room = prefs[roomKey].orEmpty(),
                    paired = prefs[pairedKey] ?: false,
                    confirmMode = prefs[confirmModeKey] ?: "writes",
                    idleTimeoutMin = prefs[idleKey] ?: 10,
                    sessionTimeoutMin = prefs[sessionKey] ?: 60,
                    disarmOnLock = prefs[disarmOnLockKey] ?: true,
                    screenshotsEnabled = prefs[screenshotsKey] ?: false,
                    removedDefaults = prefs[removedKey] ?: emptySet(),
                    extraDeny = prefs[extraKey] ?: emptySet(),
                )
            val next = transform(current)
            prefs[relayUrlKey] = next.relayUrl
            prefs[roomKey] = next.room
            prefs[pairedKey] = next.paired
            prefs[confirmModeKey] = next.confirmMode
            prefs[idleKey] = next.idleTimeoutMin.coerceIn(1, 60)
            prefs[sessionKey] = next.sessionTimeoutMin.coerceIn(5, 240)
            prefs[disarmOnLockKey] = next.disarmOnLock
            prefs[screenshotsKey] = next.screenshotsEnabled
            prefs[removedKey] = next.removedDefaults
            prefs[extraKey] = next.extraDeny
        }
    }
}
