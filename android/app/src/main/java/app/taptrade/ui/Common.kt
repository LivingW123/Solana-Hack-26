package app.taptrade.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.taptrade.data.Explorer
import app.taptrade.data.ListingStatus
import app.taptrade.data.Reputation
import app.taptrade.data.shortAddress
import app.taptrade.ui.theme.Brand

/** "TapTrade" wordmark for top bars. */
@Composable
fun BrandTitle() {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.onBackground)) { append("Tap") }
            withStyle(SpanStyle(color = Brand.Green)) { append("Trade") }
        },
        fontWeight = FontWeight.ExtraBold,
        fontSize = 22.sp,
    )
}

/** Wallet chip for top bars: connect button, or a green-dot address pill (tap to disconnect). */
@Composable
fun WalletAction(address: String?, connecting: Boolean, onConnect: () -> Unit, onDisconnect: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp)) {
        when {
            connecting -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            address == null -> Button(onClick = onConnect) { Text("Connect wallet") }
            else -> OutlinedButton(onClick = onDisconnect, contentPadding = PaddingValues(horizontal = 14.dp)) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(Brand.Green))
                Spacer(Modifier.width(8.dp))
                Text(shortAddress(address))
            }
        }
    }
}

/** Small rounded label with a tinted background. */
@Composable
fun Pill(text: String, color: Color) {
    Text(
        text,
        color = color,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
fun StatusChip(status: ListingStatus) {
    when (status) {
        ListingStatus.Open -> Pill("Open", Brand.Green)
        ListingStatus.Funded -> Pill("In escrow", Brand.Purple)
        ListingStatus.Closed -> Pill("Sold", Brand.TextMuted)
        ListingStatus.Cancelled -> Pill("Cancelled", Brand.TextMuted)
    }
}

private val avatarGradients = listOf(
    Brush.linearGradient(listOf(Brand.Purple, Brand.Green)),
    Brush.linearGradient(listOf(Brand.Blue, Brand.Purple)),
    Brush.linearGradient(listOf(Brand.Green, Brand.Blue)),
    Brush.linearGradient(listOf(Brand.Amber, Brand.Red)),
    Brush.linearGradient(listOf(Brand.Purple, Brand.Red)),
)

/** Gradient circle with the item's first letter; the gradient varies per title. */
@Composable
fun ItemAvatar(title: String, size: Int = 44) {
    val gradient = avatarGradients[Math.floorMod(title.hashCode(), avatarGradients.size)]
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(gradient),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            title.trim().take(1).uppercase().ifEmpty { "?" },
            color = Color.White,
            fontWeight = FontWeight.ExtraBold,
            fontSize = (size * 0.42).sp,
        )
    }
}

/** Big centered result: ✓ Paid / ✓ Released / ✗ Blocked. */
@Composable
fun StatusHero(symbol: String, title: String, subtitle: String?, color: Color) {
    Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.16f))
                .border(2.dp, color, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            // Vector marks so ✓ and ✕ match in weight; other symbols fall back to text.
            when (symbol) {
                "✓", "✕" -> Canvas(Modifier.size(44.dp)) {
                    val w = size.width
                    val stroke = Stroke(width = w * 0.13f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                    val path = Path().apply {
                        if (symbol == "✓") {
                            moveTo(w * 0.14f, w * 0.54f); lineTo(w * 0.40f, w * 0.78f); lineTo(w * 0.86f, w * 0.24f)
                        } else {
                            moveTo(w * 0.2f, w * 0.2f); lineTo(w * 0.8f, w * 0.8f)
                            moveTo(w * 0.8f, w * 0.2f); lineTo(w * 0.2f, w * 0.8f)
                        }
                    }
                    drawPath(path, color, style = stroke)
                }
                else -> Text(symbol, color = color, fontSize = 48.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.displayMedium, color = color, textAlign = TextAlign.Center)
        subtitle?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        }
    }
}

/** One explainer rule: colored ✓/✗ badge, rule, detail. */
@Composable
fun CheckLine(ok: Boolean, rule: String, detail: String) {
    val color = if (ok) Brand.Green else Brand.Red
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.padding(top = 2.dp).size(20.dp).clip(CircleShape).background(color.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (ok) "✓" else "✕", color = color, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
        }
        Column(Modifier.weight(1f)) {
            Text(rule, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun ErrorText(message: String?) {
    if (message != null) {
        Text(
            message,
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.errorContainer)
                .padding(12.dp),
        )
    }
}

/** Highlighted success/info banner. */
@Composable
fun Banner(text: String, color: Color = Brand.Green) {
    Text(
        text,
        color = color,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(color.copy(alpha = 0.12f))
            .padding(12.dp),
    )
}

val Gap = Arrangement.spacedBy(12.dp)

/** Opens Solana Explorer (devnet): anything the app claims can be checked there. */
@Composable
fun ExplorerLink(label: String, url: String) {
    val uri = LocalUriHandler.current
    Text(
        "$label ↗",
        style = MaterialTheme.typography.labelMedium,
        color = Brand.Blue,
        modifier = Modifier.clickable { runCatching { uri.openUri(url) } }.padding(vertical = 4.dp),
    )
}

/** Thin rounded progress bar (no Material stop-dot). */
@Composable
fun ScoreBar(fraction: Float, color: Color) {
    Box(
        Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(6.dp).clip(RoundedCornerShape(50)).background(color))
    }
}

/** Card with the app's surface color. */
@Composable
fun SurfaceCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        shape = MaterialTheme.shapes.large,
    ) { content() }
}

private fun levelColor(level: String) = when (level) {
    "Trusted" -> Brand.Green
    "Building" -> Brand.Blue
    "Caution" -> Brand.Red
    else -> Brand.Amber
}

/**
 * On-chain reputation: counters from the wallet's `Reputation` PDA, a score from an
 * explainable formula (shown), and a one-line summary.
 */
@Composable
fun ReputationCard(title: String, wallet: String?, rep: Reputation?) {
    var showFormula by remember { mutableStateOf(false) }
    SurfaceCard {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (rep == null) {
                Text("Unavailable", style = MaterialTheme.typography.bodyMedium)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("${rep.score}", style = MaterialTheme.typography.headlineLarge)
                    Text("/ 100", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    if (rep.level.isNotEmpty()) Pill(rep.level, levelColor(rep.level))
                }
                ScoreBar(rep.score / 100f, levelColor(rep.level))
                Text(
                    "${rep.completed} completed · ${rep.refunded} refunded · ${rep.disputed} disputed" +
                        if (rep.completed > 0) " · ${rep.distinctCounterparties} trader${if (rep.distinctCounterparties == 1) "" else "s"}" else "",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(rep.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (rep.formula.isNotEmpty()) {
                    Text(
                        if (showFormula) "Hide how it's scored" else "How is this scored?",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { showFormula = !showFormula }.padding(vertical = 4.dp),
                    )
                    if (showFormula) {
                        Text(
                            rep.formula + " Counted here: ${rep.effectiveCompleted} of ${rep.completed} completed.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                (rep.account.ifEmpty { wallet })?.let { ExplorerLink("Counters on-chain", Explorer.address(it)) }
            }
        }
    }
}
