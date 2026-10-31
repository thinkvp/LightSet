package app.lightset.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

/** Panel behind the exposure section, a step up from the page. */
val PanelGrey = Color(0xFF161618)

/** Values the exposure calculator worked out (unlocked settings). */
val SolvedBlue = Color(0xFF8AB4FF)

/**
 * Neutral greys with an off-white accent: colour on screen is kept for what it means
 * (light colours, gauges, status), since a tinted UI skews how colours are judged.
 */
private val Colors = darkColorScheme(
    primary = Color(0xFFECECEC),
    onPrimary = Color(0xFF0E0E0F),
    primaryContainer = Color(0xFF2A2A2D),
    onPrimaryContainer = Color(0xFFECECEC),
    secondary = Color(0xFFBDBDC2),
    onSecondary = Color(0xFF0E0E0F),
    secondaryContainer = Color(0xFF2A2A2D),
    onSecondaryContainer = Color(0xFFECECEC),
    tertiary = SolvedBlue,
    background = Color(0xFF0E0E0F),
    onBackground = Color(0xFFECECEC),
    surface = Color(0xFF0E0E0F),
    onSurface = Color(0xFFECECEC),
    surfaceVariant = Color(0xFF1F1F22),
    onSurfaceVariant = Color(0xFF8D8D92),
    surfaceContainerLowest = Color(0xFF0E0E0F),
    surfaceContainerLow = Color(0xFF151517),
    surfaceContainer = Color(0xFF161618),
    surfaceContainerHigh = Color(0xFF1A1A1C),
    surfaceContainerHighest = Color(0xFF222225),
    outline = Color(0xFF3A3A3E),
    outlineVariant = Color(0xFF2A2A2D),
)

/** Tabular digits everywhere, so values don't jitter as they change. */
private fun TextStyle.tabular() = copy(fontFeatureSettings = "tnum")

private val Type = Typography().run {
    copy(
        displayLarge = displayLarge.tabular(), displayMedium = displayMedium.tabular(), displaySmall = displaySmall.tabular(),
        headlineLarge = headlineLarge.tabular(), headlineMedium = headlineMedium.tabular(), headlineSmall = headlineSmall.tabular(),
        titleLarge = titleLarge.tabular(), titleMedium = titleMedium.tabular(), titleSmall = titleSmall.tabular(),
        bodyLarge = bodyLarge.tabular(), bodyMedium = bodyMedium.tabular(), bodySmall = bodySmall.tabular(),
        labelLarge = labelLarge.tabular(), labelMedium = labelMedium.tabular(), labelSmall = labelSmall.tabular(),
    )
}

@Composable
fun LightSetTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = Colors, typography = Type, content = content)
