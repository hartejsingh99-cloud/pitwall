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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.pitwall.data.BrowseRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.viewmodel.koinViewModel

data class SeasonsUiState(
    val seasons: List<Int> = emptyList(),
    val throughLabel: String? = null,   // e.g. "data through 2026 R6 (Monaco)"
    val loading: Boolean = true,
    val error: String? = null,
)

class SeasonsViewModel(private val repo: BrowseRepository) : ViewModel() {
    private val _state = MutableStateFlow(SeasonsUiState())
    val state: StateFlow<SeasonsUiState> = _state

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val result = withContext(Dispatchers.Default) {
                val seasons = repo.seasons()
                val newest = seasons.firstOrNull() ?: return@withContext null
                // Through-label: the last RUN round of the newest season (in-progress 2026 stops at R6).
                val lastRun = repo.racesInSeason(newest.toLong()).filter { it.isRun }.maxByOrNull { it.round }
                val label = lastRun?.let { "data through ${newest} R${it.round} (${it.grandPrixName})" }
                    ?: "data through $newest"
                seasons to label
            }
            _state.value = result?.let { (seasons, label) ->
                SeasonsUiState(seasons = seasons, throughLabel = label, loading = false)
            } ?: SeasonsUiState(loading = false, error = "No seasons found in the bundled dataset.")
        }
    }
}

@Composable
fun SeasonsScreen(
    onOpenSeason: (Int) -> Unit,
    vm: SeasonsViewModel = koinViewModel(),
) {
    val s by vm.state.collectAsState()
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Seasons", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            s.throughLabel?.let { label ->
                AssistChip(
                    onClick = {},
                    label = { Text(label) },
                    colors = AssistChipDefaults.assistChipColors(),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        val error = s.error
        if (s.loading) {
            CircularProgressIndicator()
        } else if (error != null) {
            Text(error, style = MaterialTheme.typography.bodyLarge)
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(s.seasons) { year ->
                    ListItem(
                        modifier = Modifier.clickable { onOpenSeason(year) },
                        headlineContent = { Text(year.toString(), fontWeight = FontWeight.SemiBold) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
