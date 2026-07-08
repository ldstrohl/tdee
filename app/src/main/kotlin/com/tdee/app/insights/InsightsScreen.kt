package com.tdee.app.insights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tdee.app.BuildConfig

// ---------------------------------------------------------------------------
// Screen
// ---------------------------------------------------------------------------

@Composable
fun InsightsScreen(
    viewModel: InsightsViewModel,
    onBack: () -> Unit,
    onHelp: () -> Unit = {},
    onMaximize: (ChartType) -> Unit = {},
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
            Text("Insights", style = MaterialTheme.typography.headlineSmall)
            Row {
                TextButton(onClick = onHelp) { Text("?") }
                TextButton(onClick = onBack) { Text("Back") }
            }
        }

        // Trend chart section
        WeightTrendPanel(
            points = state.visiblePoints,
            selectedRange = state.selectedRange,
            onRangeSelected = { viewModel.setRange(it) },
            predictionOn = state.predictionOn,
            onPredictionToggle = { viewModel.setPrediction(!state.predictionOn) },
            projection = state.projection,
            isLoading = state.isLoading,
            onMaximize = { onMaximize(ChartType.TREND) },
        )

        // Expenditure chart section (independent range)
        ExpenditureChartSection(
            points = state.visibleExpenditurePoints,
            selectedRange = state.expenditureRange,
            onRangeSelected = { viewModel.setExpenditureRange(it) },
            onMaximize = { onMaximize(ChartType.EXPENDITURE) },
        )

        // Macro donut section (independent window)
        MacroDonutSection(
            summary = state.macroSummary,
            selectedWindow = state.macroWindow,
            onWindowSelected = { viewModel.setMacroWindow(it) },
        )

        // Debug-only seed button
        if (BuildConfig.DEBUG) {
            Button(
                onClick = { viewModel.seedAndReload() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
            ) {
                Text("Load sample data (debug)")
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Pill composable
// ---------------------------------------------------------------------------

@Composable
internal fun Pill(label: String, active: Boolean, onClick: () -> Unit) {
    val containerColor =
        if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val contentColor =
        if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant

    Button(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        modifier = Modifier.height(32.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall)
    }
}
