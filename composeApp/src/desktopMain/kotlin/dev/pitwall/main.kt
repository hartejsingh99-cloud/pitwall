package dev.pitwall

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.pitwall.data.ensureF1dbFile
import dev.pitwall.di.appModule
import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin

fun main() {
    val path = runBlocking { ensureF1dbFile() }
    startKoin { modules(appModule(path)) }
    application {
        Window(onCloseRequest = ::exitApplication, title = "PitWall") {
            App()
        }
    }
}
