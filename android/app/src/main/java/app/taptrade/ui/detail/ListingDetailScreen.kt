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
import app.taptrade.ui.StatusChip
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
                state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                state.listing?.let { listing ->
                    ListingInfo(listing)
                    SellerReputation(state.sellerRep)
                    Actions(
                        listing = listing,
                        address = address,
                        busy = state.busy,
                        onFund = { vm.fund(sender) },
                        onHandoff = { role -> onHandoff(listing.pubkey, role) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ListingInfo(listing: Listing) {
    Text(listing.title, style = MaterialTheme.typography.headlineSmall)
    Text("${formatUnits(listing.price)} tUSDC", style = MaterialTheme.typography.titleLarge)
    StatusChip(listing.status)
    Text("Seller ${shortAddress(listing.seller)}", style = MaterialTheme.typography.bodyMedium)
    Text("Listing ${shortAddress(listing.pubkey)}", style = MaterialTheme.typography.bodySmall)
    listing.escrow?.let { Text("Escrow ${shortAddress(it)}", style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun SellerReputation(rep: Reputation?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Seller reputation", style = MaterialTheme.typography.titleSmall)
            if (rep == null) {
                Text("Unavailable", style = MaterialTheme.typography.bodyMedium)
            } else {
                Text("Score ${rep.score}/100", style = MaterialTheme.typography.titleMedium)
                Text("${rep.completed} completed · ${rep.refunded} refunded · ${rep.disputed} disputed")
                Text(rep.summary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun Actions(
    listing: Listing,
    address: String?,
    busy: Boolean,
    onFund: () -> Unit,
    onHandoff: (HandoffRole) -> Unit,
) {
    val isSeller = address != null && address == listing.seller
    val isBuyer = address != null && address == listing.buyer
    when {
        address == null ->
            Text("Connect a wallet (top of the Listings screen) to trade.")
        listing.status == ListingStatus.Open && !isSeller ->
            Button(onClick = onFund, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text("Fund escrow · ${formatUnits(listing.price)} tUSDC")
            }
        listing.status == ListingStatus.Open && isSeller ->
            Text("Waiting for a buyer to fund escrow.")
        listing.status == ListingStatus.Funded && isSeller ->
            Button(onClick = { onHandoff(HandoffRole.Seller) }, modifier = Modifier.fillMaxWidth()) {
                Text("Ready for handoff")
            }
        listing.status == ListingStatus.Funded && isBuyer ->
            Button(onClick = { onHandoff(HandoffRole.Buyer) }, modifier = Modifier.fillMaxWidth()) {
                Text("Meet seller · wait for tap")
            }
        else -> Text("No actions available.")
    }
}
