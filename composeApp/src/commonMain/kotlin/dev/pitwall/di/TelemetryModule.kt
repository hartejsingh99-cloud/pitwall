package dev.pitwall.di

import dev.pitwall.data.TelemetryRepository
import dev.pitwall.data.makeTelemetryDriver
import dev.pitwall.telemetrydb.TelemetryDb
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Feature (Telemetry / Car Data) DI. telemetryPath is resolved at startup (ensureTelemetryFile, suspend)
 * and passed in by the entry point. The TelemetryDb driver is built inline here rather than registered as
 * a bare SqlDriver single, so it never collides with the f1db SqlDriver single (same type) — no qualifier
 * needed. ViewModels are added in the UI task as `factory`/parameterised factories.
 */
fun telemetryModule(telemetryPath: String): Module = module {
    single { TelemetryDb(makeTelemetryDriver(telemetryPath)) }
    single { TelemetryRepository(get()) }
}
