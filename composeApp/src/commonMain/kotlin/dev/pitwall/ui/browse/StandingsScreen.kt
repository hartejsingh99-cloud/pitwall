package dev.pitwall.ui.browse

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
import dev.pitwall.data.StandingRow
import dev.pitwall.domain.formatStandingPoints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

data class StandingsUiState(
    val year: Int = 0,
    val drivers: List<StandingRow> = emptyList(),
    val constructors: List<StandingRow> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

class StandingsViewModel(
    private val repo: BrowseRepository,
    private val year: Int,
) : ViewModel() {
    private val _state = MutableStateFlow(StandingsUiState(year = year))
    val state: StateFlow<StandingsUiState> = _state

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val (drivers, constructors) = withContext(Dispatchers.Default) {
                repo.driverStandings(year.toLong()) to repo.constructorStandings(year.toLong())
            }
            _state.value = StandingsUiState(
                year = year,
                drivers = drivers,
                constructors = constructors,
                loading = false,
                error = if (drivers.isEmpty() && constructors.isEmpty()) "No standings for $year yet." else null,
            )
        }
    }
}

@Composable
fun StandingsScreen(
    year: Int,
    vm: StandingsViewModel = koinViewModel(parameters = { parametersOf(year) }),
) {
    val s by vm.state.collectAsState()
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("${s.year} standings", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        val error = s.error
        if (s.loading) {
            CircularProgressIndicator()
            return@Column
        }
        if (error != null) {
            Text(error, style = MaterialTheme.typography.bodyLarge)
            return@Column
        }

        var selected by remember { mutableStateOf(0) }
        TabRow(selectedTabIndex = selected) {
            Tab(selected = selected == 0, onClick = { selected = 0 }, text = { Text("Drivers") })
            Tab(selected = selected == 1, onClick = { selected = 1 }, text = { Text("Constructors") })
        }
        Spacer(Modifier.height(8.dp))
        val rows = if (selected == 0) s.drivers else s.constructors
        LazyColumn(Modifier.fillMaxSize()) {
            items(rows) { r -> StandingRowView(r) }
        }
    }
}

@Composable
private fun StandingRowView(r: StandingRow) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            r.positionText,
            modifier = Modifier.width(40.dp),
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(r.name, fontWeight = FontWeight.SemiBold)
            if (r.championshipWon) {
                Spacer(Modifier.width(6.dp))
                Text("👑", style = MaterialTheme.typography.bodyMedium)   // crown
            }
        }
        Text(formatStandingPoints(r.points), fontWeight = FontWeight.SemiBold)
    }
    HorizontalDivider()
}
