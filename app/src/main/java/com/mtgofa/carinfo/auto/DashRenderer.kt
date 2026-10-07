package com.mtgofa.carinfo.auto

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.Surface
import androidx.car.app.SurfaceContainer
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.res.ResourcesCompat
import com.mtgofa.carinfo.Fmt
import com.mtgofa.carinfo.R
import com.mtgofa.carinfo.Settings
import com.mtgofa.carinfo.obd.CarState
import com.mtgofa.carinfo.obd.LinkState
import com.mtgofa.carinfo.obd.Obd
import com.mtgofa.carinfo.obd.PidUnit
import com.mtgofa.carinfo.obd.Pids
import com.mtgofa.carinfo.obd.Virtual
import com.mtgofa.carinfo.ui.DarkPalette
import kotlin.math.min

/**
 * Draws the dark dashboard (speed + RPM arcs, temperature tiles, link status) on the Android Auto
 * surface with plain android.graphics, since Compose can't render into a car surface.
 */
class DashRenderer(context: Context) {
    companion object {
        private fun padFor(r: Rect) = min(r.width(), r.height()) * 0.03f

        /** Where the widget grid sits inside the visible area: below the status line. Shared with the phone editor. */
        /** The status line ("OBD2 … · Car …") at the top of the visible area. Shared with the phone editor. */
        fun statusRect(visible: Rect): RectF {
            val area = RectF(visible)
            val pad = padFor(visible)
            area.inset(pad, pad)
            val statusH = area.height() * 0.07f
            return RectF(area.left, area.top - pad * 0.8f, area.right, area.top - pad * 0.8f + statusH)
        }

        fun gridRect(visible: Rect): RectF {
            val area = RectF(visible)
            val pad = padFor(visible)
            area.inset(pad, pad)
            val statusH = area.height() * 0.07f
            return RectF(area.left, area.top + statusH, area.right, area.bottom)
        }
    }

    private var surface: Surface? = null
    private var width = 0
    private var height = 0
    private var visible = Rect()

    private val p = DarkPalette
    private val cyan = p.cyan.toArgb()
    private val magenta = p.magenta.toArgb()
    private val red = p.red.toArgb()
    private val text = p.text.toArgb()
    private val dim = p.dim.toArgb()

    private val digits: Typeface = ResourcesCompat.getFont(context, R.font.plexmono_medium) ?: Typeface.MONOSPACE
    private val heading: Typeface = ResourcesCompat.getFont(context, R.font.sora_semibold) ?: Typeface.DEFAULT_BOLD

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = heading; textAlign = Paint.Align.CENTER }
    private val number = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = digits; textAlign = Paint.Align.CENTER }
    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = heading; textAlign = Paint.Align.LEFT }

    @Synchronized
    fun attach(container: SurfaceContainer) {
        surface = container.surface
        width = container.width
        height = container.height
        // Keep the visible area the host reported (it may arrive before this call, e.g. for the
        // home-screen card); only fall back to the whole surface, and never draw past its edges.
        if (visible.isEmpty || !visible.intersect(0, 0, width, height)) visible = Rect(0, 0, width, height)
    }

    @Synchronized
    fun detach() {
        surface = null
    }

    @Synchronized
    fun setVisibleArea(area: Rect) {
        visible = Rect(area)
    }

    @Synchronized
    fun draw() {
        val s = surface ?: return
        if (!s.isValid || width == 0) return
        val canvas = try {
            s.lockCanvas(null)
        } catch (e: Exception) {
            // Happens when the surface is still held by a previous process (e.g. right after an
            // app update); the host hands over a fresh one when the screen is reopened.
            return
        }
        try {
            paint(canvas)
        } finally {
            runCatching { s.unlockCanvasAndPost(canvas) }
        }
    }

    private fun paint(c: Canvas) = paint(c, visible, Obd.values.value)

    /** Same drawing on any canvas: the phone's editor uses it as a live preview. */
    /** [statusLeft] leaves room at the start of the status line, e.g. for the editor's back button. */
    fun preview(c: Canvas, w: Int, h: Int, values: Map<Int, Double>, statusLeft: Float = 0f) {
        c.save()
        c.clipRect(0, 0, w, h)
        paint(c, Rect(0, 0, w, h), values, background = Rect(0, 0, w, h), statusLeft = statusLeft)
        c.restore()
    }

    private fun paint(
        c: Canvas, visibleArea: Rect, values: Map<Int, Double>,
        background: Rect = Rect(0, 0, c.width, c.height), statusLeft: Float = 0f,
    ) {
        // Paint the whole canvas every frame, at its real size, so nothing from a previous size shows through.
        val bg = RectF(background)
        fill.shader = LinearGradient(0f, bg.top, 0f, bg.bottom, p.bgTop.toArgb(), p.bgBottom.toArgb(), Shader.TileMode.CLAMP)
        c.drawRect(bg, fill)
        fill.shader = null

        val area = RectF(visibleArea)
        val pad = padFor(visibleArea)
        area.inset(pad, pad)

        // Top line: adapter and car status, same as the phone's bottom bar. Sits in the top padding.
        val statusH = area.height() * 0.07f
        drawStatus(c, statusRect(visibleArea).apply { left += statusLeft }, visibleArea)

        // The user's widgets on the grid.
        val grid = gridRect(visibleArea)
        val cw = grid.width() / AutoLayout.COLS
        val ch = grid.height() / AutoLayout.ROWS
        val gap = pad * 0.35f
        AutoLayout.widgets.forEach { wd ->
            val box = RectF(grid.left + wd.x * cw, grid.top + wd.y * ch, grid.left + (wd.x + wd.w) * cw, grid.top + (wd.y + wd.h) * ch)
            box.inset(gap, gap)
            if (wd.gauge) gaugeFor(c, box, wd.pid, values[wd.pid]) else drawTile(c, box, wd.pid, values[wd.pid])
        }
    }

    /** Range, redline and colour per reading, so any chosen PID gets a sensible gauge. */
    private fun gaugeFor(c: Canvas, box: RectF, pid: Int, v: Double?) {
        val p0 = Pids[pid] ?: return
        val text = Fmt.value(p0.unit, v)
        // km/h and rpm speak for themselves; any other gauge names its reading ("Load %", "Boost bar").
        val unit = if (pid == 0x0D || pid == 0x0C) Fmt.unit(p0.unit) else "${p0.short}  ${Fmt.unit(p0.unit)}"
        when {
            pid == 0x0D -> {
                val max = if (Settings.mph) 160f else 240f
                drawGauge(c, box, v?.let { (Fmt.convert(PidUnit.SPEED, it) / max).toFloat() }, text, unit, cyan, null)
            }
            pid == 0x0C -> drawGauge(c, box, v?.let { (it / 8000).toFloat() }, text, unit, if ((v ?: 0.0) >= 6000) red else magenta, 0.75f)
            p0.unit == PidUnit.TEMP -> {
                val (lo, hi, redline) = if (pid in listOf(0x3C, 0x3D, 0x3E, 0x3F, 0x78, 0x79)) Triple(0.0, 1000.0, 850.0)
                else if (pid == 0x5C) Triple(0.0, 150.0, 125.0) else Triple(0.0, 140.0, 110.0)
                drawGauge(c, box, v?.let { ((it - lo) / (hi - lo)).toFloat() }, text, unit, tempColor(pid, v), (redline / hi).toFloat())
            }
            pid == Virtual.BATTERY ->
                drawGauge(c, box, v?.let { ((it - 10) / 6).toFloat() }, text, unit, p.amber.toArgb(), null)
            else -> drawGauge(c, box, v?.let { ((it - p0.min) / (p0.max - p0.min)).toFloat() }, text, unit, p.violet.toArgb(), null)
        }
    }

    private fun drawStatus(c: Canvas, r: RectF, visible: Rect) {
        val link = Obd.link.value
        val adapter = when (link.state) {
            LinkState.Connected, LinkState.Initializing -> link.via.substringAfter(" · ").ifBlank { "Connected" } to p.green.toArgb()
            LinkState.Connecting -> "Connecting…" to p.amber.toArgb()
            LinkState.Error -> "Not connected" to red
            LinkState.Disconnected -> "Not connected" to dim
        }
        val car = when (link.car) {
            CarState.Online -> (Obd.vehicle.value?.title ?: "Online") to p.green.toArgb()
            CarState.Waiting -> "Ignition off?" to p.amber.toArgb()
            CarState.Lost -> "Not responding" to red
            CarState.None -> "—" to dim
        }
        label.textSize = maxOf(r.height() * 0.6f, min(visible.width(), visible.height()) * 0.045f)
        val y = r.centerY() + label.textSize * 0.35f
        label.textAlign = Paint.Align.LEFT
        drawDot(c, r.left + label.textSize * 0.4f, r.centerY(), label.textSize * 0.28f, adapter.second)
        label.color = text
        c.drawText("OBD2  ${adapter.first}", r.left + label.textSize, y, label)
        val carText = "Car  ${car.first}"
        val carX = r.centerX()
        drawDot(c, carX + label.textSize * 0.4f, r.centerY(), label.textSize * 0.28f, car.second)
        c.drawText(carText, carX + label.textSize, y, label)
        label.textAlign = Paint.Align.CENTER
    }

    private fun drawDot(c: Canvas, x: Float, y: Float, radius: Float, color: Int) {
        fill.color = color
        c.drawCircle(x, y, radius, fill)
    }

    /** 270° arc with a glowing value arc and lit digits in the middle; [redFrom] marks the redline. */
    private fun drawGauge(c: Canvas, box: RectF, fraction: Float?, value: String, unit: String, color: Int, redFrom: Float?) {
        // Wide, short boxes (2×1, 3×1…) get a half-circle gauge that uses the width; others a 270° dial.
        // Cells are a bit wider than tall, so 1×1 and 2×2 boxes are ~1.5:1; only 2×1-and-wider count as wide.
        val wide = box.width() >= box.height() * 2.2f
        val start = if (wide) 180f else 135f
        val sweep = if (wide) 180f else 270f
        val size = if (wide) min(box.width(), box.height() * 1.9f) else min(box.width(), box.height())
        // A half circle only needs half its diameter in height: sit its centre near the bottom of the box.
        val cy = if (wide) box.bottom - size * 0.08f else box.centerY()
        val oval = RectF(box.centerX() - size / 2, cy - size / 2, box.centerX() + size / 2, cy + size / 2)
        val w = size * 0.05f
        oval.inset(w * 1.5f, w * 1.5f)

        stroke.strokeWidth = w * 1.4f
        stroke.color = 0x14FFFFFF
        stroke.clearShadowLayer()
        c.drawArc(oval, start, sweep, false, stroke)

        if (redFrom != null) {
            stroke.strokeWidth = w * 0.4f
            stroke.color = (red and 0x00FFFFFF) or (0x99 shl 24)
            val outer = RectF(oval).apply { inset(-w * 1.4f, -w * 1.4f) }
            c.drawArc(outer, start + sweep * redFrom, sweep * (1 - redFrom), false, stroke)
        }

        val f = (fraction ?: 0f).coerceIn(0f, 1f)
        if (fraction != null && f > 0.002f) {
            stroke.strokeWidth = w
            stroke.color = color
            stroke.setShadowLayer(w * 1.6f, 0f, 0f, color)
            c.drawArc(oval, start, sweep * f, false, stroke)
            stroke.clearShadowLayer()
            stroke.strokeWidth = w * 0.3f
            stroke.color = 0x99FFFFFF.toInt()
            c.drawArc(oval, start, sweep * f, false, stroke)
        }

        // Digits sized to the dial, and never taller than the space under the arc's top.
        val avail = if (wide) (cy - oval.top) * 0.62f else size
        number.textSize = min(avail * (if (wide) 0.75f else 1f), size * (if (value.length >= 4) 0.24f else 0.32f))
        number.color = blendWhite(color)
        number.setShadowLayer(number.textSize * 0.35f, 0f, 0f, color)
        label.textSize = number.textSize * 0.32f
        // Half circle: unit just above the bottom edge, number above it. Dial: number centred, unit below.
        val unitY = if (wide) cy - size * 0.03f else oval.centerY() + number.textSize * 0.35f + label.textSize * 1.6f
        val numY = if (wide) unitY - label.textSize * 1.5f else oval.centerY() + number.textSize * 0.35f
        c.drawText(value, oval.centerX(), numY, number)
        number.clearShadowLayer()

        label.color = dim
        c.drawText(unit, oval.centerX(), unitY, label)
    }

    private fun drawTile(c: Canvas, r: RectF, pid: Int, v: Double?) {
        val pid0 = Pids[pid] ?: return
        fill.color = p.card.toArgb()
        c.drawRoundRect(r, r.height() * 0.18f, r.height() * 0.18f, fill)
        val color = when {
            pid0.unit == PidUnit.TEMP -> tempColor(pid, v)
            pid == Virtual.BATTERY -> if (v != null && (v < 11.8 || v > 15.0)) red else p.amber.toArgb()
            else -> p.violet.toArgb()
        }
        stroke.strokeWidth = 2f
        stroke.color = (color and 0x00FFFFFF) or (0x80 shl 24)
        c.drawRoundRect(r, r.height() * 0.18f, r.height() * 0.18f, stroke)

        val valueText = Fmt.value(pid0.unit, v)
        val unitText = Fmt.unit(pid0.unit)
        // Fit both the height and the width, since a tile can be wide (1 per row) or narrow (4 per row).
        label.textSize = min(r.height() * 0.2f, r.width() / (pid0.short.length * 0.62f + 1))
        label.color = dim
        c.drawText(pid0.short, r.centerX(), r.top + r.height() * 0.3f, label)

        // Big lit number with a small unit after it, the pair centred in the tile.
        val unitRatio = 0.45f
        val chars = valueText.length + unitText.length * unitRatio + 0.6f
        number.textSize = min(r.height() * 0.46f, r.width() / (chars * 0.62f))
        number.color = blendWhite(color)
        number.textAlign = Paint.Align.LEFT
        val vw = number.measureText(valueText)
        unitPaint.textSize = number.textSize * unitRatio
        val uw = if (unitText.isEmpty()) 0f else unitPaint.measureText(unitText) + number.textSize * 0.15f
        val x = r.centerX() - (vw + uw) / 2
        val base = r.top + r.height() * 0.8f
        number.setShadowLayer(number.textSize * 0.3f, 0f, 0f, color)
        c.drawText(valueText, x, base, number)
        number.clearShadowLayer()
        number.textAlign = Paint.Align.CENTER
        unitPaint.color = (color and 0x00FFFFFF) or (0xCC shl 24)
        if (unitText.isNotEmpty()) c.drawText(unitText, x + vw + number.textSize * 0.15f, base, unitPaint)
    }

    private fun tempColor(pid: Int, v: Double?): Int {
        if (v == null) return p.violet.toArgb()
        val hot = if (pid == 0x5C) 125.0 else if (pid == 0x0F || pid == 0x46) 60.0 else 108.0
        return when {
            v >= hot -> red
            pid == 0x05 && v < 60 -> cyan
            else -> p.green.toArgb()
        }
    }

    private fun blendWhite(color: Int): Int {
        val r = ((color shr 16 and 0xFF) + 255) / 2
        val g = ((color shr 8 and 0xFF) + 255) / 2
        val b = ((color and 0xFF) + 255) / 2
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

}
