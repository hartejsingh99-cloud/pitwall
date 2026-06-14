package dev.pitwall.util

/**
 * The current local calendar month-day as "MM-DD" (zero-padded), to match the
 * strftime('%m-%d', date) produced by the On-This-Day query. "Now" needs a platform clock,
 * so this is an expect fun with java.time-backed actuals on Android and desktop.
 */
expect fun currentMonthDay(): String
