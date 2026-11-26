package com.liveStream.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val BgDark = Color(0xFF0A0B0F)
val SurfaceCard = Color(0xFF14161E)
val SurfaceElevated = Color(0xFF1C1F2A)
val BorderSubtle = Color(0x1FFFFFFF)
val AccentRed = Color(0xFFFE2C55)
val AccentGold = Color(0xFFFFC857)
val TextPrimary = Color(0xFFF5F6FA)
val TextSecondary = Color(0xFFA0A5B5)

private val LiveColorScheme = darkColorScheme(
    primary = AccentRed,
    secondary = AccentGold,
    background = BgDark,
    surface = SurfaceCard,
    onPrimary = Color.White,
    onBackground = TextPrimary,
    onSurface = TextPrimary
)

@Composable
fun LiveStreamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LiveColorScheme,
        content = content
    )
}
