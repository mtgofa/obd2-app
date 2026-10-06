package com.mtgofa.carinfo.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.mtgofa.carinfo.Fmt
import com.mtgofa.carinfo.obd.LinkState
import com.mtgofa.carinfo.obd.Obd
import com.mtgofa.carinfo.obd.PidUnit
import com.mtgofa.carinfo.obd.Pids
import com.mtgofa.carinfo.obd.TripEvent
import com.mtgofa.carinfo.obd.TripRecorder
import com.mtgofa.carinfo.obd.TripSummary
import com.mtgofa.carinfo.obd.Virtual
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Which trip the detail screen shows. */
object TripNav {
    var selected by mutableStateOf<String?>(null)
}

private fun duration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

private val dayFmt = SimpleDateFormat("EEE d MMM · HH:mm", Locale.US)
private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

private fun distance(km: Double) = Fmt.value(PidUnit.KM, km) + " " + Fmt.unit(PidUnit.KM)

@Composable
fun TripsScreen(onBack: () -> Unit, open: (String) -> Unit) {
    val ctx = LocalContext.current
    val link by Obd.link.collectAsState()
    val version = TripRecorder.version
    val trips by produceState(emptyList<TripSummary>(), version) {
        value = withContext(Dispatchers.IO) { TripRecorder.list() }
    }
    val notifyPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        TripRecorder.start(ctx)
    }

    ScreenScaffold("Trip record", onBack) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
            item {
                val cur = TripRecorder.current
                AppCard(Modifier.fillMaxWidth(), accent = if (TripRecorder.recording) AppColors.red else null) {
                    if (TripRecorder.recording) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(12.dp).clip(CircleShape).background(AppColors.red))
                            Spacer(Modifier.width(8.dp))
                            Text("Recording", color = AppColors.red, fontFamily = Sora, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                            Spacer(Modifier.weight(1f))
                            GlowText(duration(cur?.durationMs ?: 0), AppColors.text, 22.sp)
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Stat("Distance", cur?.let { distance(it.distanceKm) } ?: "--", Modifier.weight(1f))
                            Stat("Readings", (cur?.samples ?: 0).toString(), Modifier.weight(1f))
                            Stat("Problems", (cur?.problems ?: 0).toString(), Modifier.weight(1f),
                                if ((cur?.problems ?: 0) > 0) AppColors.red else AppColors.green)
                        }
                        cur?.events?.lastOrNull { it.severity != TripEvent.Severity.Info }?.let {
                            Text("Last: ${it.text}", color = AppColors.red, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
                        }
                        Spacer(Modifier.height(12.dp))
                        AppButton("Stop recording", { TripRecorder.stop(ctx) }, Modifier.fillMaxWidth(), color = AppColors.red)
                    } else {
                        Text("Record a trip", color = AppColors.text, fontFamily = Sora, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                        Text(
                            "Saves every reading once a second and checks for new fault codes, overheating and " +
                                "charging problems along the way. Keeps recording with the screen off.",
                            color = AppColors.dim, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp),
                        )
                        AppButton("Start recording", {
                            if (Build.VERSION.SDK_INT >= 33) notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            else TripRecorder.start(ctx)
                        }, Modifier.fillMaxWidth(), color = AppColors.green)
                    }
                }
            }
            item { SectionLabel("Recorded trips") }
            if (trips.isEmpty()) item {
                Text("No trips recorded yet.", color = AppColors.dim, modifier = Modifier.padding(8.dp))
            }
            items(trips, key = { it.id }) { t -> TripRow(t) { open(t.id) } }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier, color: Color = AppColors.text) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(AppColors.inset).padding(10.dp)) {
        Text(label, color = AppColors.dim, fontSize = 11.sp)
        Text(value, color = color, fontFamily = Digits, fontSize = 17.sp, maxLines = 1)
    }
}

@Composable
private fun TripRow(t: TripSummary, onClick: () -> Unit) {
    val ok = t.problems == 0
    val noData = t.samples == 0
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(AppColors.card)
            .border(1.dp, AppColors.cardBorder, RoundedCornerShape(16.dp)).clickable { onClick() }.padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(dayFmt.format(Date(t.startMs)), color = AppColors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(
                listOfNotNull(
                    duration(t.durationMs), distance(t.distanceKm),
                    t.maxSpeed?.let { "max " + Fmt.value(PidUnit.SPEED, it) + " " + Fmt.unit(PidUnit.SPEED) },
                ).joinToString(" · "),
                color = AppColors.dim, fontSize = 12.sp,
            )
        }
        val color = if (!ok) AppColors.red else if (noData) AppColors.dim else AppColors.green
        Text(
            if (!ok) "${t.problems} problem" + (if (t.problems > 1) "s" else "") else if (noData) "No data" else "✓ OK",
            color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

// ---------------------------------------------------------------- detail

private val CHART_ORDER = listOf(0x0D, 0x0C, 0x05, 0x04, 0x0B, Virtual.BOOST, Virtual.BATTERY, 0x0F, 0x11, Virtual.FUEL_RATE, 0x5C, 0x46)

@Composable
fun TripDetailScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val id = TripNav.selected
    if (id == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val trip by produceState<TripSummary?>(null, id) { value = withContext(Dispatchers.IO) { TripRecorder.get(id) } }
    val series by produceState(emptyMap<Int, List<Pair<Long, Double>>>(), id) {
        value = withContext(Dispatchers.IO) { TripRecorder.series(id) }
    }
    var confirmDelete by remember { mutableStateOf(false) }

    ScreenScaffold(trip?.let { dayFmt.format(Date(it.startMs)) } ?: "Trip", onBack, actions = {
        IconButton(onClick = {
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", TripRecorder.csvFile(id))
            val send = Intent(Intent.ACTION_SEND).setType("text/csv").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ctx.startActivity(Intent.createChooser(send, "Share trip log"))
        }) { Icon(Icons.Rounded.Share, "Share", tint = AppColors.text) }
        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Rounded.Delete, "Delete", tint = AppColors.text) }
    }) {
        val t = trip ?: return@ScreenScaffold
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
            Text(t.vehicle, color = AppColors.dim, fontSize = 13.sp)

            // The check: what went wrong during the trip.
            SectionLabel("Trip check")
            val problems = t.events.filter { it.severity != TripEvent.Severity.Info }
            AppCard(Modifier.fillMaxWidth(), accent = if (problems.isNotEmpty()) AppColors.red else if (t.samples == 0) null else AppColors.green) {
                if (problems.isEmpty() && t.samples == 0) {
                    // Never claim "no problems" for a trip where nothing was actually read.
                    Text("No readings in this trip", color = AppColors.text, fontFamily = Sora, fontWeight = FontWeight.SemiBold)
                    Text("The car wasn't connected while recording, so nothing could be checked.", color = AppColors.dim, fontSize = 13.sp)
                } else if (problems.isEmpty()) {
                    Text("✓ No problems during this trip", color = AppColors.green, fontFamily = Sora, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (t.codesAtEnd.isEmpty()) "No fault codes, no overheating, charging normal."
                        else "No new faults. Codes already stored before the trip: ${t.codesAtEnd.joinToString(", ")}",
                        color = AppColors.dim, fontSize = 13.sp,
                    )
                } else {
                    problems.forEach { e ->
                        Row(Modifier.padding(vertical = 4.dp)) {
                            Text(timeFmt.format(Date(e.timeMs)), color = AppColors.dim, fontFamily = Digits, fontSize = 12.sp, modifier = Modifier.width(70.dp))
                            Text(e.text, color = if (e.severity == TripEvent.Severity.Fault) AppColors.red else AppColors.amber, fontSize = 14.sp)
                        }
                    }
                }
            }

            SectionLabel("Summary")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat("Duration", duration(t.durationMs), Modifier.weight(1f))
                Stat("Distance", distance(t.distanceKm), Modifier.weight(1f))
                Stat("Avg speed", t.avgSpeed?.let { Fmt.value(PidUnit.SPEED, it) } ?: "--", Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat("Max speed", t.maxSpeed?.let { Fmt.value(PidUnit.SPEED, it) } ?: "--", Modifier.weight(1f))
                Stat("Max RPM", t.maxRpm?.let { Fmt.value(PidUnit.RPM, it) } ?: "--", Modifier.weight(1f))
                Stat("Max coolant", t.maxCoolant?.let { Fmt.value(PidUnit.TEMP, it) + Fmt.unit(PidUnit.TEMP) } ?: "--", Modifier.weight(1f))
            }

            SectionLabel("Readings")
            if (series.isEmpty()) Text("No readings in this trip.", color = AppColors.dim, fontSize = 13.sp)
            CHART_ORDER.filter { it in series }.forEach { pid -> LineChart(pid, series.getValue(pid)) }

            SectionLabel("Timeline")
            t.events.forEach { e ->
                Row(Modifier.padding(vertical = 3.dp)) {
                    Text(timeFmt.format(Date(e.timeMs)), color = AppColors.dim, fontFamily = Digits, fontSize = 12.sp, modifier = Modifier.width(70.dp))
                    Text(
                        e.text, fontSize = 13.sp,
                        color = when (e.severity) {
                            TripEvent.Severity.Fault -> AppColors.red
                            TripEvent.Severity.Warning -> AppColors.amber
                            TripEvent.Severity.Info -> AppColors.text
                        },
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete this trip?") },
        text = { Text("The readings and the trip check will be removed from this phone.") },
        confirmButton = {
            TextButton(onClick = { confirmDelete = false; TripRecorder.delete(id); onBack() }) { Text("Delete", color = AppColors.red) }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}

@Composable
private fun LineChart(pid: Int, points: List<Pair<Long, Double>>) {
    val p = Pids[pid] ?: return
    val values = points.map { Fmt.convert(p.unit, it.second) }
    val lo = values.min()
    val hi = values.max()
    val color = when (p.unit) {
        PidUnit.SPEED -> AppColors.cyan
        PidUnit.RPM -> AppColors.magenta
        PidUnit.TEMP -> AppColors.red
        PidUnit.VOLT -> AppColors.amber
        PidUnit.KPA, PidUnit.BAR -> AppColors.blue
        else -> AppColors.violet
    }
    val grid = AppColors.cardBorder
    AppCard(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(p.name, color = AppColors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(
                "min ${Fmt.value(p.unit, points.minOf { it.second })} · max ${Fmt.value(p.unit, points.maxOf { it.second })} ${Fmt.unit(p.unit)}",
                color = AppColors.dim, fontSize = 11.sp,
            )
        }
        Spacer(Modifier.height(8.dp))
        Canvas(Modifier.fillMaxWidth().height(110.dp)) {
            val t0 = points.first().first
            val span = (points.last().first - t0).coerceAtLeast(1)
            val range = (hi - lo).takeIf { it > 1e-6 } ?: 1.0
            for (i in 0..2) {
                val y = size.height * i / 2
                drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            }
            val path = Path()
            points.forEachIndexed { i, (t, _) ->
                val x = size.width * (t - t0) / span
                val y = size.height * (1 - ((values[i] - lo) / range)).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
        }
    }
}
