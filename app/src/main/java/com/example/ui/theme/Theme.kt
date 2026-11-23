package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Immutable
data class EdgeFlowExtendedColors(
    val statusActive: Color,
    val statusActiveContainer: Color,
    val statusInactive: Color,
    val statusInactiveContainer: Color,
    val cardBorder: Color,
    val dedicatedZoneAccent: Color,
    val dedicatedZoneContainer: Color
)

private val DarkExtendedColors = EdgeFlowExtendedColors(
    statusActive = StatusSageDark,
    statusActiveContainer = StatusSageDark.copy(alpha = 0.12f),
    statusInactive = StatusAmberDark,
    statusInactiveContainer = StatusAmberDark.copy(alpha = 0.12f),
    cardBorder = Color.White.copy(alpha = 0.06f),
    dedicatedZoneAccent = StatusSageDark,
    dedicatedZoneContainer = StatusSageDark.copy(alpha = 0.12f)
)

private val LightExtendedColors = EdgeFlowExtendedColors(
    statusActive = StatusSageLight,
    statusActiveContainer = StatusSageLight.copy(alpha = 0.12f),
    statusInactive = StatusAmberLight,
    statusInactiveContainer = StatusAmberLight.copy(alpha = 0.12f),
    cardBorder = Color.Black.copy(alpha = 0.07f),
    dedicatedZoneAccent = StatusSageLight,
    dedicatedZoneContainer = StatusSageLight.copy(alpha = 0.12f)
)

val LocalEdgeFlowColors = staticCompositionLocalOf { DarkExtendedColors }
val LocalEdgeFlowDarkTheme = staticCompositionLocalOf { true }

private val EdgeFlowDarkColorScheme = darkColorScheme(
    primary = NeutralDarkPrimary,
    onPrimary = NeutralDarkOnPrimary,
    primaryContainer = NeutralDarkPrimaryContainer,
    onPrimaryContainer = NeutralDarkOnPrimaryContainer,
    secondary = NeutralDarkSecondary,
    onSecondary = NeutralDarkOnSecondary,
    secondaryContainer = NeutralDarkSecondaryContainer,
    onSecondaryContainer = NeutralDarkOnSecondaryContainer,
    tertiary = StatusSageDark,
    onTertiary = NeutralDarkOnPrimary,
    tertiaryContainer = StatusSageDark.copy(alpha = 0.14f),
    onTertiaryContainer = StatusSageDark,
    error = StatusErrorDark,
    onError = NeutralDarkBackground,
    errorContainer = StatusErrorDark.copy(alpha = 0.14f),
    onErrorContainer = StatusErrorDark,
    background = NeutralDarkBackground,
    onBackground = NeutralDarkInkPrimary,
    surface = NeutralDarkSurface,
    onSurface = NeutralDarkInkPrimary,
    surfaceVariant = NeutralDarkSurfaceHigh,
    onSurfaceVariant = NeutralDarkInkSecondary,
    surfaceContainerLowest = NeutralDarkBackground,
    surfaceContainerLow = NeutralDarkSurface,
    surfaceContainer = NeutralDarkSurface,
    surfaceContainerHigh = NeutralDarkSurfaceHigh,
    surfaceContainerHighest = NeutralDarkSurfaceHighest,
    outline = NeutralDarkOutline,
    outlineVariant = NeutralDarkOutlineSubtle
)

private val EdgeFlowLightColorScheme = lightColorScheme(
    primary = NeutralLightPrimary,
    onPrimary = NeutralLightOnPrimary,
    primaryContainer = NeutralLightPrimaryContainer,
    onPrimaryContainer = NeutralLightOnPrimaryContainer,
    secondary = NeutralLightSecondary,
    onSecondary = NeutralLightOnSecondary,
    secondaryContainer = NeutralLightSecondaryContainer,
    onSecondaryContainer = NeutralLightOnSecondaryContainer,
    tertiary = StatusSageLight,
    onTertiary = NeutralLightSurface,
    tertiaryContainer = StatusSageLight.copy(alpha = 0.12f),
    onTertiaryContainer = StatusSageLight,
    error = StatusErrorLight,
    onError = NeutralLightSurface,
    errorContainer = StatusErrorLight.copy(alpha = 0.12f),
    onErrorContainer = StatusErrorLight,
    background = NeutralLightBackground,
    onBackground = NeutralLightInkPrimary,
    surface = NeutralLightSurface,
    onSurface = NeutralLightInkPrimary,
    surfaceVariant = NeutralLightSurfaceHigh,
    onSurfaceVariant = NeutralLightInkSecondary,
    surfaceContainerLowest = NeutralLightSurface,
    surfaceContainerLow = NeutralLightSurface,
    surfaceContainer = NeutralLightSurfaceHigh,
    surfaceContainerHigh = NeutralLightSurfaceHigh,
    surfaceContainerHighest = NeutralLightSurfaceHighest,
    outline = NeutralLightOutline,
    outlineVariant = NeutralLightOutlineSubtle
)

private val EdgeFlowShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun EdgeFlowTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) {
                dynamicDarkColorScheme(context).copy(
                    background = NeutralDarkBackground,
                    surface = NeutralDarkSurface,
                    surfaceContainerLow = NeutralDarkSurface,
                    surfaceContainer = NeutralDarkSurface,
                    outlineVariant = NeutralDarkOutlineSubtle
                )
            } else {
                dynamicLightColorScheme(context).copy(
                    background = NeutralLightBackground,
                    surface = NeutralLightSurface,
                    surfaceContainerLow = NeutralLightSurface,
                    outlineVariant = NeutralLightOutlineSubtle
                )
            }
        }

        darkTheme -> EdgeFlowDarkColorScheme
        else -> EdgeFlowLightColorScheme
    }

    val extendedColors = if (darkTheme) DarkExtendedColors else LightExtendedColors

    CompositionLocalProvider(
        LocalEdgeFlowColors provides extendedColors,
        LocalEdgeFlowDarkTheme provides darkTheme
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = EdgeFlowShapes,
            content = content
        )
    }
}
