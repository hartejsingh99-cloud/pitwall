package dev.pitwall.ui.title

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.pitwall.data.TitleRepository
import dev.pitwall.data.TitleState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TitleUiState(
    val seasons: List<Int> = emptyList(),
    val selectedYear: Int? = null,
    val isConstructor: Boolean = false,   // false = drivers, true = constructors
    val strict: Boolean = false,          // false = simple (reach leader), true = strict (beat best rival)
    val state: TitleState? = null,
    val loading: Boolean = true,
    val error: String? = null,
)

class TitleCalculatorViewModel(private val repo: TitleRepository) : ViewModel() {
    private val _state = MutableStateFlow(TitleUiState())
    val state: StateFlow<TitleUiState> = _state

    init { reload() }

    fun selectYear(year: Int) { _state.value = _state.value.copy(selectedYear = year); reload() }
    fun setConstructor(isConstructor: Boolean) { _state.value = _state.value.copy(isConstructor = isConstructor); reload() }
    fun setStrict(strict: Boolean) { _state.value = _state.value.copy(strict = strict); reload() }

    private fun reload() {
        val cur = _state.value
        viewModelScope.launch {
            _state.value = cur.copy(loading = true, error = null)
            // The bundled dataset always has seasons; firstOrNull guards a corrupt DB so the spinner
            // can't hang forever (an exception inside the launch would be swallowed).
            val result = withContext(Dispatchers.Default) {
                val seasons = repo.seasons()
                val year = cur.selectedYear ?: seasons.firstOrNull() ?: return@withContext null
                val title = repo.titleState(year, cur.isConstructor, cur.strict)
                Triple(seasons, year, title)
            }
            _state.value = result?.let { (seasons, year, title) ->
                cur.copy(seasons = seasons, selectedYear = year, state = title, loading = false, error = null)
            } ?: cur.copy(loading = false, error = "No seasons found in the bundled dataset.")
        }
    }
}
