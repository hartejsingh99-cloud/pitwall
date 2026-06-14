package dev.pitwall.ui.records

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.pitwall.data.RecordsRepository
import dev.pitwall.domain.OnThisDayEntry
import dev.pitwall.util.currentMonthDay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class OnThisDayUiState(
    val mmdd: String = "",
    val entries: List<OnThisDayEntry> = emptyList(),
    val loading: Boolean = true,
)

class OnThisDayViewModel(private val repo: RecordsRepository) : ViewModel() {
    private val _state = MutableStateFlow(OnThisDayUiState())
    val state: StateFlow<OnThisDayUiState> = _state

    init { loadToday() }

    /** Load the device's current calendar day. */
    fun loadToday() = load(currentMonthDay())

    /** Load a specific "MM-DD". Exposed so the controller could wire a date picker later. */
    fun load(mmdd: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(mmdd = mmdd, loading = true)
            val entries = withContext(Dispatchers.Default) { repo.onThisDay(mmdd) }
            if (_state.value.mmdd == mmdd) {
                _state.value = _state.value.copy(entries = entries, loading = false)
            }
        }
    }
}
