package com.rfsat.pocketspectro.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

data class Band(val from: Double, val to: Double, val color: Color)

/** Colour of the second (overlay) trace: a library match, a fit or a reference. */
val OverlayColor = Color(0xFF00897B)

/**
 * Line plot of a spectrum with auto-scaled axes. Pinch / drag zooms and pans the x axis,
 * a tap places a read-out cursor, double tap resets the view.
 */
@Composable
fun SpectrumChart(
    x: DoubleArray,
    y: DoubleArray,
    modifier: Modifier = Modifier,
    height: Dp = 280.dp,
    yUnit: String = "",
    xRange: ClosedFloatingPointRange<Double>? = null,
    bands: List<Band> = emptyList(),
    markers: List<Double> = emptyList(),
    hLine: Double? = null,
    /** Second trace (x, y) on the same axes, e.g. a library match or a fitted line. */
    overlay: Pair<DoubleArray, DoubleArray>? = null,
    /** Draw the main trace as dots instead of a line (calibration points, time series). */
    dots: Boolean = false,
    /** Fixed y axis (otherwise scaled to the visible data). */
    yRange: ClosedFloatingPointRange<Double>? = null,
) {
    val dark = isSystemInDarkTheme()
    val trace = if (dark) TraceColorDark else TraceColor
    val axis = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    val grid = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val textColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, color = textColor)

    var zoom by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var cursorX by remember { mutableStateOf<Double?>(null) }

    if (x.isEmpty() || x.size != y.size) return

    val fullMin = xRange?.start ?: x.first()
    val fullMax = xRange?.endInclusive ?: x.last()
    val (xMin, xMax) = zoom ?: (fullMin to fullMax)

    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .pointerInput(fullMin, fullMax) {
                detectTransformGestures { centroid, pan, gestureZoom, _ ->
                    val (a, b) = zoom ?: (fullMin to fullMax)
                    val w = size.width - LEFT_PX
                    if (w <= 0) return@detectTransformGestures
                    val span = b - a
                    val cx = a + ((centroid.x - LEFT_PX) / w).coerceIn(0f, 1f) * span
                    val newSpan = (span / gestureZoom).coerceIn((fullMax - fullMin) / 200.0, fullMax - fullMin)
                    var na = cx - (cx - a) * newSpan / span - pan.x / w * newSpan
                    na = na.coerceIn(fullMin, fullMax - newSpan)
                    zoom = na to (na + newSpan)
                }
            }
            .pointerInput(fullMin, fullMax) {
                detectTapGestures(
                    onDoubleTap = { zoom = null; cursorX = null },
                    onTap = { p ->
                        val (a, b) = zoom ?: (fullMin to fullMax)
                        val w = size.width - LEFT_PX
                        cursorX = if (w > 0) a + ((p.x - LEFT_PX) / w).coerceIn(0f, 1f) * (b - a) else null
                    },
                )
            }
    ) {
        val left = LEFT_PX
        val bottom = size.height - BOTTOM_PX
        val plotW = size.width - left - 8f
        val plotH = bottom - 8f
        if (plotW <= 0f || plotH <= 0f) return@Canvas

        // y range over visible points
        var lo = Double.POSITIVE_INFINITY
        var hi = Double.NEGATIVE_INFINITY
        for (i in x.indices) {
            if (x[i] < xMin || x[i] > xMax) continue
            val v = y[i]
            if (v.isFinite()) { if (v < lo) lo = v; if (v > hi) hi = v }
        }
        hLine?.let { if (it.isFinite()) { lo = minOf(lo, it); hi = maxOf(hi, it) } }
        overlay?.let { (ox, oy) -> for (i in ox.indices) if (ox[i] in xMin..xMax && oy.getOrNull(i)?.isFinite() == true) { lo = minOf(lo, oy[i]); hi = maxOf(hi, oy[i]) } }
        if (!lo.isFinite() || !hi.isFinite()) { lo = 0.0; hi = 1.0 }
        if (hi - lo < 1e-9) { hi += 0.5; lo -= 0.5 }
        val pad = (hi - lo) * 0.05
        lo -= pad; hi += pad
        if (yRange != null) { lo = yRange.start; hi = yRange.endInclusive }

        fun px(v: Double) = left + ((v - xMin) / (xMax - xMin) * plotW).toFloat()
        fun py(v: Double) = 8f + ((hi - v) / (hi - lo) * plotH).toFloat()

        // bands
        for (bnd in bands) {
            val a = px(bnd.from.coerceIn(xMin, xMax)); val b = px(bnd.to.coerceIn(xMin, xMax))
            if (b > a) drawRect(bnd.color, Offset(a, 8f), Size(b - a, plotH))
        }

        // grid + labels
        for (t in niceTicks(lo, hi, 5)) {
            val yy = py(t)
            drawLine(grid, Offset(left, yy), Offset(left + plotW, yy))
            val lay = measurer.measure(fmt(t), labelStyle)
            drawText(lay, topLeft = Offset(left - lay.size.width - 4f, yy - lay.size.height / 2f))
        }
        for (t in niceTicks(xMin, xMax, 6)) {
            val xx = px(t)
            drawLine(grid, Offset(xx, 8f), Offset(xx, bottom))
            val lay = measurer.measure(fmt(t), labelStyle)
            drawText(lay, topLeft = Offset(xx - lay.size.width / 2f, bottom + 4f))
        }
        drawLine(axis, Offset(left, 8f), Offset(left, bottom))
        drawLine(axis, Offset(left, bottom), Offset(left + plotW, bottom))

        hLine?.let {
            val yy = py(it)
            drawLine(axis, Offset(left, yy), Offset(left + plotW, yy), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
        }

        // trace
        if (dots) {
            for (i in x.indices) if (x[i] in xMin..xMax && y[i].isFinite()) drawCircle(trace, 5f, Offset(px(x[i]), py(y[i])))
        } else {
            val path = Path()
            var started = false
            for (i in x.indices) {
                if (x[i] < xMin || x[i] > xMax || !y[i].isFinite()) { started = false; continue }
                val p = Offset(px(x[i]), py(y[i]))
                if (!started) { path.moveTo(p.x, p.y); started = true } else path.lineTo(p.x, p.y)
            }
            drawPath(path, trace, style = Stroke(width = 2.2f))
        }

        // Overlay on top of the trace, so it stays visible over a noisy spectrum.
        overlay?.let { (ox, oy) ->
            val op = Path(); var st = false
            for (i in ox.indices) {
                if (ox[i] < xMin || ox[i] > xMax || oy.getOrNull(i)?.isFinite() != true) { st = false; continue }
                val p = Offset(px(ox[i]), py(oy[i]))
                if (!st) { op.moveTo(p.x, p.y); st = true } else op.lineTo(p.x, p.y)
            }
            drawPath(op, OverlayColor, style = Stroke(width = 2.4f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f))))
        }

        for (m in markers) {
            if (m < xMin || m > xMax) continue
            val xx = px(m)
            drawLine(FireRed, Offset(xx, 8f), Offset(xx, bottom), strokeWidth = 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
        }

        cursorX?.let { cx ->
            var best = 0
            for (i in x.indices) if (abs(x[i] - cx) < abs(x[best] - cx)) best = i
            val xx = px(x[best])
            drawLine(axis, Offset(xx, 8f), Offset(xx, bottom))
            val txt = "%.2f  →  %s %s".format(Locale.US, x[best], fmt(y[best]), yUnit)
            val lay = measurer.measure(txt, labelStyle.copy(fontSize = 12.sp))
            val tx = (xx + 6f).coerceAtMost(left + plotW - lay.size.width)
            drawRect(Color.Black.copy(alpha = if (dark) 0.6f else 0.08f), Offset(tx - 3f, 10f), Size(lay.size.width + 6f, lay.size.height + 4f))
            drawText(lay, topLeft = Offset(tx, 12f))
        }
    }
}

private const val LEFT_PX = 110f
private const val BOTTOM_PX = 40f

private fun fmt(v: Double): String = when {
    !v.isFinite() -> "—"
    abs(v) >= 1000 -> "%.0f".format(Locale.US, v)
    abs(v) >= 10 -> "%.1f".format(Locale.US, v)
    else -> "%.3f".format(Locale.US, v)
}

fun niceTicks(lo: Double, hi: Double, target: Int): List<Double> {
    val span = hi - lo
    if (span <= 0 || !span.isFinite()) return emptyList()
    val raw = span / target
    val mag = 10.0.pow(floor(log10(raw)))
    val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * mag }.first { it >= raw }
    val out = ArrayList<Double>()
    var t = ceil(lo / step) * step
    while (t <= hi + 1e-9) { out += t; t += step }
    return out
}
