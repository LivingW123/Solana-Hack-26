package app.taptrade.ui.detail

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.taptrade.data.Listing
import app.taptrade.data.ListingStatus
import app.taptrade.data.Reputation
import app.taptrade.data.formatUnits
import app.taptrade.data.shortAddress
import app.taptrade.ui.ErrorText
import app.taptrade.ui.Gap
import app.taptrade.ui.LocalActivityResultSender
import androidx.compose.material3.OutlinedButton
import app.taptrade.data.Explorer
import app.taptrade.ui.Banner
import app.taptrade.ui.ExplorerLink
import app.taptrade.ui.ItemAvatar
import app.taptrade.ui.SurfaceCard
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.Alignment
import app.taptrade.ui.ReputationCard
import app.taptrade.ui.StatusChip
import app.taptrade.ui.WalletAction
import app.taptrade.ui.handoff.HandoffRole
import app.taptrade.ui.screenViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListingDetailScreen(
    onBack: () -> Unit,
    onHandoff: (pubkey: String, role: HandoffRole) -> Unit,
) {
    val vm = screenViewModel { c, s -> ListingDetailViewModel(c.backend, c.wallet, c.rpc, s) }
    val state by vm.state.collectAsStateWithLifecycle()
    val address by vm.address.collectAsStateWithLifecycle()
    val sender = LocalActivityResultSender.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Listing") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = { WalletAction(address, state.connecting, { vm.connect(sender) }, {}) },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Column(
                Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Gap,
            ) {
                ErrorText(state.error)
                state.message?.let { Banner(it) }
                state.lastTx?.let { ExplorerLink("View transaction on Solana Explorer", Explorer.tx(it)) }
                state.listing?.let { listing ->
                    ListingInfo(listing)
                    ReputationCard("Seller reputation", listing.seller, state.sellerRep)
                    Actions(
                        listing = listing,
                        address = address,
                        busy = state.busy,
                        onFund = { vm.fund(sender) },
                        onCancel = { vm.cancel(sender, onBack) },
                        onHandoff = { role -> onHandoff(listing.pubkey, role) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ListingInfo(listing: Listing) {
    SurfaceCard {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ItemAvatar(listing.title, size = 56)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(listing.title, style = MaterialTheme.typography.headlineSmall)
                    StatusChip(listing.status)
                }
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(formatUnits(listing.price), style = MaterialTheme.typography.displayMedium)
                Text("tUSDC", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            DetailRow("Seller", shortAddress(listing.seller))
            DetailRow("Listing account", shortAddress(listing.pubkey))
            listing.escrow?.let { DetailRow("Escrow", shortAddress(it)) }
            ExplorerLink("Listing on-chain", Explorer.address(listing.pubkey))
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Actions(
    listing: Listing,
    address: String?,
    busy: Boolean,
    onFund: () -> Unit,
    onCancel: () -> Unit,
    onHandoff: (HandoffRole) -> Unit,
) {
    val isSeller = address != null && address == listing.seller
    val isBuyer = address != null && address == listing.buyer
    val big = Modifier.fillMaxWidth().height(56.dp)
    val hint: @Composable (String) -> Unit = {
        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    when {
        address == null -> hint("Connect a wallet (top right) to trade.")
        listing.status == ListingStatus.Open && !isSeller -> {
            Button(onClick = onFund, enabled = !busy, modifier = big) {
                Text("Fund escrow · ${formatUnits(listing.price)} tUSDC", style = MaterialTheme.typography.titleMedium)
            }
            hint("Your tUSDC goes into a program vault, not to the seller, until you tap at the handoff. Refundable after the deadline.")
        }
        listing.status == ListingStatus.Open && isSeller -> {
            hint("Waiting for a buyer to fund the escrow.")
            OutlinedButton(onClick = onCancel, enabled = !busy, modifier = big) { Text("Cancel listing") }
        }
        listing.status == ListingStatus.Funded && isSeller ->
            Button(onClick = { onHandoff(HandoffRole.Seller) }, modifier = big) {
                Text("Ready for handoff", style = MaterialTheme.typography.titleMedium)
            }
        listing.status == ListingStatus.Funded && isBuyer ->
            Button(onClick = { onHandoff(HandoffRole.Buyer) }, modifier = big) {
                Text("Meet seller · wait for tap", style = MaterialTheme.typography.titleMedium)
            }
        else -> hint("Someone else is buying this one.")
    }
}
