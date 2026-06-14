package dev.pitwall.data

import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.cash.sqldelight.db.SqlDriver
import dev.pitwall.db.F1db

// ensureF1dbFile() copies the fully pre-populated DB to getDatabasePath(DB_NAME), so opening it
// BY THE SAME NAME means the helper's onCreate never fires and Schema.create is never run.
// `path` is the cross-platform contract (used as-is on desktop); on Android the file always lives
// in the app DB dir under DB_NAME, so we open by name rather than by absolute path.
@Suppress("UNUSED_PARAMETER")
actual fun makeF1dbDriver(path: String): SqlDriver =
    AndroidSqliteDriver(F1db.Schema, appContext, DB_NAME)
