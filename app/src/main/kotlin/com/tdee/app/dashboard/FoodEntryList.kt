package com.tdee.app.dashboard

import androidx.compose.foundation.clickable
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

// ---------------------------------------------------------------------------
// Display model — shared between Dashboard and History
// ---------------------------------------------------------------------------

internal sealed interface FoodDisplayItem {
    data class Standalone(val entry: FoodEntryEntity) : FoodDisplayItem
    data class Group(val mealId: String, val items: List<FoodEntryEntity>) : FoodDisplayItem
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
            result.add(FoodDisplayItem.Group(mid, mealItemsMap[mid]!!))
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
 * and standalone rows. When [onSaveMeal] is non-null, a "Save" action appears on group headers
 * that opens a name dialog before calling back.
 */
@Composable
internal fun FoodEntryList(
    foods: List<FoodEntryEntity>,
    onEditFood: (Long) -> Unit,
    onDeleteEntry: (Long) -> Unit,
    onDeleteMeal: (String) -> Unit,
    onRepeatMeal: ((String) -> Unit)? = null,
    onRepeatEntry: ((Long) -> Unit)? = null,
    onSaveMeal: ((mealId: String, name: String) -> Unit)? = null,
) {
    val displayItems = remember(foods) { foods.toDisplayItems() }
    val expandedState = remember { mutableStateMapOf<String, Boolean>() }

    // Dialog state for "Save meal" name prompt
    var savingMealId by remember { mutableStateOf<String?>(null) }
    var saveName by remember { mutableStateOf("") }

    if (savingMealId != null) {
        AlertDialog(
            onDismissRequest = { savingMealId = null; saveName = "" },
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
                        val mid = savingMealId
                        val n = saveName.trim()
                        if (mid != null && n.isNotBlank()) {
                            onSaveMeal?.invoke(mid, n)
                        }
                        savingMealId = null
                        saveName = ""
                    },
                    enabled = saveName.isNotBlank(),
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { savingMealId = null; saveName = "" }) { Text("Cancel") }
            },
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
                        .clickable { onEditFood(displayItem.entry.id) }
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
                    if (onRepeatEntry != null) {
                        TextButton(onClick = { onRepeatEntry(displayItem.entry.id) }) {
                            Text("Repeat")
                        }
                    }
                    TextButton(onClick = { onDeleteEntry(displayItem.entry.id) }) {
                        Text("Delete", color = MaterialTheme.colorScheme.error)
                    }
                }
            }

            is FoodDisplayItem.Group -> {
                val isExpanded = expandedState.getOrDefault(displayItem.mealId, true)
                val totalKcal = displayItem.items.sumOf { it.kcal }.toInt()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            expandedState[displayItem.mealId] = !isExpanded
                        }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Meal · ${displayItem.items.size} items",
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
                            TextButton(onClick = { onRepeatMeal(displayItem.mealId) }) {
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
                                .clickable { onEditFood(entry.id) }
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
