package dev.pitwall.ui.records

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.pitwall.domain.RecordScope
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun RecordsScreen(vm: RecordsViewModel = koinViewModel()) {
    val s by vm.state.collectAsState()
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Records Book", style = MaterialTheme.typography.headlineSmall)
        Text(
            "All-time leaderboards. Pole counts the grid-determining flag (not a quali P1); " +
                "Fastest Laps count the achievement itself (the FL championship point was abolished after 2024).",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))

        // Drivers / Constructors toggle (stable FilterChips — no experimental Material3 APIs)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RecordScope.entries.forEach { scope ->
                FilterChip(
                    selected = s.scope == scope,
                    onClick = { vm.selectScope(scope) },
                    label = { Text(if (scope == RecordScope.DRIVERS) "Drivers" else "Constructors") },
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        // Metric tabs (scrollable — up to 8 metrics)
        if (s.metrics.isNotEmpty()) {
            val selectedIdx = s.metrics.indexOf(s.metric).coerceAtLeast(0)
            ScrollableTabRow(selectedTabIndex = selectedIdx, edgePadding = 0.dp) {
                s.metrics.forEachIndexed { i, m ->
                    Tab(
                        selected = i == selectedIdx,
                        onClick = { vm.selectMetric(m) },
                        text = { Text(m.label) },
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        EraFilterRow(
            from = s.era.yearFrom,
            to = s.era.yearTo,
            computedActive = s.computedActive,
            eraSupported = s.eraSupported,
            onPreset = vm::applyEra,
            onClear = vm::clearEra,
        )
        Spacer(Modifier.height(8.dp))

        if (s.loading) {
            CircularProgressIndicator()
        } else if (s.rows.isEmpty()) {
            Text("No records for this selection.", style = MaterialTheme.typography.bodyLarge)
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(s.rows) { ranked ->
                    ListItem(
                        leadingContent = {
                            Text(
                                "${ranked.rank}.",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.width(36.dp),
                            )
                        },
                        headlineContent = { Text(ranked.row.name, fontWeight = FontWeight.SemiBold) },
                        trailingContent = {
                            Text(ranked.row.value, style = MaterialTheme.typography.titleMedium)
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

/** A small row of era presets (a free-text year picker is out of scope for a $0 offline build). */
@Composable
private fun EraFilterRow(
    from: Int?,
    to: Int?,
    computedActive: Boolean,
    eraSupported: Boolean,
    onPreset: (Int?, Int?) -> Unit,
    onClear: () -> Unit,
) {
    val presets = listOf(
        Triple("All-time", null, null),
        Triple("Turbo-hybrid 2014+", 2014, null),
        Triple("V10 era 2000-05", 2000, 2005),
        Triple("Schumacher–Ferrari 2000-04", 2000, 2004),
        Triple("RBR 2010-13", 2010, 2013),
    )
    Column {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.forEach { (label, f, t) ->
                FilterChip(
                    selected = from == f && to == t,
                    onClick = { if (f == null && t == null) onClear() else onPreset(f, t) },
                    label = { Text(label) },
                )
            }
        }
        val era = from to to
        if (era.first != null || era.second != null) {
            Spacer(Modifier.height(4.dp))
            val note = if (eraSupported && computedActive) {
                "Era filter active — counts computed from race results."
            } else {
                "Era filter ignored — this metric only has an all-time total."
            }
            Text(note, style = MaterialTheme.typography.bodySmall)
        }
    }
}
