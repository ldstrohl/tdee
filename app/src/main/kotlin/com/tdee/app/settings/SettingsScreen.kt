package com.tdee.app.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tdee.app.ui.theme.ThemePreference

/**
 * App settings. Currently just the theme selector (Light / Dark / System).
 */
@Composable
fun SettingsScreen(
    current: ThemePreference,
    onSelect: (ThemePreference) -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text("Settings", style = MaterialTheme.typography.headlineSmall)
        }

        Text(
            "Theme",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        )

        ThemePreference.entries.forEach { option ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(option) }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RadioButton(
                    selected = option == current,
                    onClick = { onSelect(option) },
                )
                Text(option.label(), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

private fun ThemePreference.label(): String = when (this) {
    ThemePreference.LIGHT -> "Light"
    ThemePreference.DARK -> "Dark"
    ThemePreference.SYSTEM -> "System default"
}
