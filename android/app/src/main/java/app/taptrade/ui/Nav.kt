package app.taptrade.ui

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.taptrade.AppContainer
import app.taptrade.ui.detail.ListingDetailScreen
import app.taptrade.ui.handoff.HandoffRole
import app.taptrade.ui.handoff.HandoffScreen
import app.taptrade.ui.handoff.parseReleaseRequest
import app.taptrade.tap.DeepLinkTapTransport
import app.taptrade.ui.listings.ListingsScreen
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender

val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }
val LocalActivityResultSender = staticCompositionLocalOf<ActivityResultSender> { error("ActivityResultSender not provided") }

/** One ViewModel per screen, built from the app container + nav args. */
@Composable
inline fun <reified VM : ViewModel> screenViewModel(
    crossinline create: (AppContainer, SavedStateHandle) -> VM,
): VM {
    val container = LocalAppContainer.current
    return viewModel(factory = viewModelFactory { initializer { create(container, createSavedStateHandle()) } })
}

object Routes {
    const val LISTINGS = "listings"
    const val DETAIL = "listing/{pubkey}"
    const val HANDOFF = "handoff/{pubkey}/{role}"

    fun detail(pubkey: String) = "listing/$pubkey"
    fun handoff(pubkey: String, role: HandoffRole) = "handoff/$pubkey/${role.name}"
}

@Composable
fun TapTradeNav() {
    val nav = rememberNavController()
    val container = LocalAppContainer.current
    val context = LocalContext.current

    // A tap that arrived while no buyer Handoff screen was open: find the funded listing
    // with that escrow and open its Handoff; the screen then receives the held tap.
    val tap = container.tapTransport as? DeepLinkTapTransport
    val pending = tap?.pending?.collectAsStateWithLifecycle()?.value
    LaunchedEffect(pending) {
        val url = pending ?: return@LaunchedEffect
        val escrow = parseReleaseRequest(url).escrow
        val listing = runCatching {
            container.backend.listings().firstOrNull { escrow != null && it.escrow == escrow }
        }.getOrNull()
        if (listing == null) {
            tap?.clearPending()
            Toast.makeText(context, "Tap ignored: no funded listing for escrow ${escrow ?: "?"}", Toast.LENGTH_LONG).show()
        } else {
            nav.navigate(Routes.handoff(listing.pubkey, HandoffRole.Buyer)) { launchSingleTop = true }
        }
    }

    NavHost(navController = nav, startDestination = Routes.LISTINGS) {
        composable(Routes.LISTINGS) {
            ListingsScreen(onOpen = { nav.navigate(Routes.detail(it)) })
        }
        composable(
            Routes.DETAIL,
            arguments = listOf(navArgument("pubkey") { type = NavType.StringType }),
        ) {
            ListingDetailScreen(
                onBack = { nav.popBackStack() },
                onHandoff = { pubkey, role -> nav.navigate(Routes.handoff(pubkey, role)) },
            )
        }
        composable(
            Routes.HANDOFF,
            arguments = listOf(
                navArgument("pubkey") { type = NavType.StringType },
                navArgument("role") { type = NavType.StringType },
            ),
        ) {
            HandoffScreen(onBack = { nav.popBackStack() })
        }
    }
}
