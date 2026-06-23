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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tdee.app.BuildConfig
import com.tdee.app.ui.theme.ThemePreference

/**
 * One-line status of the Health Connect connection, surfaced in Settings.
 */
sealed interface HealthConnectUiState {
    /** Initial / checking availability. */
    data object Loading : HealthConnectUiState

    /** HC not installed or needs an update — entry tappable but shows guidance. */
    data class Unavailable(val message: String) : HealthConnectUiState

    /** HC available, permission not yet granted — tapping requests it. */
    data object NeedsPermission : HealthConnectUiState

    /** Permission granted; [lastImported] is the most recent sync count, if any. */
    data class Connected(val lastImported: Int?) : HealthConnectUiState

    /** A sync/permission action is in flight. */
    data object Working : HealthConnectUiState
}

/**
 * App settings. Theme selector, "Edit profile", and the Health Connect connection entry.
 */
@Composable
fun SettingsScreen(
    current: ThemePreference,
    onSelect: (ThemePreference) -> Unit,
    onBack: () -> Unit,
    onEditProfile: () -> Unit = {},
    healthConnectState: HealthConnectUiState = HealthConnectUiState.Loading,
    onHealthConnectTap: () -> Unit = {},
    debugWriteStatus: String? = null,
    onDebugWriteSampleWeights: (() -> Unit)? = null,
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

        // Edit profile entry
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onEditProfile() }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Edit profile", style = MaterialTheme.typography.bodyLarge)
        }

        // Health Connect entry
        HealthConnectEntry(state = healthConnectState, onTap = onHealthConnectTap)

        // Debug-only Health Connect test-data writer.
        if (BuildConfig.DEBUG && onDebugWriteSampleWeights != null) {
            Button(
                onClick = onDebugWriteSampleWeights,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Write sample weights to HC (debug)")
            }
            if (debugWriteStatus != null) {
                Text(
                    debugWriteStatus,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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

@Composable
private fun HealthConnectEntry(
    state: HealthConnectUiState,
    onTap: () -> Unit,
) {
    // Connected + idle and Working states don't have a primary tap action beyond
    // "Sync now"; Loading/Unavailable/NeedsPermission all drive the same onTap which
    // the host interprets based on current state.
    val enabled = state !is HealthConnectUiState.Working
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (enabled) it.clickable { onTap() } else it }
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text("Health Connect", style = MaterialTheme.typography.bodyLarge)
        val subtitle = when (state) {
            HealthConnectUiState.Loading -> "Checking availability…"
            is HealthConnectUiState.Unavailable -> state.message
            HealthConnectUiState.NeedsPermission -> "Tap to connect and import weight history"
            HealthConnectUiState.Working -> "Syncing…"
            is HealthConnectUiState.Connected ->
                when (val n = state.lastImported) {
                    null -> "Connected — tap to sync now"
                    else -> "Connected — imported $n new ${if (n == 1) "entry" else "entries"}. Tap to sync now"
                }
        }
        Text(
            subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun ThemePreference.label(): String = when (this) {
    ThemePreference.LIGHT -> "Light"
    ThemePreference.DARK -> "Dark"
    ThemePreference.SYSTEM -> "System default"
}
