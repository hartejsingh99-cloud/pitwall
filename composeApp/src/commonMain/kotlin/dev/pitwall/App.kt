package dev.pitwall

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import dev.pitwall.ui.DriverVsCarScreen
import dev.pitwall.ui.LicensesScreen
import dev.pitwall.ui.browse.RaceResultScreen
import dev.pitwall.ui.browse.RacesScreen
import dev.pitwall.ui.browse.SeasonsScreen
import dev.pitwall.ui.browse.StandingsScreen
import dev.pitwall.ui.h2h.HeadToHeadScreen
import dev.pitwall.ui.records.OnThisDayScreen
import dev.pitwall.ui.records.RecordsScreen
import dev.pitwall.ui.title.TitleCalculatorScreen

/** Top-level destinations. Glyphs are emoji (no icon-pack dependency). */
private enum class Dest(val label: String, val glyph: String) {
    HERO("Driver vs Car", "🏎️"),
    BROWSE("Browse", "📅"),
    COMPARE("Compare", "⚖️"),
    TITLE("Title Race", "🏆"),
    RECORDS("Records", "📊"),
    ABOUT("About", "ℹ️"),
}

@Composable
fun App() = MaterialTheme {
    var dest by remember { mutableStateOf(Dest.HERO) }
    // NavigationRail on wide windows (desktop), NavigationBar on compact (phone) — one tree, no nav lib.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth < 600.dp) {
            Scaffold(
                bottomBar = {
                    NavigationBar {
                        Dest.entries.forEach { d ->
                            NavigationBarItem(
                                selected = dest == d,
                                onClick = { dest = d },
                                icon = { Text(d.glyph) },
                                label = { Text(d.label) },
                            )
                        }
                    }
                },
            ) { pad ->
                DestinationContent(dest, Modifier.fillMaxSize().padding(pad))
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                NavigationRail {
                    Dest.entries.forEach { d ->
                        NavigationRailItem(
                            selected = dest == d,
                            onClick = { dest = d },
                            icon = { Text(d.glyph) },
                            label = { Text(d.label) },
                        )
                    }
                }
                DestinationContent(dest, Modifier.weight(1f).fillMaxSize())
            }
        }
    }
}

@Composable
private fun DestinationContent(dest: Dest, modifier: Modifier) = Box(modifier) {
    when (dest) {
        Dest.HERO -> DriverVsCarScreen()
        Dest.BROWSE -> BrowseHost()
        Dest.COMPARE -> HeadToHeadScreen()
        Dest.TITLE -> TitleCalculatorScreen()
        Dest.RECORDS -> RecordsHost()
        Dest.ABOUT -> LicensesScreen()
    }
}

// ---- Browse drill-down: Seasons -> Races -> RaceResult / Standings ---------
private sealed interface BrowseDest {
    data object Seasons : BrowseDest
    data class Races(val year: Int) : BrowseDest
    data class RaceResult(val raceId: Int) : BrowseDest
    data class Standings(val year: Int) : BrowseDest
}

@Composable
private fun BrowseHost() {
    val stack = remember { mutableStateListOf<BrowseDest>(BrowseDest.Seasons) }
    Column(Modifier.fillMaxSize()) {
        if (stack.size > 1) {
            TextButton(onClick = { stack.removeAt(stack.lastIndex) }, modifier = Modifier.padding(start = 8.dp)) {
                Text("← Back")
            }
        }
        val top = stack.last()
        // Each entry gets its own ViewModelStoreOwner so parametersOf(year/raceId) is honored per
        // entry — otherwise koinViewModel() would hand back a cached VM from the window-root owner
        // and Races(2024) -> back -> Races(2026) would show stale 2024 data.
        key(top) {
            CompositionLocalProvider(LocalViewModelStoreOwner provides rememberEntryOwner(top)) {
                when (top) {
                    BrowseDest.Seasons -> SeasonsScreen(onOpenSeason = { stack.add(BrowseDest.Races(it)) })
                    is BrowseDest.Races -> RacesScreen(
                        year = top.year,
                        onOpenRace = { stack.add(BrowseDest.RaceResult(it)) },
                        onOpenStandings = { stack.add(BrowseDest.Standings(it)) },
                    )
                    is BrowseDest.RaceResult -> RaceResultScreen(raceId = top.raceId)
                    is BrowseDest.Standings -> StandingsScreen(year = top.year)
                }
            }
        }
    }
}

@Composable
private fun rememberEntryOwner(key: Any): ViewModelStoreOwner =
    remember(key) {
        object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
    }

// ---- Records hosts the leaderboards + On-This-Day under one destination ----
@Composable
private fun RecordsHost() {
    var sub by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = sub) {
            Tab(selected = sub == 0, onClick = { sub = 0 }, text = { Text("Records") })
            Tab(selected = sub == 1, onClick = { sub = 1 }, text = { Text("On This Day") })
        }
        Box(Modifier.weight(1f).fillMaxSize()) {
            if (sub == 0) RecordsScreen() else OnThisDayScreen()
        }
    }
}
