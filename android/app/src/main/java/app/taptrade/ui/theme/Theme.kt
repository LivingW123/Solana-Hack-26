package app.taptrade.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Purple = Color(0xFF6C4CF1)
private val Teal = Color(0xFF14C9A2)

@Composable
fun TapTradeTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Color(0xFFB4A5FF), secondary = Teal)
    } else {
        lightColorScheme(primary = Purple, secondary = Teal)
    }
    MaterialTheme(colorScheme = colors, content = content)
}
