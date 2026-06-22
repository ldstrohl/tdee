package com.tdee.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.tdee.app.addfood.AddFoodScreen
import com.tdee.app.addfood.AddFoodViewModel
import com.tdee.app.addweight.AddWeightScreen
import com.tdee.app.addweight.AddWeightViewModel
import com.tdee.app.dashboard.DashboardScreen
import com.tdee.app.dashboard.DashboardViewModel
import com.tdee.app.insights.InsightsScreen
import com.tdee.app.insights.InsightsViewModel
import com.tdee.app.onboarding.OnboardingScreen
import com.tdee.app.onboarding.OnboardingViewModel
import com.tdee.app.settings.SettingsScreen
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
                            val navController = rememberNavController()

                            NavHost(
                                navController = navController,
                                startDestination = "dashboard",
                            ) {
                                composable("dashboard") {
                                    val vm: DashboardViewModel =
                                        viewModel(factory = DashboardViewModel.Factory)
                                    DashboardScreen(
                                        viewModel = vm,
                                        onAddFood = { navController.navigate("add_food") },
                                        onAddWeight = { navController.navigate("add_weight") },
                                        onOpenSettings = { navController.navigate("settings") },
                                        onOpenInsights = { navController.navigate("insights") },
                                    )
                                }

                                composable("insights") {
                                    val vm: InsightsViewModel =
                                        viewModel(factory = InsightsViewModel.Factory)
                                    InsightsScreen(
                                        viewModel = vm,
                                        onBack = { navController.popBackStack() },
                                    )
                                }

                                composable("settings") {
                                    val pref by container.themeStore.preference.collectAsState()
                                    SettingsScreen(
                                        current = pref,
                                        onSelect = { container.themeStore.set(it) },
                                        onBack = { navController.popBackStack() },
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

                                composable("add_weight") {
                                    val vm: AddWeightViewModel =
                                        viewModel(factory = AddWeightViewModel.Factory)
                                    AddWeightScreen(
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
