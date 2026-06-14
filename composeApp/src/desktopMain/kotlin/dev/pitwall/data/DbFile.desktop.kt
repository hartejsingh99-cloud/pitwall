package dev.pitwall.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.ExperimentalResourceApi
import pitwall.composeapp.generated.resources.Res
import java.io.File

@OptIn(ExperimentalResourceApi::class)
actual suspend fun ensureF1dbFile(): String = withContext(Dispatchers.IO) {
    val dir = File(System.getProperty("user.home"), "Library/Application Support/PitWall").apply { mkdirs() }
    val out = File(dir, "f1db.db")
    val stamp = File(dir, "f1db.version")
    if (!out.exists() || stamp.takeIf { it.exists() }?.readText()?.trim() != "$DATASET_VERSION") {
        out.writeBytes(Res.readBytes("files/f1db.db"))
        stamp.writeText("$DATASET_VERSION")
    }
    out.absolutePath
}
