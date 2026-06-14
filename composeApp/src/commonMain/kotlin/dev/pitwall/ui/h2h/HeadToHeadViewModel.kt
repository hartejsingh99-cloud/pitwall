package dev.pitwall.ui.h2h

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.pitwall.data.DriverPick
import dev.pitwall.data.HeadToHeadData
import dev.pitwall.data.HeadToHeadRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which of the two picker slots a search/selection targets. */
enum class Slot { A, B }

data class HeadToHeadUiState(
    val query: String = "",
    val activeSlot: Slot = Slot.A,
    val results: List<DriverPick> = emptyList(),
    val pickA: DriverPick? = null,
    val pickB: DriverPick? = null,
    val data: HeadToHeadData? = null,
    val computing: Boolean = false,
)

class HeadToHeadViewModel(private val repo: HeadToHeadRepository) : ViewModel() {
    private val _state = MutableStateFlow(HeadToHeadUiState())
    val state: StateFlow<HeadToHeadUiState> = _state

    /** Switch which slot the search box is filling; clears the box + results for a fresh search. */
    fun focusSlot(slot: Slot) {
        _state.value = _state.value.copy(activeSlot = slot, query = "", results = emptyList())
    }

    fun search(query: String) {
        _state.value = _state.value.copy(query = query)
        viewModelScope.launch {
            val hits = withContext(Dispatchers.Default) { repo.searchDrivers(query) }
            // Ignore stale results if the query changed while we were off-thread.
            if (_state.value.query == query) _state.value = _state.value.copy(results = hits)
        }
    }

    fun pick(driver: DriverPick) {
        val cur = _state.value
        val next = when (cur.activeSlot) {
            Slot.A -> cur.copy(pickA = driver, query = "", results = emptyList(), activeSlot = Slot.B)
            Slot.B -> cur.copy(pickB = driver, query = "", results = emptyList())
        }
        _state.value = next
        maybeCompute(next.pickA, next.pickB)
    }

    private fun maybeCompute(a: DriverPick?, b: DriverPick?) {
        if (a == null || b == null) {
            _state.value = _state.value.copy(data = null)
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(computing = true)
            val data = withContext(Dispatchers.Default) { repo.headToHead(a.id, b.id) }
            _state.value = _state.value.copy(data = data, computing = false)
        }
    }

    fun swap() {
        val cur = _state.value
        val next = cur.copy(pickA = cur.pickB, pickB = cur.pickA)
        _state.value = next
        maybeCompute(next.pickA, next.pickB)
    }
}
