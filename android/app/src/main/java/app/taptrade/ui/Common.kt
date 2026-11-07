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
import app.taptrade.data.ListingStatus
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
