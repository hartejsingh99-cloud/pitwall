package dev.pitwall.util

import java.time.LocalDate

// JVM target — java.time and String.format are both available here.
actual fun currentMonthDay(): String {
    val today = LocalDate.now()
    return String.format("%02d-%02d", today.monthValue, today.dayOfMonth)
}
