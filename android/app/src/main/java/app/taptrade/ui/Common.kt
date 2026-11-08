package app.taptrade.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Card
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import app.taptrade.data.Explorer
import app.taptrade.data.ListingStatus
import app.taptrade.data.Reputation
import app.taptrade.data.shortAddress

/** Wallet chip for top bars: connect button, or the short address + disconnect. */
@Composable
fun WalletAction(address: String?, connecting: Boolean, onConnect: () -> Unit, onDisconnect: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp)) {
        when {
            connecting -> CircularProgressIndicator(Modifier.padding(8.dp))
            address == null -> Button(onClick = onConnect) { Text("Connect wallet") }
            else -> OutlinedButton(onClick = onDisconnect) { Text(shortAddress(address)) }
        }
    }
}

@Composable
fun StatusChip(status: ListingStatus) {
    AssistChip(onClick = {}, label = { Text(status.name) })
}

@Composable
fun ErrorText(message: String?) {
    if (message != null) {
        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }
}

val Gap = Arrangement.spacedBy(12.dp)

/** Opens Solana Explorer (devnet): anything the app claims can be checked there. */
@Composable
fun ExplorerLink(label: String, url: String) {
    val uri = LocalUriHandler.current
    TextButton(onClick = { runCatching { uri.openUri(url) } }, contentPadding = PaddingValues(0.dp)) {
        Text("$label ↗", style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * On-chain reputation: counters from the wallet's `Reputation` PDA, a score from an
 * explainable formula (shown), and a one-line summary.
 */
@Composable
fun ReputationCard(title: String, wallet: String?, rep: Reputation?) {
    var showFormula by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (rep == null) {
                Text("Unavailable", style = MaterialTheme.typography.bodyMedium)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${rep.score}/100", style = MaterialTheme.typography.headlineSmall)
                    if (rep.level.isNotEmpty()) AssistChip(onClick = {}, label = { Text(rep.level) })
                }
                Text(
                    "${rep.completed} completed · ${rep.refunded} refunded · ${rep.disputed} disputed" +
                        if (rep.completed > 0) " · ${rep.distinctCounterparties} different trader${if (rep.distinctCounterparties == 1) "" else "s"}" else "",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(rep.summary, style = MaterialTheme.typography.bodySmall)
                if (rep.formula.isNotEmpty()) {
                    TextButton(onClick = { showFormula = !showFormula }, contentPadding = PaddingValues(0.dp)) {
                        Text(if (showFormula) "Hide how it's scored" else "How is this scored?", style = MaterialTheme.typography.labelMedium)
                    }
                    if (showFormula) {
                        Text(
                            rep.formula + " Counted here: ${rep.effectiveCompleted} of ${rep.completed} completed.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                wallet?.let { ExplorerLink("Counters on-chain", Explorer.address(it)) }
            }
        }
    }
}
