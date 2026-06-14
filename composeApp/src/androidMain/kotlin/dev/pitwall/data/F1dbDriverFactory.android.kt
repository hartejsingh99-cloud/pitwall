package dev.pitwall.data

import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.cash.sqldelight.db.SqlDriver
import dev.pitwall.db.F1db

// ensureF1dbFile() already copied the fully pre-populated DB to getDatabasePath("f1db.db"),
// so opening it BY NAME means the helper's onCreate never fires and Schema.create is never run.
actual fun makeF1dbDriver(path: String): SqlDriver =
    AndroidSqliteDriver(F1db.Schema, appContext, "f1db.db")
