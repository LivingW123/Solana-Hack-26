package app.taptrade.ui.listings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.taptrade.data.Listing
import app.taptrade.data.formatUnits
import app.taptrade.data.shortAddress
import app.taptrade.ui.ErrorText
import app.taptrade.ui.LocalActivityResultSender
import app.taptrade.ui.StatusChip
import app.taptrade.ui.WalletAction
import app.taptrade.ui.screenViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListingsScreen(onOpen: (pubkey: String) -> Unit) {
    val vm = screenViewModel { c, _ -> ListingsViewModel(c.backend, c.wallet) }
    val state by vm.state.collectAsStateWithLifecycle()
    val address by vm.address.collectAsStateWithLifecycle()
    val sender = LocalActivityResultSender.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("TapTrade") },
                actions = {
                    WalletAction(address, state.connecting, { vm.connect(sender) }, vm::disconnect)
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                ErrorText(state.error)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Listings", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = vm::refresh) { Text("Refresh") }
                }
            }
            LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.listings, key = { it.pubkey }) { listing ->
                    ListingRow(listing, isMine = listing.seller == address, onClick = { onOpen(listing.pubkey) })
                }
            }
        }
    }
}

@Composable
private fun ListingRow(listing: Listing, isMine: Boolean, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(listing.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (isMine) "Your listing" else "Seller ${shortAddress(listing.seller)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${formatUnits(listing.price)} tUSDC", style = MaterialTheme.typography.titleSmall)
                StatusChip(listing.status)
            }
        }
    }
}
