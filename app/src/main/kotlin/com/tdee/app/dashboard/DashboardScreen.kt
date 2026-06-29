package com.tdee.app.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.tdee.app.data.FoodEntryEntity
import com.tdee.app.insights.ChartType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    onAddFood: (LocalDate) -> Unit = {},
    onLogText: (LocalDate) -> Unit = {},
    onAddWeight: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenInsights: () -> Unit = {},
    onCheckin: () -> Unit = {},
    onEditFood: (Long) -> Unit = {},
    onSavedMeals: (LocalDate) -> Unit = {},
    onFoodHistory: () -> Unit = {},
    onOpenChart: (ChartType) -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    val dayFoods by viewModel.dayFoods.collectAsState()
    val selectedDate by viewModel.selectedDate.collectAsState()

    val today = remember { LocalDate.now() }
    val dateLabel = if (selectedDate == today) "Today"
        else selectedDate.format(DateTimeFormatter.ofPattern("EEE, MMM d"))

    var showDatePicker by remember { mutableStateOf(false) }

    // DatePickerState requires epoch-millis in UTC; initialise from current selectedDate.
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = selectedDate
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        selectableDates = object : SelectableDates {
            override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                val date = Instant.ofEpochMilli(utcTimeMillis)
                    .atZone(ZoneOffset.UTC).toLocalDate()
                return !date.isAfter(today)
            }
        },
    )

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        val picked = Instant.ofEpochMilli(millis)
                            .atZone(ZoneOffset.UTC).toLocalDate()
                        viewModel.setSelectedDate(picked)
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }

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

        // Day navigator — swipe the food card or use these buttons to view another day.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { viewModel.prevDay() }) { Text("◀") }
            TextButton(onClick = { showDatePicker = true }) { Text(dateLabel) }
            TextButton(
                onClick = { viewModel.nextDay() },
                enabled = selectedDate != today,
            ) { Text("▶") }
            if (selectedDate != today) {
                TextButton(onClick = { viewModel.goToToday() }) { Text("Today") }
            }
        }

        when (val s = state) {
            is DashboardUiState.Loading -> {
                Text("Loading…", style = MaterialTheme.typography.bodyMedium)
            }

            is DashboardUiState.Loaded -> {
                LoadedContent(s, onCheckin, dateLabel, onOpenChart)
            }
        }

        // Food list for the selected day — reactive, updates immediately on add/delete.
        TodayFoodSection(
            foods = dayFoods,
            onAddFood = { onAddFood(selectedDate) },
            onLogText = { onLogText(selectedDate) },
            onDelete = { viewModel.deleteFood(it) },
            onDeleteMeal = { viewModel.deleteMeal(it) },
            onEditFood = onEditFood,
            onSaveMeal = { mealId, name -> viewModel.saveMealFromGroup(mealId, name) },
            onSavedMeals = { onSavedMeals(selectedDate) },
            onFoodHistory = onFoodHistory,
            onPrevDay = { viewModel.prevDay() },
            onNextDay = { viewModel.nextDay() },
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
                TextButton(onClick = onAddWeight) { Text("View & log") }
            }
        }
    }
}

@Composable
private fun LoadedContent(
    s: DashboardUiState.Loaded,
    onCheckin: () -> Unit,
    dateLabel: String,
    onOpenChart: (ChartType) -> Unit,
) {
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
    Card(modifier = Modifier.fillMaxWidth().clickable { onOpenChart(ChartType.EXPENDITURE) }) {
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

    // Selected-day card — consumed / target calories (reactive, reflects selected date)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(dateLabel, style = MaterialTheme.typography.titleMedium)
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
    Card(modifier = Modifier.fillMaxWidth().clickable { onOpenChart(ChartType.MACROS) }) {
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

// ---------------------------------------------------------------------------
// Food section
// ---------------------------------------------------------------------------

@Composable
private fun TodayFoodSection(
    foods: List<FoodEntryEntity>,
    onAddFood: () -> Unit,
    onLogText: () -> Unit,
    onDelete: (Long) -> Unit,
    onDeleteMeal: (String) -> Unit,
    onEditFood: (Long) -> Unit,
    onSaveMeal: (mealId: String, name: String) -> Unit,
    onSavedMeals: () -> Unit,
    onFoodHistory: () -> Unit,
    onPrevDay: () -> Unit,
    onNextDay: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                var totalDrag = 0f
                detectHorizontalDragGestures(
                    onDragStart = { totalDrag = 0f },
                    onDragEnd = {
                        val threshold = 80.dp.toPx()
                        if (totalDrag > threshold) onPrevDay()
                        else if (totalDrag < -threshold) onNextDay()
                        totalDrag = 0f
                    },
                    onDragCancel = { totalDrag = 0f },
                ) { _, dragAmount ->
                    totalDrag += dragAmount
                }
            },
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Food", style = MaterialTheme.typography.titleMedium)
                Row {
                    TextButton(onClick = onLogText) { Text("Describe a meal") }
                    TextButton(onClick = onAddFood) { Text("+ Add") }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onSavedMeals) { Text("Saved meals") }
                TextButton(onClick = onFoodHistory) { Text("History") }
            }

            FoodEntryList(
                foods = foods,
                onEditFood = onEditFood,
                onDeleteEntry = onDelete,
                onDeleteMeal = onDeleteMeal,
                onSaveMeal = onSaveMeal,
            )
        }
    }
}
