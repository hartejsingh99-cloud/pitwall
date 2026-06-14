package dev.pitwall

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import dev.pitwall.data.appContext
import dev.pitwall.data.ensureF1dbFile
import dev.pitwall.di.appModule
import dev.pitwall.di.browseModule
import dev.pitwall.di.h2hModule
import dev.pitwall.di.recordsModule
import dev.pitwall.di.titleModule
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appContext = applicationContext
        lifecycleScope.launch {
            val path = ensureF1dbFile()
            // Guard so a config-change recreation doesn't start Koin twice.
            if (GlobalContext.getOrNull() == null) {
                startKoin { modules(appModule(path), browseModule, h2hModule, titleModule, recordsModule) }
            }
            setContent { App() }
        }
    }
}
