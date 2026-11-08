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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.taptrade.data.Listing
import app.taptrade.data.formatUnits
import app.taptrade.data.shortAddress
import app.taptrade.data.Explorer
import app.taptrade.ui.ErrorText
import app.taptrade.ui.ExplorerLink
import app.taptrade.ui.LocalActivityResultSender
import app.taptrade.ui.StatusChip
import app.taptrade.ui.WalletAction
import app.taptrade.ui.screenViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListingsScreen(onOpen: (pubkey: String) -> Unit) {
    val vm = screenViewModel { c, _ -> ListingsViewModel(c.backend, c.wallet, c.rpc) }
    val state by vm.state.collectAsStateWithLifecycle()
    val address by vm.address.collectAsStateWithLifecycle()
    val sender = LocalActivityResultSender.current
    var selling by rememberSaveable { mutableStateOf(false) }

    // Coming back from a trade (Back, or app resumed): settled listings are closed
    // accounts now, so re-read instead of showing what was true before.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }

    if (selling) {
        SellDialog(
            state = state,
            validate = vm::validate,
            onDismiss = {
                selling = false
                vm.clearDraft()
            },
            onDraft = vm::draft,
            onClearDraft = vm::clearDraft,
            onSignDraft = { vm.signDraft(sender) { selling = false } },
            onSubmit = { title, price -> vm.createListing(sender, title, price) { selling = false } },
        )
    }

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
            if (state.loading || state.creating) LinearProgressIndicator(Modifier.fillMaxWidth())
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                ErrorText(state.error)
                state.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                state.lastTx?.let { ExplorerLink("View transaction on Solana Explorer", Explorer.tx(it)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Listings", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = vm::refresh) { Text("Refresh") }
                    if (address != null) {
                        Button(onClick = { selling = true }, enabled = !state.creating) { Text("Sell") }
                    }
                }
                if (!state.loading && state.listings.isEmpty()) {
                    Text("No open listings on-chain yet.", style = MaterialTheme.typography.bodyMedium)
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

/**
 * Two ways to list: describe it in a sentence (AI draft → review → sign), or fill the
 * form. Either way the seller sees exactly what goes on-chain before the wallet opens.
 */
@Composable
private fun SellDialog(
    state: ListingsUiState,
    validate: (title: String, price: String) -> String?,
    onDismiss: () -> Unit,
    onDraft: (String) -> Unit,
    onClearDraft: () -> Unit,
    onSignDraft: () -> Unit,
    onSubmit: (title: String, price: String) -> Unit,
) {
    val busy = state.creating || state.drafting
    var manual by rememberSaveable { mutableStateOf(false) }
    var sentence by rememberSaveable { mutableStateOf("") }
    var title by rememberSaveable { mutableStateOf("") }
    var price by rememberSaveable { mutableStateOf("") }
    val draft = state.draft?.draft
    val problem = validate(title, price)

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (draft != null) "Review your listing" else "New listing") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ErrorText(state.error)
                when {
                    draft != null -> {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(draft.title, style = MaterialTheme.typography.titleMedium)
                                Text("${formatUnits(draft.price)} tUSDC", style = MaterialTheme.typography.titleLarge)
                                Text("Hand off by ${formatDeadline(draft.deadlineIso)} · ${draft.category}", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    if (draft.source == "llm") "Drafted by AI from your sentence. Check it." else "Read from your sentence. Check it.",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                        Text("Title and price go on-chain. Your phone checks the transaction matches this before your wallet opens.", style = MaterialTheme.typography.bodySmall)
                    }
                    !manual -> {
                        OutlinedTextField(
                            value = sentence,
                            onValueChange = { sentence = it },
                            label = { Text("Describe it") },
                            placeholder = { Text("sell my calc textbook for 20 USDC by Friday") },
                            enabled = !busy,
                            minLines = 2,
                        )
                        TextButton(onClick = { manual = true }, enabled = !busy) { Text("Fill in a form instead") }
                    }
                    else -> {
                        OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title (on-chain)") },
                    singleLine = true,
                    enabled = !busy,
                )
                OutlinedTextField(
                    value = price,
                    onValueChange = { price = it },
                    label = { Text("Price (tUSDC)") },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                        if (title.isNotEmpty() || price.isNotEmpty()) {
                            problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                        }
                        Text("Your wallet signs; the listing lives on Solana.", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { manual = false }, enabled = !busy) { Text("Describe it in a sentence instead") }
                    }
                }
            }
        },
        confirmButton = {
            when {
                draft != null -> Button(onClick = onSignDraft, enabled = !busy) {
                    Text(if (state.creating) "Listing…" else "Sign & list")
                }
                !manual -> Button(onClick = { onDraft(sentence) }, enabled = sentence.trim().length >= 3 && !busy) {
                    Text(if (state.drafting) "Drafting…" else "Draft listing")
                }
                else -> Button(onClick = { onSubmit(title, price) }, enabled = problem == null && !busy) {
                    Text(if (state.creating) "Listing…" else "List it")
                }
            }
        },
        dismissButton = {
            if (draft != null) {
                TextButton(onClick = {
                    // Edit: open the form prefilled with the draft.
                    title = draft.title
                    price = formatUnits(draft.price)
                    manual = true
                    onClearDraft()
                }, enabled = !busy) { Text("Edit") }
            } else {
                TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
            }
        },
    )
}

private fun formatDeadline(iso: String): String = runCatching {
    // Deadlines are end-of-day UTC; show the date only so "by Friday" reads as Friday everywhere.
    java.time.ZonedDateTime.parse(iso).format(java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d"))
}.getOrDefault(iso)

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
