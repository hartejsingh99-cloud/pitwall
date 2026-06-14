package dev.pitwall.domain

/**
 * Pure, multiplatform formatting + derive helpers for the Browse feature.
 * NO java.* / String.format / kotlinx-datetime — numbers are formatted by hand.
 */

/**
 * Format a lap time given in milliseconds as F1-style "m:ss.cc" (or "s.cc" under a minute).
 * Centiseconds are rounded (matches the hero engine's millisToLapTime style). null -> em dash.
 */
fun millisToLapTime(ms: Long?): String {
    if (ms == null) return "—"
    val totalCs = (ms + 5) / 10                 // centiseconds, rounded to nearest
    val minutes = totalCs / 6000
    val seconds = (totalCs / 100) % 60
    val cs = totalCs % 100
    fun p2(n: Long) = n.toString().padStart(2, '0')
    return if (minutes > 0) "${minutes}:${p2(seconds)}.${p2(cs)}" else "${seconds}.${p2(cs)}"
}

/** A finishing gap string straight from the dataset ("+2.974", "+1 lap"). null/blank -> em dash. */
fun formatGap(gap: String?): String {
    val g = gap?.trim()
    return if (g.isNullOrEmpty()) "—" else g
}

/**
 * Championship points: drop a ".0" tail (25.0 -> "25") but keep a real fraction (0.5 -> "0.5",
 * 12.5 -> "12.5"). Multiplatform-safe: rounds to a tenth manually, no String.format.
 *
 * NOTE: named `formatStandingPoints` (not `formatPoints`) because `dev.pitwall.domain` already owns
 * `internal fun formatPoints(Double): String` in TitleCalculator.kt — a same-package, same-signature
 * top-level `formatPoints` here would be a duplicate-declaration compile error.
 */
fun formatStandingPoints(points: Double): String {
    val tenths = kotlin.math.round(points * 10.0).toLong()   // points to nearest 0.1
    val whole = tenths / 10
    val frac = tenths % 10
    return if (frac == 0L) whole.toString() else "$whole.$frac"
}

/**
 * One row in the season schedule. [isRun] / [isSprintWeekend] are derived per the verified facts:
 * a race is "run" iff it has result rows; a weekend is a sprint weekend iff sprint_qualifying_format
 * is non-null (it is genuinely NULL — not "" — for ordinary weekends in the bundle).
 */
data class RaceListItem(
    val raceId: Int,
    val round: Int,
    val grandPrixName: String,
    val circuitName: String,
    val placeName: String,
    val date: String,
    val resultCount: Int,
    val sprintQualifyingFormat: String?,
) {
    val isRun: Boolean get() = resultCount > 0
    val isSprintWeekend: Boolean get() = sprintQualifyingFormat != null
}

/** Canonical session ordering for the result tab row. */
enum class SessionTab(val label: String) {
    QUALIFYING("Qualifying"),
    SPRINT_QUALIFYING("Sprint Qualifying"),
    SPRINT("Sprint"),
    RACE("Race"),
}

/** Which session tabs to show, in canonical order, given the per-session row counts. */
data class AvailableSessions(
    val qualifying: Boolean,
    val sprintQualifying: Boolean,
    val sprint: Boolean,
    val race: Boolean,
) {
    val tabs: List<SessionTab>
        get() = buildList {
            if (qualifying) add(SessionTab.QUALIFYING)
            if (sprintQualifying) add(SessionTab.SPRINT_QUALIFYING)
            if (sprint) add(SessionTab.SPRINT)
            if (race) add(SessionTab.RACE)
        }
}
