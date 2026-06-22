package com.tdee.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tdee.app.onboarding.OnboardingScreen
import com.tdee.app.onboarding.OnboardingViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // TODO (Task C): Replace this with profile-based routing.
        //   observeProfile() emits null → show OnboardingScreen, non-null → show DashboardScreen.
        //   The OnboardingViewModel.Factory pattern below can be reused as-is; just add a
        //   DashboardViewModel factory in the same style.

        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface {
                    val vm: OnboardingViewModel = viewModel(factory = OnboardingViewModel.Factory)
                    OnboardingScreen(viewModel = vm)
                }
            }
        }
    }
}
