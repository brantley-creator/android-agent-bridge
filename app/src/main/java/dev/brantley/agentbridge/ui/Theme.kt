package dev.brantley.agentbridge.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

private val Ink = Color(0xFF101614)
private val Surface = Color(0xFF1A2420)
private val Amber = Color(0xFFE2B15A)
private val Mist = Color(0xFFE7F0EA)
private val Danger = Color(0xFFE15A4A)

private val Colors =
    darkColorScheme(
        primary = Amber,
        onPrimary = Ink,
        background = Ink,
        onBackground = Mist,
        surface = Surface,
        onSurface = Mist,
        error = Danger,
        onError = Ink,
    )

@Composable
fun AgentBridgeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors) {
        // MaterialTheme does not set a content color. Without this surface,
        // text stays black on the dark window.
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Colors.background,
            contentColor = Colors.onBackground,
            content = content,
        )
    }
}
