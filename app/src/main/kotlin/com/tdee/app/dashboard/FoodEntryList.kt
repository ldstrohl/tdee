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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tdee.app.data.FoodEntryEntity
import com.tdee.app.ui.MealMultiplierDialog
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

// ---------------------------------------------------------------------------
// Display model
// ---------------------------------------------------------------------------

internal sealed interface FoodDisplayItem {
    data class Standalone(val entry: FoodEntryEntity) : FoodDisplayItem
    data class Group(val mealId: String, val items: List<FoodEntryEntity>, val mealName: String?) : FoodDisplayItem
}

/** Formats a scale factor for display, trimming trailing zeros (2.0 -> "2", 1.5 -> "1.5"). */
internal fun formatFactor(factor: Double): String =
    if (factor == factor.toLong().toDouble()) factor.toLong().toString() else factor.toString()

/** Suffix appended to an entry's displayed name when its stored [FoodEntryEntity.scaleFactor]
 * differs from 1.0, e.g. " ×2"; empty string when unscaled. */
private fun FoodEntryEntity.scaleSuffix(): String =
    if (kotlin.math.abs(scaleFactor - 1.0) > 1e-9) " ×${formatFactor(scaleFactor)}" else ""

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
 * Renders a grouped food entry list with optional save actions.
 *
 * When [onSaveMeal]/[onSaveEntry] are non-null, a "Save" action appears on group
 * headers / standalone rows respectively that opens a name dialog before calling back.
 *
 * Long-pressing a group header or an entry row opens an action menu (Rename / Save / Remove
 * or Delete, depending on which callbacks are non-null) instead of acting directly.
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
    onSaveMeal: ((mealId: String, name: String) -> Unit)? = null,
    onSaveEntry: ((entryId: Long, name: String) -> Unit)? = null,
    onRenameMeal: ((mealId: String, name: String) -> Unit)? = null,
    onRenameEntry: ((id: Long, name: String) -> Unit)? = null,
    onEditMeal: ((String) -> Unit)? = null,
    onLogMealToDate: ((mealId: String, date: LocalDate, factor: Double) -> Unit)? = null,
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

    // Dialog state for the long-press action menu — shared by group headers and entry rows.
    var actionsMealId by remember { mutableStateOf<String?>(null) }
    var actionsMealName by remember { mutableStateOf("") }
    var actionsEntryId by remember { mutableStateOf<Long?>(null) }
    var actionsEntryName by remember { mutableStateOf("") }

    // Dialog state for "Log to another day" — date picker, then a multiplier dialog.
    val today = remember { LocalDate.now() }
    var loggingMealId by remember { mutableStateOf<String?>(null) }
    var showLogDatePicker by remember { mutableStateOf(false) }
    var logTargetDate by remember { mutableStateOf<LocalDate?>(null) }

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
            onDismissRequest = { showLogDatePicker = false },
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
                TextButton(onClick = { showLogDatePicker = false }) { Text("Cancel") }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (logTargetDate != null && loggingMealId != null) {
        val date = logTargetDate!!
        val mid = loggingMealId!!
        MealMultiplierDialog(
            onConfirm = { factor ->
                onLogMealToDate?.invoke(mid, date, factor)
                logTargetDate = null
                loggingMealId = null
            },
            onDismiss = { logTargetDate = null; loggingMealId = null },
        )
    }

    if (actionsMealId != null) {
        val mid = actionsMealId!!
        AlertDialog(
            onDismissRequest = { actionsMealId = null },
            title = { Text(actionsMealName.ifBlank { "Meal" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    if (onRenameMeal != null) {
                        TextButton(
                            onClick = {
                                renamingMealId = mid
                                renameName = actionsMealName
                                actionsMealId = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Rename") }
                    }
                    if (onSaveMeal != null) {
                        TextButton(
                            onClick = {
                                savingMealId = mid
                                saveName = ""
                                actionsMealId = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Save as meal") }
                    }
                    if (onLogMealToDate != null) {
                        TextButton(
                            onClick = {
                                loggingMealId = mid
                                actionsMealId = null
                                showLogDatePicker = true
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Log to another day") }
                    }
                    TextButton(
                        onClick = {
                            onDeleteMeal(mid)
                            actionsMealId = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Remove meal", color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { actionsMealId = null }) { Text("Cancel") } },
        )
    }

    if (actionsEntryId != null) {
        val eid = actionsEntryId!!
        AlertDialog(
            onDismissRequest = { actionsEntryId = null },
            title = { Text(actionsEntryName.ifBlank { "Item" }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column {
                    if (onRenameEntry != null) {
                        TextButton(
                            onClick = {
                                renamingEntryId = eid
                                renameName = actionsEntryName
                                actionsEntryId = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Rename") }
                    }
                    if (onSaveEntry != null) {
                        TextButton(
                            onClick = {
                                savingEntryId = eid
                                saveName = ""
                                actionsEntryId = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("Save") }
                    }
                    TextButton(
                        onClick = {
                            onDeleteEntry(eid)
                            actionsEntryId = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { actionsEntryId = null }) { Text("Cancel") } },
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
                            onLongClick = {
                                actionsEntryId = displayItem.entry.id
                                actionsEntryName = displayItem.entry.name
                            },
                        )
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            displayItem.entry.name + displayItem.entry.scaleSuffix(),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "%,d kcal".format(displayItem.entry.kcal.toInt()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
                            onLongClick = {
                                actionsMealId = displayItem.mealId
                                actionsMealName = displayItem.mealName.orEmpty()
                            },
                        )
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        displayItem.mealName ?: "Meal · ${displayItem.items.size} items",
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "%,d kcal".format(totalKcal),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
                                    onLongClick = {
                                        actionsEntryId = entry.id
                                        actionsEntryName = entry.name
                                    },
                                )
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    entry.name + entry.scaleSuffix(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
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
