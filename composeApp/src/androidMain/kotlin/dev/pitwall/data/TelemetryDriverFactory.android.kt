package dev.pitwall.data

import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.cash.sqldelight.db.SqlDriver
import dev.pitwall.telemetrydb.TelemetryDb

// ensureTelemetryFile() copies the pre-populated DB to getDatabasePath(TELEMETRY_DB_NAME), so opening
// it BY THE SAME NAME means the helper's onCreate never fires and Schema.create is never run.
@Suppress("UNUSED_PARAMETER")
actual fun makeTelemetryDriver(path: String): SqlDriver =
    AndroidSqliteDriver(TelemetryDb.Schema, appContext, TELEMETRY_DB_NAME)
