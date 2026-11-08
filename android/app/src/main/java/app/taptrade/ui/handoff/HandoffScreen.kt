package app.taptrade.ui.handoff

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import app.taptrade.explain.Check
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
import app.taptrade.ui.LocalActivityResultSender
import app.taptrade.ui.WalletAction
import app.taptrade.ui.screenViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HandoffScreen(onBack: () -> Unit) {
    val vm = screenViewModel { c, s -> HandoffViewModel(c.backend, c.wallet, c.rpc, c.tapTransport, s) }
    val state by vm.state.collectAsStateWithLifecycle()
    val sender = LocalActivityResultSender.current
    val address by vm.address.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (vm.role == HandoffRole.Seller) "Handoff · seller" else "Handoff · buyer") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = { WalletAction(address, state.connecting, { vm.connect(sender) }, {}) },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (state.loading || state.reviewing || state.releasing) LinearProgressIndicator(Modifier.fillMaxWidth())
            Column(
                Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Gap,
            ) {
                ErrorText(state.error)
                state.listing?.let { Summary(it) }
                when (vm.role) {
                    HandoffRole.Seller -> SellerPanel(state, vm::publish, vm::regenerate)
                    HandoffRole.Buyer -> BuyerPanel(
                        state, state.listing, vm.canSimulateTap, vm::simulateTap,
                        onSign = { vm.sign(sender) },
                        onRetry = vm::retryReview,
                    )
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
    when (state.settlement) {
        Settlement.Paid -> {
            Text("Paid ✓", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.primary)
            Text("The escrow closed on-chain and the tUSDC is in your wallet. Your phone saw it straight from Solana, not from our server.")
            return
        }
        Settlement.Refunded -> {
            Text("Refunded", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.error)
            Text("The deadline passed and the escrow was refunded to the buyer. Your listing is open again.")
            return
        }
        null -> Unit
    }
    val url = state.url ?: return
    Text("Ready for handoff", style = MaterialTheme.typography.titleLarge)
    Text(
        if (state.watching) "● Watching the escrow on-chain" else "○ Connecting to Solana…",
        style = MaterialTheme.typography.labelMedium,
    )
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
    onSign: () -> Unit,
    onRetry: () -> Unit,
) {
    val received = state.received
    val released = state.released
    if (released != null) {
        Text("Released ✓", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.primary)
        Text("Confirmed on Solana in ${"%.1f".format(released.confirmMs / 1000.0)} s", style = MaterialTheme.typography.titleMedium)
        released.message?.let { Text(it) }
        SelectionContainer {
            Text("Tx ${released.signature}", style = MaterialTheme.typography.bodySmall)
        }
    } else if (received == null) {
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
        val review = state.review
        when {
            review == null && state.reviewing -> Text("Checking this release against Solana…")
            review == null -> {
                Text("Connect your wallet to check and sign this release.")
                OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Check release") }
            }
            review.ok -> {
                ExplainerCard(review)
                Button(onClick = onSign, enabled = !state.releasing, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.releasing) "Releasing…" else "Sign release · ${listing?.let { formatUnits(it.price) } ?: "?"} tUSDC")
                }
            }
            else -> {
                BlockedCard(review)
                OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Check again") }
            }
        }
    }
}

/** All rules passed: the sentence (LLM or template) plus the rule list. */
@Composable
private fun ExplainerCard(review: Review) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Before you sign", style = MaterialTheme.typography.labelLarge)
            Text(review.summary ?: "…", style = MaterialTheme.typography.titleMedium)
            review.summarySource?.let {
                Text(
                    when (it) {
                        "llm" -> "Sentence written by AI from the checked facts"
                        else -> "Plain summary (AI unavailable)"
                    },
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            HorizontalDivider()
            Text("Checked on your phone against Solana", style = MaterialTheme.typography.labelMedium)
            review.checks.forEach { CheckRow(it) }
        }
    }
}

/** At least one rule failed: no signable bytes, reasons first. The AI is not consulted. */
@Composable
private fun BlockedCard(review: Review) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Blocked: don't sign this", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.error)
            review.checks.filter { !it.ok }.forEach { CheckRow(it) }
            review.claimed?.let {
                Text("The link described itself as: \"$it\". The transaction does something else.", style = MaterialTheme.typography.bodySmall)
            }
            if (review.checks.any { it.ok }) {
                HorizontalDivider()
                Text("Passed", style = MaterialTheme.typography.labelMedium)
                review.checks.filter { it.ok }.forEach { CheckRow(it) }
            }
        }
    }
}

@Composable
private fun CheckRow(c: Check) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (c.ok) "✓" else "✗",
            color = if (c.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.titleSmall,
        )
        Column {
            Text(c.rule, style = MaterialTheme.typography.bodyMedium)
            Text(c.detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}
