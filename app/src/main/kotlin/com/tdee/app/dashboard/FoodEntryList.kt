package com.tdee.app.dashboard

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tdee.app.data.FoodEntryEntity
import com.tdee.app.ui.MealMultiplierDialog
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

// ---------------------------------------------------------------------------
// Display model — shared between Dashboard and History
// ---------------------------------------------------------------------------

internal sealed interface FoodDisplayItem {
    data class Standalone(val entry: FoodEntryEntity) : FoodDisplayItem
    data class Group(val mealId: String, val items: List<FoodEntryEntity>, val mealName: String?) : FoodDisplayItem
}

internal fun List<FoodEntryEntity>.toDisplayItems(): List<FoodDisplayItem> {
    val seenMeals = mutableSetOf<String>()
    val mealItemsMap = groupBy { it.mealId }.filterKeys { it != null }
    val result = mutableListOf<FoodDisplayItem>()
    forEach { entry ->
        val mid = entry.mealId
        if (mid == null) {
            result.add(FoodDisplayItem.Standalone(entry))
        } else if (seenMeals.add(mid)) {
            result.add(FoodDisplayItem.Group(mid, mealItemsMap[mid]!!, mealItemsMap[mid]!!.firstOrNull()?.mealName))
        }
    }
    return result
}

// ---------------------------------------------------------------------------
// Reusable grouped food list composable
// ---------------------------------------------------------------------------

/**
 * Renders a grouped food entry list with optional repeat and save actions.
 *
 * When [onRepeatMeal]/[onRepeatEntry] are non-null, a "Repeat" action appears on group headers
 * and standalone rows; tapping it opens [MealMultiplierDialog] and calls back with the chosen
 * scale factor. When [onSaveMeal]/[onSaveEntry] are non-null, a "Save" action appears on group
 * headers / standalone rows respectively that opens a name dialog before calling back.
 *
 * When [onRenameMeal]/[onRenameEntry] are non-null, long-pressing a group header or an entry
 * row opens a rename dialog pre-filled with the current name.
 *
 * When [onLogMeal]/[onLogEntry] are non-null, a "Log" action appears on group headers /
 * standalone rows that opens a date picker (future dates disabled) then [MealMultiplierDialog],
 * and calls back with the chosen date and scale factor.
 *
 * When [onEditMeal] is non-null, an expanded group shows an "Edit meal" action above its items.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun FoodEntryList(
    foods: List<FoodEntryEntity>,
    onEditFood: (Long) -> Unit,
    onDeleteEntry: (Long) -> Unit,
    onDeleteMeal: (String) -> Unit,
    onRepeatMeal: ((mealId: String, factor: Double) -> Unit)? = null,
    onRepeatEntry: ((id: Long, factor: Double) -> Unit)? = null,
    onSaveMeal: ((mealId: String, name: String) -> Unit)? = null,
    onSaveEntry: ((entryId: Long, name: String) -> Unit)? = null,
    onRenameMeal: ((mealId: String, name: String) -> Unit)? = null,
    onRenameEntry: ((id: Long, name: String) -> Unit)? = null,
    onLogMeal: ((mealId: String, date: LocalDate, factor: Double) -> Unit)? = null,
    onLogEntry: ((id: Long, date: LocalDate, factor: Double) -> Unit)? = null,
    onEditMeal: ((String) -> Unit)? = null,
) {
    val displayItems = remember(foods) { foods.toDisplayItems() }
    val expandedState = remember { mutableStateMapOf<String, Boolean>() }

    // Dialog state for "Save meal" name prompt — shared by group and standalone saves.
    var savingMealId by remember { mutableStateOf<String?>(null) }
    var savingEntryId by remember { mutableStateOf<Long?>(null) }
    var saveName by remember { mutableStateOf("") }

    if (savingMealId != null || savingEntryId != null) {
        AlertDialog(
            onDismissRequest = { savingMealId = null; savingEntryId = null; saveName = "" },
            title = { Text("Save meal") },
            text = {
                OutlinedTextField(
                    value = saveName,
                    onValueChange = { saveName = it },
                    label = { Text("Meal name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val n = saveName.trim()
                        if (n.isNotBlank()) {
                            savingMealId?.let { onSaveMeal?.invoke(it, n) }
                            savingEntryId?.let { onSaveEntry?.invoke(it, n) }
                        }
                        savingMealId = null
                        savingEntryId = null
                        saveName = ""
                    },
                    enabled = saveName.isNotBlank(),
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { savingMealId = null; savingEntryId = null; saveName = "" }) {
                    Text("Cancel")
                }
            },
        )
    }

    // Dialog state for "Rename" prompt — shared by group headers and entry rows (long-press).
    var renamingMealId by remember { mutableStateOf<String?>(null) }
    var renamingEntryId by remember { mutableStateOf<Long?>(null) }
    var renameName by remember { mutableStateOf("") }

    if (renamingMealId != null || renamingEntryId != null) {
        AlertDialog(
            onDismissRequest = { renamingMealId = null; renamingEntryId = null; renameName = "" },
            title = { Text(if (renamingMealId != null) "Rename meal" else "Rename item") },
            text = {
                OutlinedTextField(
                    value = renameName,
                    onValueChange = { renameName = it },
                    label = { Text("Name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val n = renameName.trim()
                        if (n.isNotBlank()) {
                            renamingMealId?.let { onRenameMeal?.invoke(it, n) }
                            renamingEntryId?.let { onRenameEntry?.invoke(it, n) }
                        }
                        renamingMealId = null
                        renamingEntryId = null
                        renameName = ""
                    },
                    enabled = renameName.isNotBlank(),
                ) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { renamingMealId = null; renamingEntryId = null; renameName = "" }) {
                    Text("Cancel")
                }
            },
        )
    }

    // Dialog state for "Repeat" scale-factor prompt — shared by group and standalone repeats.
    var repeatingMealId by remember { mutableStateOf<String?>(null) }
    var repeatingEntryId by remember { mutableStateOf<Long?>(null) }

    if (repeatingMealId != null) {
        val mid = repeatingMealId!!
        MealMultiplierDialog(
            onConfirm = { factor -> onRepeatMeal?.invoke(mid, factor); repeatingMealId = null },
            onDismiss = { repeatingMealId = null },
        )
    }
    if (repeatingEntryId != null) {
        val eid = repeatingEntryId!!
        MealMultiplierDialog(
            onConfirm = { factor -> onRepeatEntry?.invoke(eid, factor); repeatingEntryId = null },
            onDismiss = { repeatingEntryId = null },
        )
    }

    // Dialog state for "Log" (date-pick then scale-factor) prompt — shared by group and standalone.
    var loggingMealId by remember { mutableStateOf<String?>(null) }
    var loggingEntryId by remember { mutableStateOf<Long?>(null) }
    var showLogDatePicker by remember { mutableStateOf(false) }
    var logTargetDate by remember { mutableStateOf<LocalDate?>(null) }
    val today = remember { LocalDate.now() }

    fun clearLogState() {
        loggingMealId = null
        loggingEntryId = null
        showLogDatePicker = false
        logTargetDate = null
    }

    if (showLogDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val date = Instant.ofEpochMilli(utcTimeMillis)
                        .atZone(ZoneOffset.UTC).toLocalDate()
                    return !date.isAfter(today)
                }
            },
        )
        DatePickerDialog(
            onDismissRequest = { clearLogState() },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        logTargetDate = Instant.ofEpochMilli(millis)
                            .atZone(ZoneOffset.UTC).toLocalDate()
                        showLogDatePicker = false
                    }
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { clearLogState() }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (logTargetDate != null && loggingMealId != null) {
        val mid = loggingMealId!!
        val date = logTargetDate!!
        MealMultiplierDialog(
            onConfirm = { factor -> onLogMeal?.invoke(mid, date, factor); clearLogState() },
            onDismiss = { clearLogState() },
        )
    }
    if (logTargetDate != null && loggingEntryId != null) {
        val eid = loggingEntryId!!
        val date = logTargetDate!!
        MealMultiplierDialog(
            onConfirm = { factor -> onLogEntry?.invoke(eid, date, factor); clearLogState() },
            onDismiss = { clearLogState() },
        )
    }

    if (foods.isEmpty()) {
        Text(
            "No food logged yet",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    displayItems.forEach { displayItem ->
        HorizontalDivider()
        when (displayItem) {
            is FoodDisplayItem.Standalone -> {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { onEditFood(displayItem.entry.id) },
                            onLongClick = onRenameEntry?.let {
                                {
                                    renamingEntryId = displayItem.entry.id
                                    renameName = displayItem.entry.name
                                }
                            },
                        )
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(displayItem.entry.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "%,d kcal".format(displayItem.entry.kcal.toInt()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (onSaveEntry != null) {
                        TextButton(onClick = {
                            savingEntryId = displayItem.entry.id
                            saveName = ""
                        }) {
                            Text("Save")
                        }
                    }
                    if (onLogEntry != null) {
                        TextButton(onClick = {
                            loggingEntryId = displayItem.entry.id
                            showLogDatePicker = true
                        }) {
                            Text("Log")
                        }
                    }
                    if (onRepeatEntry != null) {
                        TextButton(onClick = { repeatingEntryId = displayItem.entry.id }) {
                            Text("Repeat")
                        }
                    }
                    TextButton(onClick = { onDeleteEntry(displayItem.entry.id) }) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            is FoodDisplayItem.Group -> {
                val isExpanded = expandedState.getOrDefault(displayItem.mealId, false)
                val totalKcal = displayItem.items.sumOf { it.kcal }.toInt()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            onClick = { expandedState[displayItem.mealId] = !isExpanded },
                            onLongClick = onRenameMeal?.let {
                                {
                                    renamingMealId = displayItem.mealId
                                    renameName = displayItem.mealName.orEmpty()
                                }
                            },
                        )
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        displayItem.mealName ?: "Meal · ${displayItem.items.size} items",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "%,d kcal".format(totalKcal),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (onSaveMeal != null) {
                            TextButton(onClick = {
                                savingMealId = displayItem.mealId
                                saveName = ""
                            }) {
                                Text("Save")
                            }
                        }
                        if (onLogMeal != null) {
                            TextButton(onClick = {
                                loggingMealId = displayItem.mealId
                                showLogDatePicker = true
                            }) {
                                Text("Log")
                            }
                        }
                        if (onRepeatMeal != null) {
                            TextButton(onClick = { repeatingMealId = displayItem.mealId }) {
                                Text("Repeat")
                            }
                        }
                        TextButton(onClick = { onDeleteMeal(displayItem.mealId) }) {
                            Text("Remove meal", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                if (isExpanded) {
                    if (onEditMeal != null) {
                        Row(modifier = Modifier.fillMaxWidth().padding(start = 16.dp)) {
                            TextButton(onClick = { onEditMeal(displayItem.mealId) }) { Text("Edit meal") }
                        }
                    }
                    displayItem.items.forEach { entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp)
                                .combinedClickable(
                                    onClick = { onEditFood(entry.id) },
                                    onLongClick = onRenameEntry?.let {
                                        {
                                            renamingEntryId = entry.id
                                            renameName = entry.name
                                        }
                                    },
                                )
                                .padding(vertical = 4.dp),
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
                            TextButton(onClick = { onDeleteEntry(entry.id) }) {
                                Text("Delete", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }
}
