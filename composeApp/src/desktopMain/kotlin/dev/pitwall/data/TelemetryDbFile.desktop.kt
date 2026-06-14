package dev.pitwall.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.ExperimentalResourceApi
import pitwall.composeapp.generated.resources.Res
import java.io.File

@OptIn(ExperimentalResourceApi::class)
actual suspend fun ensureTelemetryFile(): String = withContext(Dispatchers.IO) {
    val dir = File(System.getProperty("user.home"), "Library/Application Support/PitWall").apply { mkdirs() }
    val out = File(dir, "telemetry.db")
    val stamp = File(dir, "telemetry.version")
    if (!out.exists() || stamp.takeIf { it.exists() }?.readText()?.trim() != "$TELEMETRY_VERSION") {
        out.writeBytes(Res.readBytes("files/telemetry.db"))
        stamp.writeText("$TELEMETRY_VERSION")
    }
    out.absolutePath
}
