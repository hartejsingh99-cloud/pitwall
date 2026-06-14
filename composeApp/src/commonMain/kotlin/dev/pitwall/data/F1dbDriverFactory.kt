package dev.pitwall.data

import app.cash.sqldelight.db.SqlDriver

expect fun makeF1dbDriver(path: String): SqlDriver
