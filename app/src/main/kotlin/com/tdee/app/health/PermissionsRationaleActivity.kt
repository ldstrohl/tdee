package com.tdee.app.health

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Minimal permissions-rationale screen required by Health Connect.
 *
 * On API < 34 (our Pixel 3 target) Health Connect launches this activity when the
 * user opens the privacy/rationale link from the permission sheet. It only needs to
 * explain why we read weight data; it does not request the permission itself.
 */
class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Surface {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "Health Connect permissions",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        "TDEE reads your body weight measurements from Health Connect so it " +
                            "can track your weight trend and estimate your total daily energy " +
                            "expenditure. Weight data stays on your device and is never shared.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}
