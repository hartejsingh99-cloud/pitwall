package dev.pitwall.ui.browse

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.pitwall.data.BrowseRepository
import dev.pitwall.domain.RaceListItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

data class RacesUiState(
    val year: Int = 0,
    val races: List<RaceListItem> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

class RacesViewModel(
    private val repo: BrowseRepository,
    private val year: Int,
) : ViewModel() {
    private val _state = MutableStateFlow(RacesUiState(year = year))
    val state: StateFlow<RacesUiState> = _state

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val races = withContext(Dispatchers.Default) { repo.racesInSeason(year.toLong()) }
            _state.value = RacesUiState(
                year = year,
                races = races,
                loading = false,
                error = if (races.isEmpty()) "No races found for $year." else null,
            )
        }
    }
}

@Composable
fun RacesScreen(
    year: Int,
    onOpenRace: (raceId: Int) -> Unit,
    onOpenStandings: (year: Int) -> Unit,
    vm: RacesViewModel = koinViewModel(parameters = { parametersOf(year) }),
) {
    val s by vm.state.collectAsState()
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${s.year} season", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.width(12.dp))
            Button(onClick = { onOpenStandings(s.year) }) { Text("Standings") }
        }
        Spacer(Modifier.height(8.dp))
        val error = s.error
        if (s.loading) {
            CircularProgressIndicator()
        } else if (error != null) {
            Text(error, style = MaterialTheme.typography.bodyLarge)
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(s.races) { race ->
                    RaceRow(race, onClick = { if (race.isRun) onOpenRace(race.raceId) })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun RaceRow(race: RaceListItem, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(enabled = race.isRun, onClick = onClick),
        leadingContent = { Text("R${race.round}", fontWeight = FontWeight.SemiBold) },
        headlineContent = { Text(race.grandPrixName, fontWeight = FontWeight.SemiBold) },
        supportingContent = {
            Column {
                Text("${race.circuitName} · ${race.placeName}")
                Text(race.date, style = MaterialTheme.typography.bodySmall)
                if (!race.isRun) {
                    Text("Scheduled — not yet run", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (race.isSprintWeekend) {
                    SuggestionChip(onClick = {}, label = { Text("Sprint") })
                    Spacer(Modifier.width(6.dp))
                }
                if (!race.isRun) {
                    AssistChip(onClick = {}, label = { Text("Upcoming") })
                }
            }
        },
    )
}
