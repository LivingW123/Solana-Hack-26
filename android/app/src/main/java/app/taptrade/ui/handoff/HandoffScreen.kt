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
import app.taptrade.data.Explorer
import app.taptrade.ui.ExplorerLink
import app.taptrade.ui.LocalActivityResultSender
import app.taptrade.ui.ReputationCard
import app.taptrade.ui.WalletAction
import app.taptrade.ui.screenViewModel
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import app.taptrade.ui.Banner
import app.taptrade.ui.CheckLine
import app.taptrade.ui.ItemAvatar
import app.taptrade.ui.Pill
import app.taptrade.ui.StatusHero
import app.taptrade.ui.SurfaceCard
import app.taptrade.ui.theme.Brand

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
                val done = state.settlement != null || state.released != null
                if (done) {
                    // Phase 8: counters were bumped on-chain by `release`; re-read, not assumed.
                    ReputationCard("Your reputation (updated on-chain)", address, state.myRep)
                } else {
                    val other = if (vm.role == HandoffRole.Seller) state.listing?.buyer else state.listing?.seller
                    ReputationCard(if (vm.role == HandoffRole.Seller) "Buyer's reputation" else "Seller's reputation", other, state.counterpartRep)
                }
            }
        }
    }
}

@Composable
private fun Summary(listing: Listing) {
    SurfaceCard {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ItemAvatar(listing.title)
            Column(Modifier.weight(1f)) {
                Text(listing.title, style = MaterialTheme.typography.titleMedium)
                Text("in escrow on Solana", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("${formatUnits(listing.price)} tUSDC", style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun SellerPanel(state: HandoffUiState, onPublish: () -> Unit, onRegenerate: () -> Unit) {
    when (state.settlement) {
        Settlement.Paid -> {
            StatusHero("✓", "Paid", "The tUSDC is in your wallet", Brand.Green)
            Banner("Your phone saw the escrow close straight from Solana, not from our server.")
            state.listing?.escrow?.let { ExplorerLink("Escrow history on Solana Explorer", Explorer.address(it)) }
            return
        }
        Settlement.Refunded -> {
            StatusHero("↺", "Refunded", "The deadline passed; the buyer got their tUSDC back", Brand.Amber)
            Banner("Your listing is open again.", Brand.Amber)
            return
        }
        null -> Unit
    }
    val url = state.url ?: return
    SurfaceCard {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Ready for handoff", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (state.watching) Pill("● Live on-chain", Brand.Green) else Pill("Connecting…", Brand.TextMuted)
            }
            Text(
                "Hand over the item, then let the buyer tap your phone. Their wallet releases the escrow to you, and this screen flips to Paid by itself.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SelectionContainer {
                Text(
                    url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(10.dp),
                )
            }
        }
    }
    Button(onClick = onPublish, modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Text(if (state.published) "Published · waiting for tap" else "Publish for tap", style = MaterialTheme.typography.titleMedium)
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
        StatusHero("✓", "Released", "Confirmed on Solana in ${"%.1f".format(released.confirmMs / 1000.0)} s", Brand.Green)
        released.message?.let { Banner(it) }
        ExplorerLink("View release on Solana Explorer", Explorer.tx(released.signature))
    } else if (received == null) {
        SurfaceCard {
            Column(
                Modifier.padding(24.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier.size(72.dp).clip(CircleShape).background(Brand.Purple.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) { Text("⟿", color = Brand.Purple, style = MaterialTheme.typography.headlineMedium) }
                Text("Waiting for tap", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Check the item, then tap the seller's phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (canSimulate) {
            var input by rememberSaveable { mutableStateOf("") }
            Text("Debug: paste the seller's URL to simulate a tap", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
        val review = state.review
        when {
            review == null && state.reviewing -> Banner("Tap received · checking this release against Solana…", Brand.Blue)
            review == null -> {
                Banner("Tap received. Your phone checks this release against Solana before you sign.", Brand.Blue)
                OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Check release") }
            }
            review.ok -> {
                ExplainerCard(review)
                Button(onClick = onSign, enabled = !state.releasing, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text(
                        if (state.releasing) "Waiting for your wallet…" else "Sign release · ${listing?.let { formatUnits(it.price) } ?: "?"} tUSDC",
                        style = MaterialTheme.typography.titleMedium,
                    )
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
    SurfaceCard {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("BEFORE YOU SIGN", style = MaterialTheme.typography.labelSmall, color = Brand.Green)
            Text(review.summary ?: "…", style = MaterialTheme.typography.titleLarge)
            review.summarySource?.let {
                Text(
                    if (it == "llm") "Sentence written by AI from the checked facts" else "Plain summary",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Checked on your phone against Solana", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Pill("${review.checks.count { it.ok }}/${review.checks.size} ✓", Brand.Green)
            }
            review.checks.forEach { CheckLine(it.ok, it.rule, it.detail) }
        }
    }
}

/** At least one rule failed: no signable bytes, reasons first. The AI is not consulted. */
@Composable
private fun BlockedCard(review: Review) {
    StatusHero("✕", "Blocked", "Don't sign this", Brand.Red)
    SurfaceCard {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("WHY", style = MaterialTheme.typography.labelSmall, color = Brand.Red)
            review.checks.filter { !it.ok }.forEach { CheckLine(false, it.rule, it.detail) }
            review.claimed?.let {
                Banner("The link described itself as \"$it\". The transaction does something else.", Brand.Amber)
            }
            Text(
                "No signature possible, and the AI was not consulted.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (review.checks.any { it.ok }) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                Text("PASSED", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                review.checks.filter { it.ok }.forEach { CheckLine(true, it.rule, it.detail) }
            }
        }
    }
}
