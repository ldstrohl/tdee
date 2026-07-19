package com.tdee.app.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tdee.app.data.MealSearchResult
import com.tdee.app.ui.MealMultiplierDialog
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun MealSearchScreen(
    viewModel: MealSearchViewModel,
    onBack: () -> Unit,
) {
    val query by viewModel.query.collectAsState()
    val results by viewModel.results.collectAsState()
    val justLogged by viewModel.justLogged.collectAsState()

    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    // Result awaiting a scale-factor pick before logging.
    var logging by remember { mutableStateOf<MealSearchResult?>(null) }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    if (logging != null) {
        val result = logging!!
        MealMultiplierDialog(
            onConfirm = { factor ->
                viewModel.log(result, factor)
                logging = null
            },
            onDismiss = { logging = null },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Search meals", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack) { Text("Back") }
        }

        OutlinedTextField(
            value = query,
            onValueChange = viewModel::setQuery,
            singleLine = true,
            placeholder = { Text("Meal or food name") },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
        )

        if (results.isEmpty()) {
            Text(
                text = if (query.isBlank()) "Type to search saved meals and your food log." else "No matches.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(results, key = { it.key }) { result ->
                    MealSearchResultRow(
                        result = result,
                        expanded = expanded[result.key] == true,
                        onToggleExpanded = { expanded[result.key] = expanded[result.key] != true },
                        justLogged = justLogged == result.key,
                        onLog = { logging = result },
                    )
                }
            }
        }
    }
}

@Composable
private fun MealSearchResultRow(
    result: MealSearchResult,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    justLogged: Boolean,
    onLog: () -> Unit,
) {
    val sourceLabel = remember(result) {
        when (result) {
            is MealSearchResult.Saved -> "Saved"
            is MealSearchResult.LoggedMeal -> "Logged " + result.lastLogged
                .atZone(ZoneId.systemDefault()).toLocalDate()
                .format(DateTimeFormatter.ofPattern("MMM d"))
            is MealSearchResult.LoggedEntry -> "Logged " + result.lastLogged
                .atZone(ZoneId.systemDefault()).toLocalDate()
                .format(DateTimeFormatter.ofPattern("MMM d"))
        }
    }
    val totals = remember(result) {
        Quad(
            result.items.sumOf { it.kcal },
            result.items.sumOf { it.proteinG },
            result.items.sumOf { it.fatG },
            result.items.sumOf { it.carbG },
        )
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpanded),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        result.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "%,d kcal · P %d F %d C %d".format(
                            totals.kcal.toInt(),
                            totals.proteinG.toInt(),
                            totals.fatG.toInt(),
                            totals.carbG.toInt(),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        sourceLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (justLogged) {
                        Text(
                            "Added.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                TextButton(onClick = onLog) { Text("Log") }
            }

            if (expanded) {
                Column(modifier = Modifier.padding(top = 4.dp)) {
                    result.items.forEach { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                item.name,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "%,d kcal".format(item.kcal.toInt()),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class Quad(val kcal: Double, val proteinG: Double, val fatG: Double, val carbG: Double)
