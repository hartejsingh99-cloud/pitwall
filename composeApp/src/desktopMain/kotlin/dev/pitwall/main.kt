package dev.pitwall

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.pitwall.data.ensureF1dbFile
import dev.pitwall.di.appModule
import dev.pitwall.di.browseModule
import dev.pitwall.di.h2hModule
import dev.pitwall.di.recordsModule
import dev.pitwall.di.titleModule
import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin

fun main() {
    val path = runBlocking { ensureF1dbFile() }
    startKoin { modules(appModule(path), browseModule, h2hModule, titleModule, recordsModule) }
    application {
        Window(onCloseRequest = ::exitApplication, title = "PitWall") {
            App()
        }
    }
}
