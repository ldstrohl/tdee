package com.tdee.app.weight

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tdee.app.insights.Pill
import com.tdee.app.insights.ProjectionUi
import com.tdee.app.insights.WeightRange
import com.tdee.app.insights.WeightTrendChart

@Composable
fun WeightScreen(
    viewModel: WeightViewModel,
    onBack: () -> Unit,
    onLogManual: () -> Unit,
    onExpandChart: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Weight", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack) { Text("Back") }
        }

        // Headline numbers
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.currentTrendLb?.let { lb ->
                Text("%.1f lb".format(lb), style = MaterialTheme.typography.headlineMedium)
            }
            state.weeklyRateLb?.let { rate ->
                Text(
                    "%+.1f lb/wk".format(rate),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Trend chart section
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Trend", style = MaterialTheme.typography.titleMedium)
            if (state.visiblePoints.size >= 2) {
                TextButton(onClick = onExpandChart) { Text("Expand ⤢") }
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            WeightRange.values().forEach { range ->
                Pill(
                    label = range.label,
                    active = range == state.selectedRange,
                    onClick = { viewModel.setRange(range) },
                )
            }
        }
        when {
            state.isLoading -> {
                Box(
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Loading…", style = MaterialTheme.typography.bodyMedium)
                }
            }
            state.visiblePoints.size < 2 -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Add weigh-ins to see your trend",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            else -> {
                WeightTrendChart(
                    points = state.visiblePoints,
                    goalLb = state.goalLb,
                    projection = ProjectionUi.NoGoal,
                )
            }
        }

        // Health Connect sync
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Health Connect", style = MaterialTheme.typography.titleMedium)
                when (state.hc) {
                    HcAvailability.CONNECTED -> {
                        Text(
                            "Auto-imports your weigh-ins. Pull the latest now:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = viewModel::syncFromHealthConnect,
                            enabled = !state.syncing,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (state.syncing) "Syncing…" else "Sync now")
                        }
                        Text(
                            "Missing older weigh-ins? Pull your complete history:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(
                            onClick = viewModel::reimportFullHistory,
                            enabled = !state.syncing,
                        ) {
                            Text("Re-import full history")
                        }
                    }
                    HcAvailability.NEEDS_SETUP -> Text(
                        "Connect Health Connect in Settings to auto-import your weight.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HcAvailability.UNAVAILABLE -> Text(
                        "Health Connect isn't available on this device. Log weight manually below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HcAvailability.UNKNOWN -> Unit
                }
                state.syncStatus?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        // Manual entry (the exception — weight normally flows in from Health Connect)
        OutlinedButton(onClick = onLogManual, modifier = Modifier.fillMaxWidth()) {
            Text("Log weight manually")
        }
    }
}
