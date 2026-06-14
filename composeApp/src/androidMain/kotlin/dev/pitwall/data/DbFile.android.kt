package dev.pitwall.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.ExperimentalResourceApi
import pitwall.composeapp.generated.resources.Res
import java.io.File

/** The fixed filename the bundled DB is written to and opened by (shared by the driver factory). */
internal const val DB_NAME = "f1db.db"

internal lateinit var appContext: Context // set in MainActivity.onCreate (Task 6)

@OptIn(ExperimentalResourceApi::class)
actual suspend fun ensureF1dbFile(): String = withContext(Dispatchers.IO) {
    val out = appContext.getDatabasePath(DB_NAME)
    val stamp = File(out.parentFile, "f1db.version")
    if (!out.exists() || stamp.takeIf { it.exists() }?.readText()?.trim() != "$DATASET_VERSION") {
        out.parentFile?.mkdirs()
        out.writeBytes(Res.readBytes("files/f1db.db"))
        stamp.writeText("$DATASET_VERSION")
    }
    out.absolutePath
}
