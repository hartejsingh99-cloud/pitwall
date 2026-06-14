package dev.pitwall.ui.records

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun OnThisDayScreen(vm: OnThisDayViewModel = koinViewModel()) {
    val s by vm.state.collectAsState()
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("On This Day", style = MaterialTheme.typography.headlineSmall)
        val pretty = if (s.mmdd.length == 5) s.mmdd.replace('-', '/') else s.mmdd
        if (pretty.isNotEmpty()) {
            Text("Grand Prix winners on $pretty across history.", style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(12.dp))

        if (s.loading) {
            CircularProgressIndicator()
        } else if (s.entries.isEmpty()) {
            Text(
                "No World Championship race has been won on this calendar day. " +
                    "Try again on a busier date in the season.",
                style = MaterialTheme.typography.bodyLarge,
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(s.entries) { e ->
                    ListItem(
                        headlineContent = {
                            Text("${e.year}: ${e.winner}", fontWeight = FontWeight.SemiBold)
                        },
                        supportingContent = {
                            Text("won the ${e.grandPrix} GP at ${e.circuit} (${e.place}) · ${e.constructor}")
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
