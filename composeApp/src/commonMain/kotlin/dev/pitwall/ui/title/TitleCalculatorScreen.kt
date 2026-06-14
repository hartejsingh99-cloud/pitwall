package dev.pitwall.ui.title

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.pitwall.data.TitleRow
import dev.pitwall.data.TitleState
import dev.pitwall.domain.TitleStatus
import kotlin.math.abs
import kotlin.math.round
import org.koin.compose.viewmodel.koinViewModel

/** Points as a compact string: drop trailing ".0", else one decimal. Multiplatform-safe (no String.format). */
private fun fmtPoints(v: Double): String {
    val rounded = round(v * 10.0) / 10.0
    val whole = rounded.toLong()
    return if (rounded == whole.toDouble()) whole.toString()
    else {
        val tenths = round(abs(rounded - whole) * 10.0).toLong()
        "$whole.$tenths"
    }
}

private fun signedPoints(v: Double): String = if (v <= 0.0) "leader" else "−${fmtPoints(v)}"

@Composable
fun TitleCalculatorScreen(vm: TitleCalculatorViewModel = koinViewModel()) {
    val s by vm.state.collectAsState()
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Title Calculator — who can still win", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Mathematical permutation of the championship from the current standings",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))

        // Drivers / Constructors toggle
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = !s.isConstructor,
                onClick = { vm.setConstructor(false) },
                label = { Text("Drivers") },
            )
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = s.isConstructor,
                onClick = { vm.setConstructor(true) },
                label = { Text("Constructors") },
            )
        }
        Spacer(Modifier.height(8.dp))

        // Season navigation (older / newer)
        if (s.seasons.isNotEmpty()) {
            s.selectedYear?.let { y ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Season: $y", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(12.dp))
                    val idx = s.seasons.indexOf(y)
                    Button(enabled = idx < s.seasons.lastIndex, onClick = { vm.selectYear(s.seasons[idx + 1]) }) { Text("◀ older") }
                    Spacer(Modifier.width(8.dp))
                    Button(enabled = idx > 0, onClick = { vm.selectYear(s.seasons[idx - 1]) }) { Text("newer ▶") }
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        val error = s.error
        val state = s.state
        when {
            s.loading -> CircularProgressIndicator()
            error != null -> Text(error, style = MaterialTheme.typography.bodyLarge)
            state is TitleState.Empty -> Text(
                "No standings recorded for ${state.year} in the bundled dataset.",
                style = MaterialTheme.typography.bodyLarge,
            )
            state is TitleState.HistoricalOnly -> HistoricalView(state)
            state is TitleState.Projection -> ProjectionView(state, onStrictChange = { vm.setStrict(it) })
            else -> Text("Select a season to begin.", style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun ProjectionView(state: TitleState.Projection, onStrictChange: (Boolean) -> Unit) {
    Column {
        // Header: X GPs and Y sprints remaining · max +Z available
        Text(
            "${state.remainingGps} GPs and ${state.remainingSprints} sprints remaining · max +${state.maxAvailable} available",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(4.dp))

        // Data freshness stamp (prominent) — dynamic, derived from the latest run round.
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = RoundedCornerShape(6.dp),
        ) {
            Text(
                state.dataStamp,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        Spacer(Modifier.height(6.dp))

        // Clinch line
        state.clinchMessage?.let { msg ->
            Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(6.dp), modifier = Modifier.fillMaxWidth()) {
                Text(msg, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(10.dp))
            }
            Spacer(Modifier.height(6.dp))
        }

        // Simple / Strict toggle
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Reach leader", style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.width(8.dp))
            Switch(checked = state.strict, onCheckedChange = onStrictChange)
            Spacer(Modifier.width(8.dp))
            Text("Beat best rival (strict)", style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(8.dp))

        LazyColumn(Modifier.fillMaxSize()) {
            items(state.rows) { row -> ProjectionRow(row) }
        }
    }
}

@Composable
private fun ProjectionRow(row: TitleRow) {
    ListItem(
        leadingContent = { Text(row.positionText, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium) },
        headlineContent = { Text(row.name, fontWeight = FontWeight.SemiBold) },
        supportingContent = {
            val max = row.maxReachable?.let { fmtPoints(it) } ?: "—"
            val gap = row.gapToLeader?.let { signedPoints(it) } ?: "—"
            Text("${fmtPoints(row.points)} pts · max reachable $max · vs leader $gap")
        },
        trailingContent = { row.status?.let { StatusBadge(it) } },
    )
    HorizontalDivider()
}

@Composable
private fun StatusBadge(status: TitleStatus) {
    val alive = status == TitleStatus.ALIVE
    val container = if (alive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
    val content = if (alive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
    Surface(color = container, shape = RoundedCornerShape(8.dp)) {
        Text(
            if (alive) "Alive" else "Eliminated",
            color = content,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun HistoricalView(state: TitleState.HistoricalOnly) {
    Column {
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(6.dp), modifier = Modifier.fillMaxWidth()) {
            Text(
                "Permutation projection available for 2010-onward seasons only.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(10.dp),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(state.dataStamp, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("${state.year} final standings", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(state.rows) { row ->
                ListItem(
                    leadingContent = { Text(row.positionText, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium) },
                    headlineContent = { Text(row.name, fontWeight = FontWeight.SemiBold) },
                    supportingContent = { Text("${fmtPoints(row.points)} pts") },
                )
                HorizontalDivider()
            }
        }
    }
}
