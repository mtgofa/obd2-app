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

    @Synchronized
    fun attach(container: SurfaceContainer) {
        surface = container.surface
        width = container.width
        height = container.height
        if (visible.isEmpty) visible = Rect(0, 0, width, height)
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
            return
        }
        try {
            paint(canvas)
        } finally {
            runCatching { s.unlockCanvasAndPost(canvas) }
        }
    }

    private fun paint(c: Canvas) {
        fill.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), p.bgTop.toArgb(), p.bgBottom.toArgb(), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        fill.shader = null

        val area = RectF(visible)
        val pad = min(area.width(), area.height()) * 0.04f
        area.inset(pad, pad)
        val values = Obd.values.value

        // Top line: adapter and car status, same as the phone's bottom bar.
        val statusH = area.height() * 0.09f
        drawStatus(c, RectF(area.left, area.top, area.right, area.top + statusH))

        // Bottom row: four tiles. Middle: two gauges side by side.
        val tilesH = area.height() * 0.24f
        val gaugeArea = RectF(area.left, area.top + statusH, area.right, area.bottom - tilesH - pad * 0.5f)
        val half = gaugeArea.width() / 2
        val speedMax = if (Settings.mph) 160f else 240f
        val speed = values[0x0D]
        drawGauge(
            c, RectF(gaugeArea.left, gaugeArea.top, gaugeArea.left + half, gaugeArea.bottom),
            speed?.let { (Fmt.convert(PidUnit.SPEED, it) / speedMax).toFloat() },
            Fmt.value(PidUnit.SPEED, speed), Fmt.unit(PidUnit.SPEED), cyan, null,
        )
        val rpm = values[0x0C]
        drawGauge(
            c, RectF(gaugeArea.left + half, gaugeArea.top, gaugeArea.right, gaugeArea.bottom),
            rpm?.let { (it / 8000).toFloat() },
            Fmt.value(PidUnit.RPM, rpm), "rpm", if ((rpm ?: 0.0) >= 6000) red else magenta, 0.75f,
        )

        val tiles = TILE_PIDS.filter { Obd.isSupported(it) }.take(4)
        val tileW = area.width() / maxOf(tiles.size, 1)
        tiles.forEachIndexed { i, pid ->
            val r = RectF(area.left + i * tileW, area.bottom - tilesH, area.left + (i + 1) * tileW, area.bottom)
            r.inset(pad * 0.3f, 0f)
            drawTile(c, r, pid, values[pid])
        }
    }

    private fun drawStatus(c: Canvas, r: RectF) {
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
        label.textSize = r.height() * 0.5f
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
        val size = min(box.width(), box.height()) * 0.92f
        val oval = RectF(box.centerX() - size / 2, box.centerY() - size / 2, box.centerX() + size / 2, box.centerY() + size / 2)
        val w = size * 0.05f
        oval.inset(w * 1.5f, w * 1.5f)

        stroke.strokeWidth = w * 1.4f
        stroke.color = 0x14FFFFFF
        stroke.clearShadowLayer()
        c.drawArc(oval, 135f, 270f, false, stroke)

        if (redFrom != null) {
            stroke.strokeWidth = w * 0.4f
            stroke.color = (red and 0x00FFFFFF) or (0x99 shl 24)
            val outer = RectF(oval).apply { inset(-w * 1.4f, -w * 1.4f) }
            c.drawArc(outer, 135f + 270f * redFrom, 270f * (1 - redFrom), false, stroke)
        }

        val f = (fraction ?: 0f).coerceIn(0f, 1f)
        if (fraction != null && f > 0.002f) {
            stroke.strokeWidth = w
            stroke.color = color
            stroke.setShadowLayer(w * 1.6f, 0f, 0f, color)
            c.drawArc(oval, 135f, 270f * f, false, stroke)
            stroke.clearShadowLayer()
            stroke.strokeWidth = w * 0.3f
            stroke.color = 0x99FFFFFF.toInt()
            c.drawArc(oval, 135f, 270f * f, false, stroke)
        }

        number.textSize = size * (if (value.length >= 4) 0.2f else 0.26f)
        number.color = blendWhite(color)
        number.setShadowLayer(number.textSize * 0.35f, 0f, 0f, color)
        c.drawText(value, oval.centerX(), oval.centerY() + number.textSize * 0.35f, number)
        number.clearShadowLayer()

        label.textSize = size * 0.075f
        label.color = dim
        c.drawText(unit, oval.centerX(), oval.centerY() + number.textSize * 0.35f + label.textSize * 1.6f, label)
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

        label.textSize = r.height() * 0.2f
        label.color = dim
        c.drawText(pid0.short, r.centerX(), r.top + r.height() * 0.3f, label)

        number.textSize = r.height() * 0.38f
        number.color = blendWhite(color)
        number.setShadowLayer(number.textSize * 0.3f, 0f, 0f, color)
        c.drawText(Fmt.value(pid0.unit, v) + " " + Fmt.unit(pid0.unit), r.centerX(), r.top + r.height() * 0.78f, number)
        number.clearShadowLayer()
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

    companion object {
        /** Tiles in priority order; the first four the car supports are shown. */
        val TILE_PIDS = listOf(0x05, 0x5C, 0x0F, Virtual.BATTERY, 0x46, 0x04)
    }
}
