package dev.pitwall.data

import android.content.Context
import org.jetbrains.compose.resources.ExperimentalResourceApi
import pitwall.composeapp.generated.resources.Res
import java.io.File

lateinit var appContext: Context // set in MainActivity.onCreate (Task 6)

@OptIn(ExperimentalResourceApi::class)
actual suspend fun ensureF1dbFile(): String {
    val out = appContext.getDatabasePath("f1db.db")
    val stamp = File(out.parentFile, "f1db.version")
    if (!out.exists() || stamp.takeIf { it.exists() }?.readText()?.trim() != "$DATASET_VERSION") {
        out.parentFile?.mkdirs()
        out.writeBytes(Res.readBytes("files/f1db.db"))
        stamp.writeText("$DATASET_VERSION")
    }
    return out.absolutePath
}
