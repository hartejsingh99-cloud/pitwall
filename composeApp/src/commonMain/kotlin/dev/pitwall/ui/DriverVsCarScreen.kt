package dev.pitwall.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToLong
import org.koin.compose.viewmodel.koinViewModel

/** Signed percentage, exactly 3 decimals, multiplatform-safe (no JVM String.format). */
private fun formatSignedPct(v: Double): String {
    val scaled = (abs(v) * 1000).roundToLong()
    val sign = if (v < 0) "-" else "+"
    val frac = (scaled % 1000).toString().padStart(3, '0')
    return "$sign${scaled / 1000}.$frac%"
}

@Composable
fun DriverVsCarScreen(vm: DriverVsCarViewModel = koinViewModel()) {
    val s by vm.state.collectAsState()
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Driver vs Car — qualifying skill", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Teammate-normalized one-lap rating · median symmetric gap · same car only",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        if (s.seasons.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                s.selectedYear?.let { y ->
                    Text("Season: $y", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(12.dp))
                    val idx = s.seasons.indexOf(y)
                    Button(enabled = idx < s.seasons.lastIndex, onClick = { vm.load(s.seasons[idx + 1]) }) { Text("◀ older") }
                    Spacer(Modifier.width(8.dp))
                    Button(enabled = idx > 0, onClick = { vm.load(s.seasons[idx - 1]) }) { Text("newer ▶") }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        val error = s.error
        if (s.loading) {
            CircularProgressIndicator()
        } else if (error != null) {
            Text(error, style = MaterialTheme.typography.bodyLarge)
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(s.rows) { (r, name) ->
                    ListItem(
                        headlineContent = { Text(name, fontWeight = FontWeight.SemiBold) },
                        supportingContent = {
                            Text("${formatSignedPct(r.oneLapRatingPct)} vs teammate · H2H ${r.headToHeadWins}/${r.events}")
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
