package com.tdee.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.tdee.app.addfood.AddFoodScreen
import com.tdee.app.addfood.AddFoodViewModel
import com.tdee.app.addfood.EditFoodEntryScreen
import com.tdee.app.addfood.EditFoodEntryViewModel
import com.tdee.app.addfood.ParseConfirmScreen
import com.tdee.app.addfood.ParseConfirmViewModel
import com.tdee.app.addweight.AddWeightScreen
import com.tdee.app.addweight.AddWeightViewModel
import com.tdee.app.checkin.CheckinScreen
import com.tdee.app.checkin.CheckinViewModel
import com.tdee.app.dashboard.DashboardScreen
import com.tdee.app.dashboard.DashboardViewModel
import com.tdee.app.editprofile.EditProfileScreen
import com.tdee.app.editprofile.EditProfileViewModel
import com.tdee.app.insights.HelpScreen
import com.tdee.app.insights.InsightsScreen
import com.tdee.app.insights.InsightsViewModel
import com.tdee.app.onboarding.OnboardingScreen
import com.tdee.app.onboarding.OnboardingViewModel
import com.tdee.app.settings.SettingsRoute
import com.tdee.app.ui.theme.TdeeTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        setContent {
            val container = (application as TdeeApplication).container
            val themePreference by container.themeStore.preference.collectAsState()
            TdeeTheme(preference = themePreference) {
                Surface {
                    // Collect the profile flow as Compose state.
                    //
                    // initial = null means the first Compose frame treats the state as
                    // "no profile" (shows Onboarding) until Room emits. Room emits the
                    // first value very quickly, so on a device with an existing profile
                    // the onboarding screen is visible for at most one or two frames.
                    // For MVP this flash is acceptable — a proper splash/loading screen
                    // can be added later without changing this routing logic.
                    //
                    // After onboarding saves a profile, observeProfile() re-emits the
                    // new entity automatically, Compose recomposes, and the Dashboard
                    // is shown — no manual navigation call needed.
                    val profile by container.repository.observeProfile()
                        .collectAsState(initial = null)

                    when {
                        profile == null -> {
                            val vm: OnboardingViewModel =
                                viewModel(factory = OnboardingViewModel.Factory)
                            OnboardingScreen(viewModel = vm)
                        }
                        else -> {
                            // Foreground incremental HC sync on app open (only when a
                            // profile exists, i.e. past onboarding). Resilient: no-ops if
                            // HC is unavailable or permission was never granted.
                            LaunchedEffect(Unit) {
                                val sync = container.healthConnectSyncManager
                                val src = container.healthConnectSource
                                runCatching {
                                    if (src.isAvailable() && src.hasReadPermission()) {
                                        sync.sync(fullHistory = false)
                                    }
                                }
                            }

                            val navController = rememberNavController()

                            NavHost(
                                navController = navController,
                                startDestination = "dashboard",
                            ) {
                                composable("dashboard") {
                                    val vm: DashboardViewModel =
                                        viewModel(factory = DashboardViewModel.Factory)
                                    // Reload engine-derived targets/TDEE when the dashboard is
                                    // resumed (e.g. returning from a check-in or target edit), so
                                    // the displayed active targets reflect the new period.
                                    val lifecycleOwner = LocalLifecycleOwner.current
                                    DisposableEffect(lifecycleOwner) {
                                        val observer = LifecycleEventObserver { _, event ->
                                            if (event == Lifecycle.Event.ON_RESUME) vm.reload()
                                        }
                                        lifecycleOwner.lifecycle.addObserver(observer)
                                        onDispose {
                                            lifecycleOwner.lifecycle.removeObserver(observer)
                                        }
                                    }
                                    DashboardScreen(
                                        viewModel = vm,
                                        onAddFood = { navController.navigate("add_food") },
                                        onLogText = { navController.navigate("log_text") },
                                        onAddWeight = { navController.navigate("add_weight") },
                                        onOpenSettings = { navController.navigate("settings") },
                                        onOpenInsights = { navController.navigate("insights") },
                                        onCheckin = { navController.navigate("checkin") },
                                        onEditFood = { id -> navController.navigate("edit_food/$id") },
                                    )
                                }

                                composable("checkin") {
                                    val vm: CheckinViewModel =
                                        viewModel(factory = CheckinViewModel.Factory)
                                    CheckinScreen(
                                        viewModel = vm,
                                        onDone = { navController.popBackStack() },
                                    )
                                }

                                composable("insights") {
                                    val vm: InsightsViewModel =
                                        viewModel(factory = InsightsViewModel.Factory)
                                    InsightsScreen(
                                        viewModel = vm,
                                        onBack = { navController.popBackStack() },
                                        onHelp = { navController.navigate("help") },
                                    )
                                }

                                composable("help") {
                                    HelpScreen(onBack = { navController.popBackStack() })
                                }

                                composable("settings") {
                                    val pref by container.themeStore.preference.collectAsState()
                                    SettingsRoute(
                                        current = pref,
                                        onSelect = { container.themeStore.set(it) },
                                        onBack = { navController.popBackStack() },
                                        onEditProfile = { navController.navigate("edit_profile") },
                                    )
                                }

                                composable("edit_profile") {
                                    val vm: EditProfileViewModel =
                                        viewModel(factory = EditProfileViewModel.Factory)
                                    EditProfileScreen(
                                        viewModel = vm,
                                        onDone = { navController.popBackStack() },
                                    )
                                }

                                composable("add_food") {
                                    val vm: AddFoodViewModel =
                                        viewModel(factory = AddFoodViewModel.Factory)
                                    AddFoodScreen(
                                        viewModel = vm,
                                        onDone = { navController.popBackStack() },
                                    )
                                }

                                composable("log_text") {
                                    val vm: ParseConfirmViewModel =
                                        viewModel(factory = ParseConfirmViewModel.Factory)
                                    ParseConfirmScreen(
                                        viewModel = vm,
                                        onDone = { navController.popBackStack() },
                                    )
                                }

                                composable("add_weight") {
                                    val vm: AddWeightViewModel =
                                        viewModel(factory = AddWeightViewModel.Factory)
                                    AddWeightScreen(
                                        viewModel = vm,
                                        onDone = { navController.popBackStack() },
                                    )
                                }

                                composable(
                                    route = "edit_food/{foodId}",
                                    arguments = listOf(
                                        navArgument("foodId") { type = NavType.LongType }
                                    ),
                                ) { backStackEntry ->
                                    val foodId = backStackEntry.arguments!!.getLong("foodId")
                                    val vm: EditFoodEntryViewModel =
                                        viewModel(factory = EditFoodEntryViewModel.factory(foodId))
                                    EditFoodEntryScreen(
                                        viewModel = vm,
                                        onDone = { navController.popBackStack() },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
