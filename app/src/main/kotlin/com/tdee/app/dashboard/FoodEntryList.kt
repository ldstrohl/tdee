package com.tdee.app.dashboard

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
 */
@OptIn(ExperimentalFoundationApi::class)
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
