package dev.pitwall.ui.browse

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.pitwall.data.BrowseRepository
import dev.pitwall.data.QualifyingResultRow
import dev.pitwall.data.RaceResultRow
import dev.pitwall.data.RaceSessions
import dev.pitwall.data.SprintQualifyingResultRow
import dev.pitwall.data.SprintRaceResultRow
import dev.pitwall.domain.SessionTab
import dev.pitwall.domain.formatGap
import dev.pitwall.domain.formatStandingPoints
import dev.pitwall.domain.millisToLapTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

data class RaceResultUiState(
    val sessions: RaceSessions? = null,
    val loading: Boolean = true,
    val error: String? = null,
)

class RaceResultViewModel(
    private val repo: BrowseRepository,
    private val raceId: Int,
) : ViewModel() {
    private val _state = MutableStateFlow(RaceResultUiState())
    val state: StateFlow<RaceResultUiState> = _state

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val sessions = withContext(Dispatchers.Default) { repo.raceSessions(raceId.toLong()) }
            _state.value = RaceResultUiState(
                sessions = sessions,
                loading = false,
                error = if (sessions.available.tabs.isEmpty()) "This race has not been run yet." else null,
            )
        }
    }
}

@Composable
fun RaceResultScreen(
    raceId: Int,
    vm: RaceResultViewModel = koinViewModel(parameters = { parametersOf(raceId) }),
) {
    val s by vm.state.collectAsState()
    val sessions = s.sessions
    val error = s.error
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Results", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        if (s.loading) {
            CircularProgressIndicator()
            return@Column
        }
        if (error != null || sessions == null) {
            Text(error ?: "No results.", style = MaterialTheme.typography.bodyLarge)
            return@Column
        }

        val tabs = sessions.available.tabs
        // FIX 13 (verified false positive): remember keys use STRUCTURAL equality, and Kotlin
        // List.equals compares by content — a same-content `tabs` list does NOT reset selection
        // across recompositions. Keying on a stable identity string of which tabs exist is
        // behavior-identical to keying on `tabs`, just clearer about intent.
        val tabsKey = tabs.joinToString(",") { it.name }
        var selected by remember(tabsKey) { mutableStateOf(0) }
        val current = tabs.getOrElse(selected) { tabs.first() }

        TabRow(selectedTabIndex = selected) {
            tabs.forEachIndexed { i, tab ->
                Tab(selected = i == selected, onClick = { selected = i }, text = { Text(tab.label) })
            }
        }
        Spacer(Modifier.height(8.dp))
        when (current) {
            SessionTab.QUALIFYING -> QualifyingTable(sessions.qualifying)
            SessionTab.SPRINT_QUALIFYING -> SprintQualifyingTable(sessions.sprintQualifying)
            SessionTab.SPRINT -> SprintRaceTable(sessions.sprint)
            SessionTab.RACE -> RaceTable(sessions.race)
        }
    }
}

// ---- Race classification ---------------------------------------------------
@Composable
private fun RaceTable(rows: List<RaceResultRow>) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(rows) { r ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                PosCell(r.positionText)
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(r.driverName, fontWeight = FontWeight.SemiBold)
                        if (r.polePosition) MarkerChip("POLE")
                        if (r.fastestLap) MarkerChip("FL")
                    }
                    Text(r.constructorName, style = MaterialTheme.typography.bodySmall)
                    val reason = r.reasonRetired?.trim()
                    if (!reason.isNullOrEmpty()) {
                        Text(reason, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(formatStandingPoints(r.points), fontWeight = FontWeight.SemiBold)
            }
            HorizontalDivider()
        }
    }
}

// ---- Qualifying classification ---------------------------------------------
@Composable
private fun QualifyingTable(rows: List<QualifyingResultRow>) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(rows) { r ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                PosCell(r.positionText)
                Column(Modifier.weight(1f)) {
                    Text(r.driverName, fontWeight = FontWeight.SemiBold)
                    // Pre-2006 single-session quali has q1/q2/q3 all NULL (time only in bestMillis);
                    // only show the per-session breakdown when at least one session time exists.
                    if (r.q1Millis != null || r.q2Millis != null || r.q3Millis != null) {
                        Text(
                            "Q1 ${millisToLapTime(r.q1Millis)} · Q2 ${millisToLapTime(r.q2Millis)} · Q3 ${millisToLapTime(r.q3Millis)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Text(millisToLapTime(r.bestMillis), fontWeight = FontWeight.SemiBold)
            }
            HorizontalDivider()
        }
    }
}

// ---- Sprint race -----------------------------------------------------------
@Composable
private fun SprintRaceTable(rows: List<SprintRaceResultRow>) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(rows) { r ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                PosCell(r.positionText)
                Column(Modifier.weight(1f)) {
                    Text(r.driverName, fontWeight = FontWeight.SemiBold)
                    Text(r.constructorName, style = MaterialTheme.typography.bodySmall)
                    val reason = r.reasonRetired?.trim()
                    if (!reason.isNullOrEmpty()) {
                        Text(reason, style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text("Gap ${formatGap(r.gap)}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(formatStandingPoints(r.points), fontWeight = FontWeight.SemiBold)
            }
            HorizontalDivider()
        }
    }
}

// ---- Sprint qualifying -----------------------------------------------------
@Composable
private fun SprintQualifyingTable(rows: List<SprintQualifyingResultRow>) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(rows) { r ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                PosCell(r.positionText)
                Column(Modifier.weight(1f)) {
                    Text(r.driverName, fontWeight = FontWeight.SemiBold)
                    // Same single-session guard as QualifyingTable (FIX 11).
                    if (r.q1Millis != null || r.q2Millis != null || r.q3Millis != null) {
                        Text(
                            "Q1 ${millisToLapTime(r.q1Millis)} · Q2 ${millisToLapTime(r.q2Millis)} · Q3 ${millisToLapTime(r.q3Millis)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Text(millisToLapTime(r.bestMillis), fontWeight = FontWeight.SemiBold)
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun PosCell(positionText: String) {
    Text(
        positionText,
        modifier = Modifier.width(40.dp),
        fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun MarkerChip(label: String) {
    Spacer(Modifier.width(6.dp))
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
    )
}
