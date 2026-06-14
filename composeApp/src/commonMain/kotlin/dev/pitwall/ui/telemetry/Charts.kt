package dev.pitwall.ui.telemetry

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import dev.pitwall.domain.scaleToCanvas

/**
 * Charts are hand-drawn with Compose Canvas — no chart library, matching the app's no-nav-lib /
 * no-icon-pack minimalism. All geometry goes through the pure, unit-tested scaleToCanvas() so the
 * mapping logic is testable independently of the (untestable) draw calls.
 */

/** A single labelled telemetry channel drawn as a normalized polyline over the lap's distance axis. */
@Composable
fun ChannelStrip(
    label: String,
    distance: List<Double>,
    values: List<Double>,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        val lo = values.minOrNull() ?: 0.0
        val hi = values.maxOrNull() ?: 0.0
        Text(
            "$label  (${fmt1(lo)}–${fmt1(hi)})",
            style = MaterialTheme.typography.labelSmall,
        )
        Canvas(Modifier.fillMaxWidth().height(56.dp)) {
            if (distance.size < 2 || distance.size != values.size) return@Canvas
            val (px, py) = scaleToCanvas(distance, values, size.width.toDouble(), size.height.toDouble(), pad = 2.0)
            val path = Path().apply {
                moveTo(px[0].toFloat(), py[0].toFloat())
                for (i in 1 until px.size) lineTo(px[i].toFloat(), py[i].toFloat())
            }
            drawPath(path, color, style = Stroke(width = 2f))
        }
    }
}

/** The lap's racing line (x/y) colored by an intensity channel (speed): slow = red, fast = green. */
@Composable
fun TrackMapCanvas(
    x: List<Double>,
    y: List<Double>,
    intensity: List<Double>?,
    modifier: Modifier = Modifier,
) {
    val slow = Color(0xFFD32F2F)
    val fast = Color(0xFF2E7D32)
    Canvas(modifier) {
        if (x.size < 2 || x.size != y.size) return@Canvas
        // preserveAspect: the racing line must keep the circuit's true proportions, not stretch to fill.
        val (px, py) = scaleToCanvas(x, y, size.width.toDouble(), size.height.toDouble(), pad = 12.0, preserveAspect = true)
        val iLo = intensity?.minOrNull() ?: 0.0
        val iHi = intensity?.maxOrNull() ?: 1.0
        val range = (iHi - iLo).takeIf { it != 0.0 } ?: 1.0
        for (i in 1 until px.size) {
            val frac = intensity?.let { ((it[i] - iLo) / range).coerceIn(0.0, 1.0) } ?: 1.0
            drawLine(
                color = lerp(slow, fast, frac.toFloat()),
                start = Offset(px[i - 1].toFloat(), py[i - 1].toFloat()),
                end = Offset(px[i].toFloat(), py[i].toFloat()),
                strokeWidth = 3f,
            )
        }
    }
}

/** Delta-time trace over distance, with a zero baseline. Positive (comparison slower) drawn in red. */
@Composable
fun DeltaCanvas(delta: List<Double>, modifier: Modifier = Modifier) {
    val ahead = Color(0xFF2E7D32)
    val behind = Color(0xFFD32F2F)
    val baseline = Color(0xFF9E9E9E)
    Column(modifier.fillMaxWidth()) {
        val lo = delta.minOrNull() ?: 0.0
        val hi = delta.maxOrNull() ?: 0.0
        Text(
            "Δ time vs reference  (${fmtSigned(lo)}s … ${fmtSigned(hi)}s)",
            style = MaterialTheme.typography.labelSmall,
        )
        Canvas(Modifier.fillMaxWidth().height(160.dp)) {
            if (delta.size < 2) return@Canvas
            val xs = delta.indices.map { it.toDouble() }
            // include 0.0 in the y-range so the baseline is always on-screen
            val ys = delta + 0.0
            val xsForScale = xs + xs.last()
            val (px, py) = scaleToCanvas(xsForScale, ys, size.width.toDouble(), size.height.toDouble(), pad = 4.0)
            val zeroY = py.last().toFloat()  // the appended 0.0 sample
            drawLine(baseline, Offset(0f, zeroY), Offset(size.width, zeroY), strokeWidth = 1f)
            // Color each segment by its own sign so the trace honestly shows where the comparison was
            // ahead (green, below the baseline) vs behind (red), matching what the zero baseline implies.
            for (i in 1 until delta.size) {
                drawLine(
                    color = if (delta[i] >= 0.0) behind else ahead,
                    start = Offset(px[i - 1].toFloat(), py[i - 1].toFloat()),
                    end = Offset(px[i].toFloat(), py[i].toFloat()),
                    strokeWidth = 2f,
                )
            }
        }
    }
}

/** Compact, multiplatform-safe number formatting (no JVM String.format). */
internal fun fmt1(v: Double): String {
    val scaled = kotlin.math.round(v * 10).toLong()
    val whole = scaled / 10
    val frac = kotlin.math.abs(scaled % 10)
    return "$whole.$frac"
}

internal fun fmtSigned(v: Double): String {
    val s = fmt1(kotlin.math.abs(v))
    return if (v < 0) "-$s" else "+$s"
}
