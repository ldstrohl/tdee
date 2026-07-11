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
import java.time.LocalDate
import com.tdee.app.addweight.AddWeightScreen
import com.tdee.app.addweight.AddWeightViewModel
import com.tdee.app.checkin.CheckinScreen
import com.tdee.app.checkin.CheckinViewModel
import com.tdee.app.dashboard.DashboardScreen
import com.tdee.app.dashboard.DashboardViewModel
import com.tdee.app.editmeal.EditMealScreen
import com.tdee.app.editmeal.EditMealViewModel
import com.tdee.app.editprofile.EditProfileScreen
import com.tdee.app.editprofile.EditProfileViewModel
import com.tdee.app.insights.ChartDetailScreen
import com.tdee.app.insights.ChartType
import com.tdee.app.insights.HelpScreen
import com.tdee.app.insights.InsightsScreen
import com.tdee.app.insights.InsightsViewModel
import com.tdee.app.onboarding.OnboardingScreen
import com.tdee.app.onboarding.OnboardingViewModel
import com.tdee.app.savedmeals.SavedMealsScreen
import com.tdee.app.savedmeals.SavedMealsViewModel
import com.tdee.app.settings.LlmSettingsScreen
import com.tdee.app.settings.LlmSettingsViewModel
import com.tdee.app.settings.SettingsRoute
import com.tdee.app.ui.theme.TdeeTheme
import com.tdee.app.weight.WeightScreen
import com.tdee.app.weight.WeightViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        setContent {
            val container = (application as TdeeApplication).container
            val themePreference by container.themeStore.preference.collectAsState()
            TdeeTheme(preference = themePreference) {
                Surface {
                    val profile by container.repository.observeProfile()
                        .collectAsState(initial = null)

                    when {
                        profile == null -> {
                            val vm: OnboardingViewModel =
                                viewModel(factory = OnboardingViewModel.Factory)
                            OnboardingScreen(viewModel = vm)
                        }
                        else -> {
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
                                        onAddFood = { date -> navController.navigate("add_food?date=$date") },
                                        onLogText = { date -> navController.navigate("log_text?date=$date") },
                                        onAddWeight = { navController.navigate("weight") },
                                        onOpenSettings = { navController.navigate("settings") },
                                        onOpenInsights = { navController.navigate("insights") },
                                        onCheckin = { navController.navigate("checkin") },
                                        onEditFood = { id -> navController.navigate("edit_food/$id") },
                                        onSavedMeals = { date -> navController.navigate("saved_meals?date=$date") },
                                        onOpenChart = { type -> navController.navigate("chart_detail/${type.name}") },
                                        onEditMeal = { mealId -> navController.navigate("edit_meal/$mealId") },
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
                                        onMaximize = { type ->
                                            navController.navigate("chart_detail/${type.name}")
                                        },
                                    )
                                }

                                composable(
                                    route = "chart_detail/{type}",
                                    arguments = listOf(
                                        navArgument("type") { type = NavType.StringType }
                                    ),
                                ) { backStackEntry ->
                                    val chartType = ChartType.valueOf(
                                        backStackEntry.arguments!!.getString("type")!!
                                    )
                                    val vm: InsightsViewModel =
                                        viewModel(factory = InsightsViewModel.Factory)
                                    ChartDetailScreen(
                                        viewModel = vm,
                                        type = chartType,
                                        onBack = { navController.popBackStack() },
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
                                        onMealParsing = { navController.navigate("llm_settings") },
                                    )
                                }

                                composable("llm_settings") {
                                    val vm: LlmSettingsViewModel =
                                        viewModel(factory = LlmSettingsViewModel.Factory)
                                    LlmSettingsScreen(
                                        viewModel = vm,
                                        onBack = { navController.popBackStack() },
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

                                composable(
                                    route = "add_food?date={date}",
                                    arguments = listOf(
                                        navArgument("date") {
                                            type = NavType.StringType
                                            nullable = true
                                            defaultValue = null
                                        }
                                    ),
                                ) { backStackEntry ->
                                    val dateArg = backStackEntry.arguments?.getString("date")
                                    val initialDate = if (dateArg != null) LocalDate.parse(dateArg) else LocalDate.now()
                                    val vm: AddFoodViewModel =
                                        viewModel(factory = AddFoodViewModel.factory(initialDate))
                                    AddFoodScreen(
                                        viewModel = vm,
                                        onDone = { navController.popBackStack() },
                                    )
                                }

                                composable(
                                    route = "log_text?date={date}",
                                    arguments = listOf(
                                        navArgument("date") {
                                            type = NavType.StringType
                                            nullable = true
                                            defaultValue = null
                                        }
                                    ),
                                ) { backStackEntry ->
                                    val dateArg = backStackEntry.arguments?.getString("date")
                                    val initialDate = if (dateArg != null) LocalDate.parse(dateArg) else LocalDate.now()
                                    val vm: ParseConfirmViewModel =
                                        viewModel(factory = ParseConfirmViewModel.factory(initialDate))
                                    ParseConfirmScreen(
                                        viewModel = vm,
                                        onDone = { navController.popBackStack() },
                                    )
                                }

                                composable("weight") {
                                    val vm: WeightViewModel =
                                        viewModel(factory = WeightViewModel.Factory)
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
                                    WeightScreen(
                                        viewModel = vm,
                                        onBack = { navController.popBackStack() },
                                        onLogManual = { navController.navigate("add_weight") },
                                        onExpandChart = { navController.navigate("chart_detail/${ChartType.TREND.name}") },
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

                                composable(
                                    route = "edit_meal/{mealId}",
                                    arguments = listOf(
                                        navArgument("mealId") { type = NavType.StringType }
                                    ),
                                ) { backStackEntry ->
                                    val mealId = backStackEntry.arguments!!.getString("mealId")!!
                                    EditMealScreen(
                                        viewModel = viewModel(factory = EditMealViewModel.factory(mealId)),
                                        onBack = { navController.popBackStack() },
                                        onEditFood = { id -> navController.navigate("edit_food/$id") },
                                    )
                                }

                                composable(
                                    route = "saved_meals?date={date}",
                                    arguments = listOf(
                                        navArgument("date") {
                                            type = NavType.StringType
                                            nullable = true
                                            defaultValue = null
                                        }
                                    ),
                                ) { backStackEntry ->
                                    val dateArg = backStackEntry.arguments?.getString("date")
                                    val initialDate = if (dateArg != null) LocalDate.parse(dateArg) else LocalDate.now()
                                    val vm: SavedMealsViewModel =
                                        viewModel(factory = SavedMealsViewModel.factory(initialDate))
                                    SavedMealsScreen(
                                        viewModel = vm,
                                        onBack = { navController.popBackStack() },
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
