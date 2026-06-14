package dev.pitwall.ui.telemetry

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.pitwall.data.TelemetryDriver
import dev.pitwall.data.TelemetryLap
import dev.pitwall.data.TelemetryPace
import dev.pitwall.data.TelemetryRepository
import dev.pitwall.data.TelemetrySession
import dev.pitwall.domain.ChannelSet
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TelemetrySessionsUi(
    val loading: Boolean = true,
    val error: String? = null,
    val sessions: List<TelemetrySession> = emptyList(),
)

/** Level 1: the list of baked sessions (2018+). */
class TelemetrySessionsViewModel(private val repo: TelemetryRepository) : ViewModel() {
    private val _state = MutableStateFlow(TelemetrySessionsUi())
    val state: StateFlow<TelemetrySessionsUi> = _state

    init {
        viewModelScope.launch {
            try {
                val s = withContext(Dispatchers.Default) { repo.sessions() }
                _state.value = TelemetrySessionsUi(loading = false, sessions = s)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = TelemetrySessionsUi(loading = false, error = "Couldn't load car data: ${e.message ?: e::class.simpleName}")
            }
        }
    }
}

data class TelemetrySessionUi(
    val loading: Boolean = true,
    val error: String? = null,
    val drivers: List<TelemetryDriver> = emptyList(),
    val pace: List<TelemetryPace> = emptyList(),
    val selectedDriverId: String? = null,
    val selectedLap: Int? = null,
    val compareDriverId: String? = null,
    val lapsForSelected: List<TelemetryLap> = emptyList(),
    val channel: ChannelSet? = null,
    val delta: List<Double>? = null,
)

/**
 * Level 2: one session. Holds the driver/lap/compare selection and recomputes the decoded [ChannelSet]
 * and delta-time off the main thread on each change. Each recompute cancels the previous job so a slow
 * read can't clobber a newer selection (the Phase-1 loadJob discipline).
 */
class TelemetrySessionViewModel(
    private val repo: TelemetryRepository,
    private val sessionId: String,
) : ViewModel() {
    private val _state = MutableStateFlow(TelemetrySessionUi())
    val state: StateFlow<TelemetrySessionUi> = _state
    private var job: Job? = null

    init { load() }

    private fun load() {
        viewModelScope.launch {
            try {
                val (drivers, allLaps, pace) = withContext(Dispatchers.Default) {
                    Triple(repo.drivers(sessionId), repo.laps(sessionId), repo.pace(sessionId))
                }
                if (drivers.isEmpty()) {
                    _state.value = TelemetrySessionUi(loading = false, error = "No car data for this session.")
                    return@launch
                }
                val first = drivers.first().id
                val compare = drivers.getOrNull(1)?.id
                _state.value = TelemetrySessionUi(
                    loading = false, drivers = drivers, pace = pace,
                    selectedDriverId = first, compareDriverId = compare,
                )
                // stash all laps for filtering; recompute selection
                allLapsCache = allLaps
                selectDriver(first)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = TelemetrySessionUi(loading = false, error = "Couldn't load session: ${e.message ?: e::class.simpleName}")
            }
        }
    }

    private var allLapsCache: List<TelemetryLap> = emptyList()

    fun selectDriver(driverId: String) {
        val laps = allLapsCache.filter { it.driverId == driverId }
        val firstLap = laps.firstOrNull()?.lap
        _state.update { it.copy(selectedDriverId = driverId, lapsForSelected = laps, selectedLap = firstLap) }
        recompute()
    }

    fun selectLap(lap: Int) {
        _state.update { it.copy(selectedLap = lap) }
        recompute()
    }

    fun selectCompare(driverId: String) {
        _state.update { it.copy(compareDriverId = driverId) }
        recompute()
    }

    private fun recompute() {
        val s = _state.value
        val driver = s.selectedDriverId ?: return
        val lap = s.selectedLap ?: return
        job?.cancel()
        job = viewModelScope.launch {
            try {
                val (ch, delta) = withContext(Dispatchers.Default) {
                    val ch = repo.channel(sessionId, driver, lap)
                    val cmp = s.compareDriverId
                    val delta = if (cmp != null && cmp != driver) repo.deltaBetween(sessionId, driver, lap, cmp, lap) else null
                    ch to delta
                }
                _state.update { it.copy(channel = ch, delta = delta) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(channel = null, delta = null) }
            }
        }
    }
}
