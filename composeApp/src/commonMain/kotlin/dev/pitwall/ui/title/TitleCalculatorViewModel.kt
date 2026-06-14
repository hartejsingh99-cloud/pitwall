package dev.pitwall.ui.title

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.pitwall.data.TitleRepository
import dev.pitwall.data.TitleState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
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

    private var loadJob: Job? = null

    private fun reload() {
        // Snapshot the toggles we need to drive THIS load. We must not write cur.copy(...) back at
        // the end: a toggle changed while the coroutine was in flight would be clobbered. Read the
        // LATEST state via _state.update {} when writing results instead.
        val cur = _state.value
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                // The bundled dataset always has seasons; firstOrNull guards a corrupt DB so the spinner
                // can't hang forever.
                val result = withContext(Dispatchers.Default) {
                    val seasons = repo.seasons()
                    val year = cur.selectedYear ?: seasons.firstOrNull() ?: return@withContext null
                    val title = repo.titleState(year, cur.isConstructor, cur.strict)
                    Triple(seasons, year, title)
                }
                if (result != null) {
                    val (seasons, year, title) = result
                    _state.update { it.copy(seasons = seasons, selectedYear = year, state = title, loading = false, error = null) }
                } else {
                    _state.update { it.copy(loading = false, error = "No seasons found in the bundled dataset.") }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: "Failed to load title picture.") }
            }
        }
    }
}
