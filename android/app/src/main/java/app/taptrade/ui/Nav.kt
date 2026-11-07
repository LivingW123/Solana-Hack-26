package app.taptrade.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
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
