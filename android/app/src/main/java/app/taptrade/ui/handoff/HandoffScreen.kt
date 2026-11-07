package app.taptrade.ui.handoff

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.taptrade.data.Listing
import app.taptrade.data.formatUnits
import app.taptrade.data.shortAddress
import app.taptrade.ui.ErrorText
import app.taptrade.ui.Gap
import app.taptrade.ui.screenViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HandoffScreen(onBack: () -> Unit) {
    val vm = screenViewModel { c, s -> HandoffViewModel(c.backend, c.wallet, c.tapTransport, s) }
    val state by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (vm.role == HandoffRole.Seller) "Handoff · seller" else "Handoff · buyer") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            Column(
                Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Gap,
            ) {
                ErrorText(state.error)
                state.listing?.let { Summary(it) }
                when (vm.role) {
                    HandoffRole.Seller -> SellerPanel(state, vm::publish, vm::regenerate)
                    HandoffRole.Buyer -> BuyerPanel(state, state.listing, vm.canSimulateTap, vm::simulateTap)
                }
            }
        }
    }
}

@Composable
private fun Summary(listing: Listing) {
    Text(listing.title, style = MaterialTheme.typography.headlineSmall)
    Text("${formatUnits(listing.price)} tUSDC in escrow", style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun SellerPanel(state: HandoffUiState, onPublish: () -> Unit, onRegenerate: () -> Unit) {
    val url = state.url ?: return
    Text("Ready for handoff", style = MaterialTheme.typography.titleLarge)
    Text("Hand over the item, then let the buyer tap your phone. Their wallet releases the escrow to you.")
    Card(Modifier.fillMaxWidth()) {
        SelectionContainer {
            Text(url, Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
        }
    }
    Button(onClick = onPublish, modifier = Modifier.fillMaxWidth()) {
        Text(if (state.published) "Published · waiting for tap" else "Publish for tap")
    }
    OutlinedButton(onClick = onRegenerate, modifier = Modifier.fillMaxWidth()) {
        Text("New code (expires in 2 min)")
    }
}

@Composable
private fun BuyerPanel(
    state: HandoffUiState,
    listing: Listing?,
    canSimulate: Boolean,
    onSimulate: (String) -> Unit,
) {
    val received = state.received
    if (received == null) {
        Text("Waiting for tap…", style = MaterialTheme.typography.titleLarge)
        Text("Tap the seller's phone after you've checked the item.")
        if (canSimulate) {
            var input by rememberSaveable { mutableStateOf("") }
            Text("Debug: paste the seller's URL to simulate a tap", style = MaterialTheme.typography.labelMedium)
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                label = { Text("solana:http://…/tx/release?escrow=…&nonce=…") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = { onSimulate(input) }, enabled = input.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text("Simulate tap")
            }
        }
    } else {
        Text("Tap received", style = MaterialTheme.typography.titleLarge)
        val escrowMatches = listing?.escrow != null && received.escrow == listing.escrow
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Escrow ${received.escrow?.let(::shortAddress) ?: "missing"}" +
                    if (escrowMatches) " ✓ matches this listing" else " ✗ does not match this listing")
                Text("Nonce ${received.nonce ?: "missing"}", style = MaterialTheme.typography.bodySmall)
            }
        }
        // Phase 4: POST wallet to the URL → unsigned release tx. Phase 6: explainer gates signing.
        Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
            Text("Review & sign release (Phase 4/6)")
        }
    }
}
