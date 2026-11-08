package app.taptrade.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Solana-inspired palette. Always dark: consistent on every device and on video. */
object Brand {
    val Purple = Color(0xFF9945FF)
    val Green = Color(0xFF14F195)
    val Blue = Color(0xFF00C2FF)
    val Red = Color(0xFFFF5C7A)
    val Amber = Color(0xFFFFB547)

    val Background = Color(0xFF0B0C10)
    val Surface = Color(0xFF15171F)
    val SurfaceHigh = Color(0xFF1E212C)
    val Outline = Color(0xFF2C3040)
    val TextMuted = Color(0xFF9AA0B4)

    /** The purple → green accent used for the wordmark, avatars and highlights. */
    val Gradient = Brush.linearGradient(listOf(Purple, Green))
}

private val colors = darkColorScheme(
    primary = Brand.Purple,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2A1A4D),
    onPrimaryContainer = Color(0xFFE4D4FF),
    secondary = Brand.Green,
    onSecondary = Color(0xFF00241A),
    secondaryContainer = Color(0xFF0D3A2C),
    onSecondaryContainer = Brand.Green,
    tertiary = Brand.Blue,
    background = Brand.Background,
    onBackground = Color(0xFFF2F3F7),
    surface = Brand.Background,
    onSurface = Color(0xFFF2F3F7),
    surfaceVariant = Brand.SurfaceHigh,
    onSurfaceVariant = Brand.TextMuted,
    surfaceContainerLowest = Brand.Background,
    surfaceContainerLow = Brand.Surface,
    surfaceContainer = Brand.Surface,
    surfaceContainerHigh = Brand.SurfaceHigh,
    surfaceContainerHighest = Brand.SurfaceHigh,
    outline = Brand.Outline,
    outlineVariant = Brand.Outline,
    error = Brand.Red,
    onError = Color.White,
    errorContainer = Color(0xFF3A1420),
    onErrorContainer = Color(0xFFFFD9DF),
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val base = Typography()
private val typography = base.copy(
    displayMedium = base.displayMedium.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp),
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
)

@Composable
fun TapTradeTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, shapes = shapes, typography = typography, content = content)
}
