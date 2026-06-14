package dev.pitwall.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.ExperimentalResourceApi
import pitwall.composeapp.generated.resources.Res
import java.io.File

/** Fixed filename the bundled telemetry DB is written to and opened by (shared with the driver factory). */
internal const val TELEMETRY_DB_NAME = "telemetry.db"

@OptIn(ExperimentalResourceApi::class)
actual suspend fun ensureTelemetryFile(): String = withContext(Dispatchers.IO) {
    val out = appContext.getDatabasePath(TELEMETRY_DB_NAME)
    val stamp = File(out.parentFile, "telemetry.version")
    if (!out.exists() || stamp.takeIf { it.exists() }?.readText()?.trim() != "$TELEMETRY_VERSION") {
        out.parentFile?.mkdirs()
        out.writeBytes(Res.readBytes("files/telemetry.db"))
        stamp.writeText("$TELEMETRY_VERSION")
    }
    out.absolutePath
}
