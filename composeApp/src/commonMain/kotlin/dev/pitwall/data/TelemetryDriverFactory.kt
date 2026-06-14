package dev.pitwall.data

import app.cash.sqldelight.db.SqlDriver

expect fun makeTelemetryDriver(path: String): SqlDriver
