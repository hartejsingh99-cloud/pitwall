package dev.pitwall.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.sqldelight.db.SqlDriver

// Open the pre-populated telemetry.db directly. We never call TelemetryDb.Schema.create().
actual fun makeTelemetryDriver(path: String): SqlDriver =
    JdbcSqliteDriver("jdbc:sqlite:$path")
