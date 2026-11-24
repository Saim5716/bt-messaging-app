package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val DarkColorScheme = darkColorScheme(
    primary = SignalMint,
    onPrimary = SignalMintDark,
    primaryContainer = SignalMintContainer,
    onPrimaryContainer = SignalMint,
    secondary = MeshCyan,
    onSecondary = Color(0xFF002538),
    secondaryContainer = MeshCyanContainer,
    onSecondaryContainer = MeshCyan,
    tertiary = RelayAmber,
    onTertiary = Color(0xFF2B1C00),
    tertiaryContainer = RelayAmberContainer,
    onTertiaryContainer = RelayAmber,
    background = MeshObsidian,
    onBackground = TextPrimaryDark,
    surface = MeshSlateSurface,
    onSurface = TextPrimaryDark,
    surfaceVariant = MeshSlateContainer,
    onSurfaceVariant = TextSecondaryDark,
    surfaceContainerHigh = MeshSlateHigh,
    outline = OutlineDark,
    error = AlertCoral,
    onError = Color.White,
    errorContainer = AlertCoralContainer,
    onErrorContainer = AlertCoral
)

private val LightColorScheme = lightColorScheme(
    primary = EmeraldPrimaryLight,
    onPrimary = Color.White,
    primaryContainer = EmeraldContainerLight,
    onPrimaryContainer = Color(0xFF003826),
    secondary = CyanSecondaryLight,
    onSecondary = Color.White,
    secondaryContainer = CyanContainerLight,
    onSecondaryContainer = Color(0xFF002744),
    tertiary = Color(0xFFB26A00),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE6B8),
    onTertiaryContainer = Color(0xFF3A2200),
    background = MeshMistLight,
    onBackground = TextPrimaryLight,
    surface = MeshCardLight,
    onSurface = TextPrimaryLight,
    surfaceVariant = MeshCardContainerLight,
    onSurfaceVariant = TextSecondaryLight,
    outline = OutlineLight,
    error = Color(0xFFD32F2F),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002)
)

val MeshShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun OfflineMeshTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val scheme = if (darkTheme || systemDark) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = scheme,
        typography = AppTypography,
        shapes = MeshShapes,
        content = content
    )
}
