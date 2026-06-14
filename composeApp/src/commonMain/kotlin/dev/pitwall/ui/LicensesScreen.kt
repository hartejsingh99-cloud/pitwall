package dev.pitwall.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun LicensesScreen() = Column(Modifier.padding(16.dp)) {
    Text("Open-source data", style = MaterialTheme.typography.titleLarge)
    Spacer(Modifier.height(8.dp))
    Text(
        "Historical data: F1DB (github.com/f1db/f1db), licensed CC BY 4.0 " +
            "(creativecommons.org/licenses/by/4.0/). Bundled and transformed for offline use.",
    )
    Spacer(Modifier.height(12.dp))
    Text(
        "PitWall is an unofficial fan project, not associated with Formula 1. " +
            "F1, FORMULA 1, FORMULA ONE and related marks are trademarks of Formula One Licensing B.V.",
        style = MaterialTheme.typography.bodySmall,
    )
}
