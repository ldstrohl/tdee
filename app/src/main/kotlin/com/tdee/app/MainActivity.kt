package com.tdee.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.tdee.app.addfood.AddFoodScreen
import com.tdee.app.addfood.AddFoodViewModel
import com.tdee.app.dashboard.DashboardScreen
import com.tdee.app.dashboard.DashboardViewModel
import com.tdee.app.onboarding.OnboardingScreen
import com.tdee.app.onboarding.OnboardingViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        setContent {
            MaterialTheme {
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
                    val container = (application as TdeeApplication).container
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
                                        onAddFood = { navController.navigate("add_food") },   // wired for Task B
                                        onAddWeight = { navController.navigate("add_weight") }, // wired for Task C
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
                                    // TODO: Task C replaces this placeholder with the real Add Weight screen.
                                    Column(
                                        modifier = Modifier.fillMaxSize().statusBarsPadding(),
                                        verticalArrangement = Arrangement.Center,
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        Text("Add Weight", style = MaterialTheme.typography.headlineSmall)
                                        Button(onClick = { navController.popBackStack() }) {
                                            Text("Back")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
