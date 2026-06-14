package dev.pitwall.ui.telemetry

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.pitwall.data.TELEMETRY_LABEL
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

/** Level 1: choose a session. */
@Composable
fun TelemetrySessionsScreen(onOpen: (String) -> Unit, vm: TelemetrySessionsViewModel = koinViewModel()) {
    val s by vm.state.collectAsState()
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Car Data", style = MaterialTheme.typography.headlineSmall)
        Text(TELEMETRY_LABEL, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        val error = s.error
        when {
            s.loading -> CircularProgressIndicator()
            error != null -> Text(error, style = MaterialTheme.typography.bodyLarge)
            s.sessions.isEmpty() -> Text(
                "No car data is bundled. Run the bake (tools/bake) and re-bundle telemetry.db.",
                style = MaterialTheme.typography.bodyLarge,
            )
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(s.sessions) { sess ->
                    ListItem(
                        headlineContent = {
                            Text("${sess.year} · ${sess.event} · ${sessionTypeLabel(sess.type)}", fontWeight = FontWeight.SemiBold)
                        },
                        supportingContent = { Text("Round ${sess.round} · ${sess.circuit}") },
                        modifier = Modifier.fillMaxWidth().clickable { onOpen(sess.id) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

/** Level 2: one session — driver/lap pick + tabbed charts. */
@Composable
fun TelemetrySessionScreen(sessionId: String) {
    val vm: TelemetrySessionViewModel = koinViewModel(parameters = { parametersOf(sessionId) })
    val s by vm.state.collectAsState()
    val error = s.error
    if (s.loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (error != null) {
        Box(Modifier.fillMaxSize().padding(16.dp)) { Text(error, style = MaterialTheme.typography.bodyLarge) }
        return
    }

    var tab by remember { mutableStateOf(0) }
    val tabs = listOf("Lap Trace", "Track", "Delta", "Pace")

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        // driver picker
        Text("Driver", style = MaterialTheme.typography.labelMedium)
        LazyRow {
            items(s.drivers) { d ->
                FilterChip(
                    selected = d.id == s.selectedDriverId,
                    onClick = { vm.selectDriver(d.id) },
                    label = { Text(d.label) },
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
        }
        // lap picker
        if (s.lapsForSelected.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text("Lap", style = MaterialTheme.typography.labelMedium)
            LazyRow {
                items(s.lapsForSelected) { l ->
                    FilterChip(
                        selected = l.lap == s.selectedLap,
                        onClick = { vm.selectLap(l.lap) },
                        label = { Text("L${l.lap}") },
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 0.dp) {
            tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.weight(1f).fillMaxSize()) {
            when (tab) {
                0 -> LapTraceTab(s)
                1 -> TrackTab(s)
                2 -> DeltaTab(s, onCompare = vm::selectCompare)
                else -> PaceTab(s)
            }
        }
    }
}

@Composable
private fun LapTraceTab(s: TelemetrySessionUi) {
    val ch = s.channel
    if (ch == null) {
        Text("No telemetry for this lap.", Modifier.padding(8.dp))
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item { ch.speed?.let { ChannelStrip("Speed (km/h)", ch.distance, it, Color(0xFF1565C0)) } }
        item { ch.throttle?.let { ChannelStrip("Throttle (%)", ch.distance, it, Color(0xFF2E7D32)) } }
        item { ch.brake?.let { ChannelStrip("Brake", ch.distance, it, Color(0xFFD32F2F)) } }
        item { ch.gear?.let { ChannelStrip("Gear", ch.distance, it.map { g -> g.toDouble() }, Color(0xFF6A1B9A)) } }
        item { ch.drs?.let { ChannelStrip("DRS", ch.distance, it.map { d -> d.toDouble() }, Color(0xFFF9A825)) } }
    }
}

@Composable
private fun TrackTab(s: TelemetrySessionUi) {
    val ch = s.channel
    if (ch?.x == null || ch.y == null) {
        Text("No track-position data for this lap.", Modifier.padding(8.dp))
        return
    }
    Column(Modifier.fillMaxSize()) {
        Text("Racing line — colored by speed (red slow → green fast)", style = MaterialTheme.typography.labelSmall)
        TrackMapCanvas(ch.x, ch.y, ch.speed, Modifier.fillMaxSize())
    }
}

@Composable
private fun DeltaTab(s: TelemetrySessionUi, onCompare: (String) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Text("Compare against", style = MaterialTheme.typography.labelMedium)
        LazyRow {
            items(s.drivers.filter { it.id != s.selectedDriverId }) { d ->
                FilterChip(
                    selected = d.id == s.compareDriverId,
                    onClick = { onCompare(d.id) },
                    label = { Text(d.label) },
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        val delta = s.delta
        if (delta == null || delta.isEmpty()) {
            Text("Pick a different driver to see the delta-time trace (same lap number, on the reference grid).",
                Modifier.padding(8.dp))
        } else {
            DeltaCanvas(delta, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun PaceTab(s: TelemetrySessionUi) {
    if (s.pace.isEmpty()) {
        Text("Race-pace is computed for race sessions only.", Modifier.padding(8.dp))
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(s.pace) { p ->
            val pct = p.pctVsTeam
            ListItem(
                headlineContent = { Text(p.driverLabel, fontWeight = FontWeight.SemiBold) },
                supportingContent = {
                    val pace = if (pct == null) "—" else "${fmtSigned(pct)}% vs teammate"
                    val deg = p.degMsPerLap?.let { " · deg ${fmt1(it)} ms/lap" } ?: ""
                    Text("$pace$deg")
                },
                trailingContent = {
                    pct?.let {
                        Text(
                            if (it < 0) "faster" else "slower",
                            color = if (it < 0) Color(0xFF2E7D32) else Color(0xFFD32F2F),
                        )
                    }
                },
            )
            HorizontalDivider()
        }
    }
}

private fun sessionTypeLabel(t: String): String = when (t) {
    "R" -> "Race"; "Q" -> "Qualifying"; "S" -> "Sprint"; "SQ" -> "Sprint Quali"
    "FP1" -> "Practice 1"; "FP2" -> "Practice 2"; "FP3" -> "Practice 3"; else -> t
}
