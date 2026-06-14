package dev.pitwall.ui.telemetry

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner

/**
 * Car Data drill-down: Sessions -> one Session. Mirrors BrowseHost — each entry gets its own
 * ViewModelStoreOwner so koinViewModel(parametersOf(sessionId)) is honored per session rather than
 * handing back a cached VM from the window-root owner.
 */
@Composable
fun TelemetryHost() {
    var openSessionId by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        val current = openSessionId
        if (current == null) {
            TelemetrySessionsScreen(onOpen = { openSessionId = it })
        } else {
            TextButton(onClick = { openSessionId = null }, modifier = Modifier.padding(start = 8.dp)) {
                Text("← Sessions")
            }
            key(current) {
                CompositionLocalProvider(LocalViewModelStoreOwner provides rememberEntryOwner(current)) {
                    TelemetrySessionScreen(sessionId = current)
                }
            }
        }
    }
}

@Composable
private fun rememberEntryOwner(keyValue: Any): ViewModelStoreOwner {
    val owner = remember(keyValue) {
        object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
    }
    DisposableEffect(keyValue) { onDispose { owner.viewModelStore.clear() } }
    return owner
}
