package dev.pitwall.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.sqldelight.db.SqlDriver

// Open the existing, pre-populated file directly. We never call F1db.Schema.create() — tables already exist.
actual fun makeF1dbDriver(path: String): SqlDriver =
    JdbcSqliteDriver("jdbc:sqlite:$path")
