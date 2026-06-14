package dev.pitwall.ui.h2h

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.pitwall.data.DriverTotals
import dev.pitwall.domain.HeadToHeadResult
import kotlin.math.abs
import kotlin.math.roundToLong
import org.koin.compose.viewmodel.koinViewModel

/** Signed percentage, exactly 2 decimals, multiplatform-safe (no JVM String.format). */
private fun formatSignedPct(v: Double): String {
    val scaled = (abs(v) * 100).roundToLong()
    val sign = if (v < 0) "-" else "+"
    val frac = (scaled % 100).toString().padStart(2, '0')
    return "$sign${scaled / 100}.$frac%"
}

/** Points: Double rendered with one decimal, trailing ".0" trimmed (no String.format). */
private fun formatPoints(v: Double): String {
    val scaled = (v * 10).roundToLong()
    val whole = scaled / 10
    val frac = scaled % 10
    return if (frac == 0L) whole.toString() else "$whole.$frac"
}

@Composable
fun HeadToHeadScreen(vm: HeadToHeadViewModel = koinViewModel()) {
    val s by vm.state.collectAsState()
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Head-to-Head", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Career totals + same-car teammate qualifying gap",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))

        // ---- Two picker slots ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            FilterChip(
                selected = s.activeSlot == Slot.A,
                onClick = { vm.focusSlot(Slot.A) },
                label = { Text(s.pickA?.fullName ?: "Pick driver A") },
            )
            Spacer(Modifier.width(8.dp))
            Text("vs")
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = s.activeSlot == Slot.B,
                onClick = { vm.focusSlot(Slot.B) },
                label = { Text(s.pickB?.fullName ?: "Pick driver B") },
            )
            if (s.pickA != null && s.pickB != null) {
                Spacer(Modifier.width(8.dp))
                AssistChip(onClick = { vm.swap() }, label = { Text("Swap") })
            }
        }
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = s.query,
            onValueChange = { vm.search(it) },
            label = { Text("Search driver ${s.activeSlot.name}") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))

        when {
            s.results.isNotEmpty() -> {
                // Search results take over the body while the user is typing.
                LazyColumn(Modifier.fillMaxSize()) {
                    items(items = s.results, key = { it.id }) { d ->
                        ListItem(
                            headlineContent = { Text(d.fullName, fontWeight = FontWeight.SemiBold) },
                            supportingContent = { Text("${d.wins} wins · ${d.titles} titles") },
                            modifier = Modifier.fillMaxWidth(),
                            trailingContent = {
                                Button(onClick = { vm.pick(d) }) { Text("Pick") }
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }

            s.computing -> CircularProgressIndicator()

            s.data != null -> {
                val data = s.data!!
                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { TotalsCard(data.driverA, data.driverB) }
                    item { CareerH2HBlock(data.result) }
                    if (!data.result.directGapComputable) {
                        item {
                            Text(
                                "Never teammates — no same-car comparison.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    } else {
                        item {
                            Text(
                                "Same-car teammate stints (median quali gap, ${data.driverA.fullName} perspective)",
                                style = MaterialTheme.typography.titleSmall,
                            )
                        }
                        items(
                            items = data.result.teammateStints,
                            key = { st -> "${st.year}-${st.constructorId}" },
                        ) { st ->
                            ListItem(
                                headlineContent = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("${st.year} · ${st.constructorId}", fontWeight = FontWeight.SemiBold)
                                        Spacer(Modifier.width(8.dp))
                                        AssistChip(onClick = {}, label = { Text("same car") })
                                    }
                                },
                                supportingContent = {
                                    Text(
                                        "${data.driverA.fullName} ahead ${st.aAhead}/${st.sessions}" +
                                            " · median ${formatSignedPct(st.medianGapPctA)}",
                                    )
                                },
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }

            else -> Text(
                "Pick two drivers to compare.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun TotalsCard(a: DriverTotals, b: DriverTotals) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(a.fullName, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
                Text(b.fullName, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
            }
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            TotalsRow("Wins", a.wins.toString(), b.wins.toString())
            TotalsRow("Poles", a.poles.toString(), b.poles.toString())
            TotalsRow("Podiums", a.podiums.toString(), b.podiums.toString())
            TotalsRow("Fastest laps", a.fastestLaps.toString(), b.fastestLaps.toString())
            TotalsRow("Points", formatPoints(a.points), formatPoints(b.points))
            TotalsRow("Titles", a.titles.toString(), b.titles.toString())
            TotalsRow("Starts", a.starts.toString(), b.starts.toString())
        }
    }
}

@Composable
private fun TotalsRow(label: String, av: String, bv: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(av, style = MaterialTheme.typography.bodyMedium)
        Text(label, style = MaterialTheme.typography.labelMedium)
        Text(bv, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CareerH2HBlock(r: HeadToHeadResult) {
    val c = r.career
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Career head-to-head", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text("Qualifying ${c.qualiWinsA}-${c.qualiWinsB} (${c.commonQualiSessions} shared sessions)")
            Text("Races ${c.raceWinsA}-${c.raceWinsB} (${c.commonRaces} both classified)")
        }
    }
}
