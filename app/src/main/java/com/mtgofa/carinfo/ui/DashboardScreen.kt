package com.mtgofa.carinfo.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mtgofa.carinfo.Fmt
import com.mtgofa.carinfo.Settings
import com.mtgofa.carinfo.obd.LinkState
import com.mtgofa.carinfo.obd.Obd
import com.mtgofa.carinfo.obd.PidUnit
import com.mtgofa.carinfo.obd.Pids
import com.mtgofa.carinfo.obd.Virtual

/** Registers the PIDs a screen needs while it is visible. */
@Composable
fun Subscribe(owner: String, fast: List<Int>, slow: List<Int> = emptyList()) {
    DisposableEffect(owner, fast, slow) {
        Obd.subscribe(owner, fast, slow)
        onDispose { Obd.unsubscribe(owner) }
    }
}

@Composable
fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(Settings.keepScreenOn) {
        view.keepScreenOn = Settings.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }
}

private val extraPids = listOf(Virtual.BATTERY, 0x04, 0x11, 0x2F, 0x0B, 0x10, 0x0E, 0x42, Virtual.FUEL_RATE)

/** Temperature + other tiles that the car actually supports (or a sensible default set while offline). */
@Composable
private fun visibleTiles(): List<Int> {
    val supported by Obd.supported.collectAsState()
    val link by Obd.link.collectAsState()
    return if (link.state != LinkState.Connected) listOf(0x05, 0x5C, 0x0F, 0x46, Virtual.BATTERY, 0x04)
    else (Pids.temperatures + extraPids).filter { it >= 0x1000 || supported.isEmpty() || it in supported }
}

@Composable
fun DashboardScreen(onBack: () -> Unit) {
    val values by Obd.values.collectAsState()
    val tiles = visibleTiles()
    val raw = tiles.filter { it != Virtual.FUEL_RATE } + listOf(0x0B, 0x0F)
    Subscribe("dash", fast = listOf(0x0D, 0x0C), slow = raw)
    KeepScreenOn()

    ScreenScaffold("Dashboard", onBack) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val landscape = maxWidth > maxHeight
            if (landscape) {
                Row(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    Row(Modifier.weight(1.1f).fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                        SpeedGauge(values[0x0D], Modifier.weight(1f))
                        RpmGauge(values[0x0C], Modifier.weight(1f))
                    }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 8.dp, bottom = 12.dp)) {
                        TileGrid(tiles, values, columns = 2)
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
                    SpeedGauge(values[0x0D], Modifier.fillMaxWidth().padding(horizontal = 24.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        RpmGauge(values[0x0C], Modifier.weight(1f))
                        val ct = values[0x05]
                        ArcGauge(
                            fraction = ct?.let { (it / 140).toFloat() },
                            display = Fmt.value(PidUnit.TEMP, ct), unit = Fmt.unit(PidUnit.TEMP), label = "Coolant",
                            color = tempColor(0x05, ct), modifier = Modifier.weight(1f),
                            scaleMax = 140f, majorStep = 20f, redFrom = 110f,
                        )
                    }
                    SectionLabel("Temperatures & sensors")
                    TileGrid(tiles, values, columns = 2)
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun SpeedGauge(v: Double?, modifier: Modifier) {
    val max = if (Settings.mph) 160f else 240f
    ArcGauge(
        fraction = v?.let { (Fmt.convert(PidUnit.SPEED, it) / max).toFloat() },
        display = Fmt.value(PidUnit.SPEED, v), unit = Fmt.unit(PidUnit.SPEED), label = "Speed",
        color = AppColors.cyan, modifier = modifier, scaleMax = max, majorStep = 20f,
    )
}

@Composable
private fun RpmGauge(v: Double?, modifier: Modifier) {
    val color = if ((v ?: 0.0) >= 6000) AppColors.red else AppColors.magenta
    ArcGauge(
        fraction = v?.let { (it / 8000).toFloat() },
        display = Fmt.value(PidUnit.RPM, v), unit = "rpm", label = "RPM",
        color = color, modifier = modifier, scaleMax = 8000f, majorStep = 1000f, redFrom = 6000f, scaleDivisor = 1000f,
    )
}

@Composable
fun TileGrid(pids: List<Int>, values: Map<Int, Double>, columns: Int, valueSize: Int = 30) {
    pids.chunked(columns).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { pid ->
                val p = Pids[pid]
                val v = values[pid]
                val color = when {
                    p?.unit == PidUnit.TEMP -> tempColor(pid, v)
                    pid == Virtual.BATTERY || pid == 0x42 -> if (v != null && (v < 11.8 || v > 15.0)) AppColors.red else AppColors.amber
                    pid == 0x2F -> if (v != null && v < 12) AppColors.red else AppColors.green
                    else -> AppColors.violet
                }
                ValueTile(
                    label = p?.short ?: pidLabel(pid),
                    value = p?.let { Fmt.value(it.unit, v) } ?: "--",
                    unit = p?.let { Fmt.unit(it.unit) } ?: "",
                    color = color,
                    fraction = if (p != null && v != null) ((v - p.min) / (p.max - p.min)).toFloat() else null,
                    modifier = Modifier.weight(1f),
                    valueSize = valueSize,
                )
            }
            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
fun TemperaturesScreen(onBack: () -> Unit) {
    val values by Obd.values.collectAsState()
    val supported by Obd.supported.collectAsState()
    val link by Obd.link.collectAsState()
    val temps = if (link.state == LinkState.Connected)
        Pids.temperatures.filter { supported.isEmpty() || it in supported } else listOf(0x05, 0x5C, 0x0F, 0x46)
    Subscribe("temps", fast = emptyList(), slow = temps)
    KeepScreenOn()
    ScreenScaffold("Temperatures", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
            if (link.state == LinkState.Connected && temps.isEmpty()) {
                Text("This car doesn't report any temperature PIDs.", color = AppColors.dim, modifier = Modifier.padding(16.dp))
            }
            TileGrid(temps, values, columns = 2, valueSize = 40)
            Text(
                "Showing every temperature sensor your car's ECU reports over standard OBD-II " +
                    "(${temps.size} found). Gearbox temperature is manufacturer-specific and isn't part of the standard.",
                color = AppColors.dim.copy(alpha = 0.7f), fontSize = 12.sp, modifier = Modifier.padding(vertical = 12.dp),
            )
        }
    }
}

@Composable
fun HudScreen(onBack: () -> Unit) {
    val values by Obd.values.collectAsState()
    var mirrored by rememberSaveable { mutableStateOf(true) }
    Subscribe("hud", fast = listOf(0x0D, 0x0C), slow = listOf(0x05))
    KeepScreenOn()
    val activity = LocalContext.current as? Activity
    DisposableEffect(Unit) {
        val old = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose { activity?.requestedOrientation = old ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
    val speed = values[0x0D]
    val rpm = values[0x0C]
    Box(Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
        BoxWithConstraints(
            Modifier.fillMaxSize().graphicsLayer { scaleX = if (mirrored) -1f else 1f }.clickable { mirrored = !mirrored },
            contentAlignment = Alignment.Center,
        ) {
            val big = (maxHeight.value * 0.4f).sp
            val barWidth = maxWidth * 0.6f
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                GlowText(Fmt.value(PidUnit.SPEED, speed), AppColors.green, big)
                Text(Fmt.unit(PidUnit.SPEED), color = AppColors.green.copy(alpha = 0.7f), fontSize = 22.sp, fontFamily = Sora)
                Spacer(Modifier.height(16.dp))
                val f = ((rpm ?: 0.0) / 7000).toFloat().coerceIn(0f, 1f)
                Box(Modifier.width(barWidth).height(10.dp).clip(RoundedCornerShape(5.dp)).background(Color.White.copy(alpha = 0.1f))) {
                    Box(
                        Modifier.fillMaxWidth(f).height(10.dp).clip(RoundedCornerShape(5.dp))
                            .background(if ((rpm ?: 0.0) > 5500) AppColors.red else AppColors.cyan)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GlowText(Fmt.value(PidUnit.RPM, rpm), AppColors.cyan, 34.sp)
                    Text("  rpm     ", color = AppColors.dim, fontSize = 16.sp)
                    GlowText(Fmt.value(PidUnit.TEMP, values[0x05]), tempColor(0x05, values[0x05]), 34.sp)
                    Text(" ${Fmt.unit(PidUnit.TEMP)}", color = AppColors.dim, fontSize = 16.sp)
                }
            }
        }
        Row(Modifier.align(Alignment.TopStart)) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = AppColors.dim)
            }
            IconButton(onClick = { mirrored = !mirrored }) { Icon(Icons.Rounded.Flip, "Mirror", tint = AppColors.dim) }
        }
        Text(
            if (mirrored) "Mirrored for windshield · tap to flip" else "Tap to mirror for windshield",
            color = AppColors.dim.copy(alpha = 0.5f), fontSize = 11.sp, fontWeight = FontWeight.Normal,
            modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
        )
    }
}

