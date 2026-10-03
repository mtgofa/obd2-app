package com.mtgofa.carinfo.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private const val START = 135f
private const val SWEEP = 270f

private fun DrawScope.glowArc(color: Color, start: Float, sweep: Float, inset: Float, width: Float) {
    val size = Size(this.size.width - inset * 2, this.size.height - inset * 2)
    val tl = Offset(inset, inset)
    // Stacked translucent strokes fake a bloom that renders the same on every GPU.
    for ((w, a) in listOf(4.2f to 0.06f, 3.0f to 0.10f, 2.0f to 0.18f)) {
        drawArc(color.copy(alpha = a), start, sweep, false, tl, size, style = Stroke(width * w, cap = StrokeCap.Round))
    }
    drawArc(color, start, sweep, false, tl, size, style = Stroke(width, cap = StrokeCap.Round))
    drawArc(Color.White.copy(alpha = 0.55f), start, sweep, false, tl, size, style = Stroke(width * 0.3f, cap = StrokeCap.Round))
}

/**
 * A 270° neon arc gauge with ticks, labelled scale and glowing digits in the middle.
 * [display] is the already-formatted value; [fraction] positions the arc.
 */
@Composable
fun ArcGauge(
    fraction: Float?,
    display: String,
    unit: String,
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
    scaleMax: Float = 240f,
    majorStep: Float = 20f,
    redFrom: Float? = null,
    scaleDivisor: Float = 1f,
) {
    val anim by animateFloatAsState((fraction ?: 0f).coerceIn(0f, 1f), tween(220), label = "gauge")
    val pal = LocalPalette.current
    BoxWithConstraints(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        val d = maxWidth
        Canvas(Modifier.fillMaxSize()) {
            val w = size.minDimension
            val stroke = w * 0.035f
            val inset = w * 0.09f
            val track = Size(size.width - inset * 2, size.height - inset * 2)
            drawArc(Color.White.copy(alpha = 0.07f), START, SWEEP, false, Offset(inset, inset), track, style = Stroke(stroke * 1.6f, cap = StrokeCap.Round))

            if (redFrom != null) {
                val rf = redFrom / scaleMax
                drawArc(pal.red.copy(alpha = 0.55f), START + SWEEP * rf, SWEEP * (1 - rf), false,
                    Offset(inset - stroke * 1.5f, inset - stroke * 1.5f),
                    Size(track.width + stroke * 3, track.height + stroke * 3), style = Stroke(stroke * 0.5f))
            }

            // Ticks and scale numbers.
            val c = Offset(size.width / 2, size.height / 2)
            val rOuter = track.width / 2 - stroke * 1.6f
            val minorCount = (scaleMax / majorStep * 5).roundToInt()
            val paint = android.graphics.Paint().apply {
                isAntiAlias = true
                textAlign = android.graphics.Paint.Align.CENTER
                textSize = w * 0.05f
                this.color = pal.dim.toArgb()
            }
            for (i in 0..minorCount) {
                val f = i.toFloat() / minorCount
                val ang = Math.toRadians((START + SWEEP * f).toDouble())
                val major = i % 5 == 0
                val len = if (major) w * 0.05f else w * 0.025f
                val tickColor = when {
                    redFrom != null && f * scaleMax >= redFrom -> pal.red
                    f <= anim -> color
                    else -> Color.White.copy(alpha = 0.25f)
                }
                val p1 = Offset(c.x + (rOuter * cos(ang)).toFloat(), c.y + (rOuter * sin(ang)).toFloat())
                val p2 = Offset(c.x + ((rOuter - len) * cos(ang)).toFloat(), c.y + ((rOuter - len) * sin(ang)).toFloat())
                drawLine(tickColor.copy(alpha = if (major) 0.9f else 0.5f), p1, p2, strokeWidth = if (major) w * 0.008f else w * 0.004f)
                if (major) {
                    val rl = rOuter - w * 0.085f
                    val lx = c.x + (rl * cos(ang)).toFloat()
                    val ly = c.y + (rl * sin(ang)).toFloat() + paint.textSize * 0.35f
                    drawIntoCanvas { it.nativeCanvas.drawText((f * scaleMax / scaleDivisor).roundToInt().toString(), lx, ly, paint) }
                }
            }

            if (fraction != null && anim > 0.002f) {
                glowArc(color, START, SWEEP * anim, inset, stroke)
                // Hot spot at the arc tip.
                val ang = Math.toRadians((START + SWEEP * anim).toDouble())
                val r = track.width / 2
                val tip = Offset(c.x + (r * cos(ang)).toFloat(), c.y + (r * sin(ang)).toFloat())
                drawCircle(color.copy(alpha = 0.25f), stroke * 2.6f, tip)
                drawCircle(Color.White, stroke * 0.8f, tip)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            GlowText(display, color, (d.value * (if (display.length >= 4) 0.155f else 0.2f)).sp)
            Text(unit, color = AppColors.dim, fontSize = (d.value * 0.065f).sp, fontFamily = Body)
        }
        Text(
            label.uppercase(), color = color.copy(alpha = 0.85f), fontSize = (d.value * 0.055f).sp,
            fontFamily = Sora, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp,
            modifier = Modifier.align(Alignment.BottomCenter).offset(y = (-d.value * 0.04f).dp),
        )
    }
}

/** Compact tile: label, glowing value, unit and a thin level bar. */
@Composable
fun ValueTile(
    label: String,
    value: String,
    unit: String,
    color: Color,
    fraction: Float?,
    modifier: Modifier = Modifier,
    valueSize: Int = 30,
) {
    val anim by animateFloatAsState((fraction ?: 0f).coerceIn(0f, 1f), tween(300), label = "tile")
    AppCard(modifier, accent = color.copy(alpha = 0.5f)) {
        Text(label, color = AppColors.dim, fontSize = 12.sp, fontFamily = Sora, fontWeight = FontWeight.SemiBold, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.Bottom) {
            GlowText(value, color, valueSize.sp)
            Text(" $unit", color = AppColors.dim, fontSize = 12.sp, fontFamily = Body, modifier = Modifier.offset(y = (-4).dp))
        }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(AppColors.text.copy(alpha = 0.08f))
        ) {
            if (fraction != null) {
                Box(
                    Modifier.fillMaxWidth(anim).height(4.dp).clip(RoundedCornerShape(2.dp)).background(color)
                )
            }
        }
    }
}
