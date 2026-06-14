package dev.pitwall.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.pitwall.data.F1Repository
import dev.pitwall.data.TelemetryRepository
import dev.pitwall.domain.HeroRow
import dev.pitwall.domain.mergeRacePace
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
    val seasons: List<Int> = emptyList(),
    val selectedYear: Int? = null,
    val rows: List<HeroRow> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

class DriverVsCarViewModel(
    private val repo: F1Repository,
    private val telemetry: TelemetryRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init { load(null) }

    fun load(year: Int?) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            try {
                // firstOrNull() (not first()) so an empty dataset surfaces as an error, not a throw
                // that the coroutine swallows into a permanent spinner. The try/catch does the same
                // for any data-layer failure (e.g. a missing JDBC module in a packaged build).
                val result = withContext(Dispatchers.Default) {
                    val s = repo.seasons()
                    val y = year ?: s.firstOrNull() ?: return@withContext null
                    val ratings = repo.ratingsForSeason(y.toLong())
                    // Race-pace companion (2018+). Best-effort: the qualifying hero is the flagship
                    // offline feature and must never break if telemetry is absent/empty. Flip the sign
                    // so positive = faster, matching oneLapRatingPct (the bake uses negative = faster).
                    val pace = runCatching { telemetry.heroRacePace(y).mapValues { -it.value } }
                        .getOrDefault(emptyMap())
                    Triple(s, y, mergeRacePace(ratings, pace))
                }
                _state.value = result?.let { (seasons, chosen, rows) ->
                    UiState(seasons, chosen, rows, loading = false)
                } ?: UiState(loading = false, error = "No seasons found in the bundled dataset.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = UiState(loading = false, error = "Couldn't load data: ${e.message ?: e::class.simpleName}")
            }
        }
    }
}
