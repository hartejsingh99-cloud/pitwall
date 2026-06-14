package dev.pitwall.ui.records

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.pitwall.data.RecordsRepository
import dev.pitwall.domain.EraFilter
import dev.pitwall.domain.RankedRecord
import dev.pitwall.domain.RecordMetric
import dev.pitwall.domain.RecordScope
import dev.pitwall.domain.RecordSource
import dev.pitwall.domain.rankRecords
import dev.pitwall.domain.recordSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RecordsUiState(
    val scope: RecordScope = RecordScope.DRIVERS,
    val metrics: List<RecordMetric> = RecordMetric.forScope(RecordScope.DRIVERS),
    val metric: RecordMetric = RecordMetric.WINS,
    val era: EraFilter = EraFilter(null, null),
    val rows: List<RankedRecord> = emptyList(),
    val loading: Boolean = true,
) {
    /** True when the current (metric, scope) has an era-sliced query at all. */
    val eraSupported: Boolean get() = scope == RecordScope.DRIVERS && metric.eraComputableForDrivers
    /** True when an era window is set AND it is actually being applied (computed source). */
    val computedActive: Boolean get() = recordSource(metric, scope, era) == RecordSource.COMPUTED
}

class RecordsViewModel(private val repo: RecordsRepository) : ViewModel() {
    private val _state = MutableStateFlow(RecordsUiState())
    val state: StateFlow<RecordsUiState> = _state

    init { reload() }

    fun selectScope(scope: RecordScope) {
        if (scope == _state.value.scope) return
        val metrics = RecordMetric.forScope(scope)
        // Keep the current metric if the new scope still offers it; otherwise default to its first.
        val metric = _state.value.metric.takeIf { it in metrics } ?: metrics.first()
        _state.value = _state.value.copy(scope = scope, metrics = metrics, metric = metric)
        reload()
    }

    fun selectMetric(metric: RecordMetric) {
        if (metric == _state.value.metric) return
        _state.value = _state.value.copy(metric = metric)
        reload()
    }

    fun applyEra(yearFrom: Int?, yearTo: Int?) {
        _state.value = _state.value.copy(era = EraFilter(yearFrom, yearTo))
        reload()
    }

    fun clearEra() {
        _state.value = _state.value.copy(era = EraFilter(null, null))
        reload()
    }

    private fun reload() {
        val snapshot = _state.value
        viewModelScope.launch {
            _state.value = snapshot.copy(loading = true)
            val rows = withContext(Dispatchers.Default) {
                rankRecords(repo.leaderboard(snapshot.metric, snapshot.scope, snapshot.era))
            }
            // Guard against a newer reload having superseded this one.
            if (_state.value.metric == snapshot.metric &&
                _state.value.scope == snapshot.scope &&
                _state.value.era == snapshot.era
            ) {
                _state.value = _state.value.copy(rows = rows, loading = false)
            }
        }
    }
}
