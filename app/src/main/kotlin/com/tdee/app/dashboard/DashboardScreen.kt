package com.tdee.app.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tdee.app.data.FoodEntryEntity

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    onAddFood: () -> Unit = {},
    onAddWeight: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenInsights: () -> Unit = {},
    onCheckin: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    val todayFoods by viewModel.todayFoods.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Dashboard", style = MaterialTheme.typography.headlineSmall)
            Row {
                TextButton(onClick = onOpenInsights) { Text("Insights") }
                TextButton(onClick = onOpenSettings) { Text("Settings") }
            }
        }

        // Always-available on-demand entry point: check in or edit targets any time.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onCheckin) { Text("Check in / adjust targets") }
        }

        when (val s = state) {
            is DashboardUiState.Loading -> {
                Text("Loading…", style = MaterialTheme.typography.bodyMedium)
            }

            is DashboardUiState.Loaded -> {
                LoadedContent(s, onCheckin)
            }
        }

        // Today's food list — reactive, updates immediately on add/delete.
        TodayFoodSection(
            foods = todayFoods,
            onAddFood = onAddFood,
            onDelete = { viewModel.deleteFood(it) },
        )

        // Weight logging entry point
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Weight", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onAddWeight) { Text("Log weight") }
            }
        }
    }
}

@Composable
private fun LoadedContent(s: DashboardUiState.Loaded, onCheckin: () -> Unit) {
    // Weekly "check-in due" nudge — a clear banner that opens the check-in screen.
    if (s.checkinDue) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Check-in due", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Review your TDEE and update targets.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onCheckin) { Text("Check in") }
            }
        }
    }

    // TDEE card
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "%,d kcal".format(s.tdeeKcal),
                    style = MaterialTheme.typography.headlineMedium,
                )
                if (s.calibrating) {
                    Text(
                        text = "Calibrating",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }
            Text("TDEE · ${s.tdeeMethod.name.lowercase().replaceFirstChar { it.uppercaseChar() }}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    // Trend weight line
    Text(
        text = "Trend weight: %.1f lb".format(s.trendWeightLb),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 4.dp),
    )

    // Today card — consumed / target calories (reactive)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Today", style = MaterialTheme.typography.titleMedium)
            val consumed = s.todayConsumedKcal
            if (consumed != null) {
                Text(
                    text = "%,d / %,d kcal".format(consumed, s.calorieTargetKcal),
                    style = MaterialTheme.typography.bodyLarge,
                )
            } else {
                Text(
                    text = "Target: %,d kcal".format(s.calorieTargetKcal),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = "No food logged yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    // Macros card — consumed vs target per macro
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Macros", style = MaterialTheme.typography.titleMedium)
            MacroRow(
                label = "Protein",
                consumed = s.consumedTotals.proteinG,
                target = s.macroTargets.proteinG.toInt(),
                unit = "g",
            )
            MacroRow(
                label = "Fat",
                consumed = s.consumedTotals.fatG,
                target = s.macroTargets.fatG.toInt(),
                unit = "g",
            )
            MacroRow(
                label = "Carbs",
                consumed = s.consumedTotals.carbG,
                target = s.macroTargets.carbG.toInt(),
                unit = "g",
            )
        }
    }
}

@Composable
private fun MacroRow(label: String, consumed: Int, target: Int, unit: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text("%d / %d %s".format(consumed, target, unit), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun TodayFoodSection(
    foods: List<FoodEntryEntity>,
    onAddFood: () -> Unit,
    onDelete: (Long) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Today's food", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onAddFood) { Text("+ Add") }
            }

            if (foods.isEmpty()) {
                Text(
                    "No food logged yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                foods.forEach { entry ->
                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(entry.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "%,d kcal".format(entry.kcal.toInt()),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { onDelete(entry.id) }) {
                            Text("Delete", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        }
    }
}
