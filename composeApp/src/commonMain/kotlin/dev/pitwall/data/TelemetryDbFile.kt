package dev.pitwall.data

/** Bump alongside the bundled telemetry.db when re-baking, so a stale copy is re-extracted on launch. */
const val TELEMETRY_VERSION = 2

/**
 * Human-facing freshness stamp for the bundled telemetry copy. The repo's default sample is SYNTHETIC
 * (tools/bake/make_sample_db.py); a real build replaces telemetry.db via tools/bake/bake.py and bumps
 * this. Surface it wherever telemetry is shown so sample data is never mistaken for real.
 */
const val TELEMETRY_LABEL = "Car data · 2026 season, rounds 1–7 (FastF1)"

expect suspend fun ensureTelemetryFile(): String
