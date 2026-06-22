package com.tdee.app.insights

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun HelpScreen(onBack: () -> Unit) {
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
            Text("How these charts work", style = MaterialTheme.typography.headlineSmall)
            TextButton(onClick = onBack) { Text("Back") }
        }

        HelpSection(
            title = "Weight Trend",
            body = "Your daily weigh-ins bounce around due to water retention, sodium, and glycogen — " +
                "not real fat change. The small dots are your raw readings; the smooth line is a 14-day " +
                "exponential moving average (EMA) that filters out that noise so you can see what your " +
                "weight is actually doing over time.\n\n" +
                "The EMA weights recent days more heavily than older ones (alpha = 2/15), so it tracks " +
                "your true trend without being thrown off by a salty dinner or a hard workout.",
        )

        HelpSection(
            title = "Prediction",
            body = "When you have a goal weight set, the Prediction overlay draws two dashed lines " +
                "from today's trend value to your goal:\n\n" +
                "Goal pace — the date you'd reach your goal if you lost or gained at exactly your " +
                "chosen rate (e.g. 0.5 lb/week).\n\n" +
                "Current pace — the date you'd arrive based on your actual recent slope. If your " +
                "trend is flat or moving the wrong way, this shows \"not on track\" instead of a date.\n\n" +
                "Toggle Prediction on or off — it doesn't affect the history range pills.",
        )

        HelpSection(
            title = "Expenditure & TDEE",
            body = "The bars show your logged calorie intake each day. The line is your computed TDEE " +
                "(total daily energy expenditure) — the calories your body actually burned, measured " +
                "from your own data.\n\n" +
                "When a bar falls below the TDEE line, the gap is shaded green to show your deficit " +
                "for that day. Bars above the line are a surplus.\n\n" +
                "TDEE is calculated as: average intake minus trend change times 3,500 kcal per pound, " +
                "divided by the window. For the first ~2 weeks it uses a formula-based estimate while " +
                "it calibrates to your real metabolism.",
        )

        HelpSection(
            title = "Macros",
            body = "The donut shows how your calories split between protein, fat, and carbs. The bars " +
                "on the right show how many grams you consumed versus your daily targets.\n\n" +
                "\"Today\" reflects live numbers for the current day. Longer windows (1 month, 3 months, " +
                "etc.) show your average day — but only days where you logged food count toward that " +
                "average. If you skipped logging on some days, the caption tells you how many complete " +
                "days are included (e.g. \"24 of 30 days logged\"), so gaps never pull the average down.",
        )

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun HelpSection(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(
            modifier = Modifier.padding(top = 8.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
    }
}
