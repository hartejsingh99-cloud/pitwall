package dev.pitwall.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.pitwall.data.F1Repository
import dev.pitwall.domain.DriverCarRating
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
    val seasons: List<Int> = emptyList(),
    val selectedYear: Int? = null,
    val rows: List<Pair<DriverCarRating, String>> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

class DriverVsCarViewModel(private val repo: F1Repository) : ViewModel() {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init { load(null) }

    fun load(year: Int?) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            // The bundled dataset always has seasons, so this is a corrupt-DB guard, not a normal
            // path. firstOrNull() keeps an empty result from throwing inside the coroutine scope
            // (which would be swallowed, leaving the spinner up forever) and surfaces it instead.
            val result = withContext(Dispatchers.Default) {
                val s = repo.seasons()
                val y = year ?: s.firstOrNull() ?: return@withContext null
                Triple(s, y, repo.ratingsForSeason(y.toLong()))
            }
            _state.value = result?.let { (seasons, chosen, rows) ->
                UiState(seasons, chosen, rows, loading = false)
            } ?: UiState(loading = false, error = "No seasons found in the bundled dataset.")
        }
    }
}
