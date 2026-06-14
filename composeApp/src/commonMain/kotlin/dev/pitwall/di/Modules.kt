package dev.pitwall.di

import app.cash.sqldelight.db.SqlDriver
import dev.pitwall.data.F1Repository
import dev.pitwall.data.makeF1dbDriver
import dev.pitwall.db.F1db
import org.koin.core.module.Module
import org.koin.dsl.module

// driverPath is resolved at startup (ensureF1dbFile, a suspend fun) and passed in by the entry point.
fun appModule(driverPath: String): Module = module {
    single<SqlDriver> { makeF1dbDriver(driverPath) }
    single { F1db(get()) }            // DB is pre-populated — never call F1db.Schema.create
    single { F1Repository(get()) }
    // NOTE: the DriverVsCarViewModel binding is added in Task 5, once the ViewModel exists.
}
