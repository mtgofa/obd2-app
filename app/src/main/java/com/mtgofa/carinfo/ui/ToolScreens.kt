package com.mtgofa.carinfo.ui

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mtgofa.carinfo.Fmt
import com.mtgofa.carinfo.BuildConfig
import com.mtgofa.carinfo.Settings
import com.mtgofa.carinfo.Updater
import com.mtgofa.carinfo.obd.Category
import com.mtgofa.carinfo.obd.Dtc
import com.mtgofa.carinfo.obd.LinkState
import com.mtgofa.carinfo.obd.Obd
import com.mtgofa.carinfo.obd.PidUnit
import com.mtgofa.carinfo.obd.Pids
import com.mtgofa.carinfo.obd.Virtual
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun NotConnectedHint(go: () -> Unit) {
    val link by Obd.link.collectAsState()
    if (link.state == LinkState.Connected) return
    AppCard(Modifier.fillMaxWidth().padding(bottom = 12.dp), onClick = go, accent = AppColors.amber) {
        Text("Not connected", color = AppColors.amber, fontFamily = Sora, fontWeight = FontWeight.SemiBold)
        Text("Tap to connect to your OBD2 adapter (or try the demo car).", color = AppColors.dim, fontSize = 13.sp)
    }
}

// ---------------------------------------------------------------- Monitoring

@Composable
fun MonitorScreen(onBack: () -> Unit, connect: () -> Unit) {
    val values by Obd.values.collectAsState()
    val supported by Obd.supported.collectAsState()
    val pids = remember(supported) {
        listOf(Virtual.BATTERY) + Pids.all.filter { supported.isEmpty() || it.id in supported }.map { it.id }
    }
    Subscribe("monitor", fast = emptyList(), slow = pids)
    KeepScreenOn()
    ScreenScaffold("Live monitoring", onBack) {
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
            item { NotConnectedHint(connect) }
            item {
                Text(
                    "${pids.size} live values available on this car",
                    color = AppColors.dim, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            Category.entries.forEach { cat ->
                val inCat = pids.filter { Pids[it]?.category == cat }
                if (inCat.isNotEmpty()) {
                    item { SectionLabel(cat.name) }
                    items(inCat) { pid -> MonitorRow(pid, values[pid]) }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun MonitorRow(pid: Int, v: Double?) {
    val p = Pids[pid] ?: return
    val color = when (p.category) {
        Category.Temperature -> tempColor(pid, v)
        Category.Engine -> AppColors.cyan
        Category.Fuel -> AppColors.green
        Category.Air -> AppColors.blue
        Category.Electrical -> AppColors.amber
        Category.Status -> AppColors.violet
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(14.dp)).background(AppColors.card)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(p.name, color = AppColors.text, fontSize = 14.sp)
            Text(if (pid >= 0x1000) "Adapter" else "PID %02X".format(pid), color = AppColors.dim, fontSize = 11.sp)
        }
        GlowText(Fmt.value(p.unit, v), color, 22.sp)
        Text(" " + Fmt.unit(p.unit), color = AppColors.dim, fontSize = 12.sp, modifier = Modifier.width(52.dp))
    }
}

// ---------------------------------------------------------------- Diagnosis

private data class DtcScan(
    val mil: Boolean?, val count: Int?,
    val stored: List<String>, val pending: List<String>, val permanent: List<String>,
)

@Composable
fun DiagnosisScreen(onBack: () -> Unit, connect: () -> Unit) {
    val link by Obd.link.collectAsState()
    val scope = rememberCoroutineScope()
    var scanning by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<DtcScan?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }

    fun scan() {
        scanning = true; error = null
        scope.launch {
            try {
                val status = Obd.monitorStatus()
                val (s, p, perm) = Obd.readDtcs()
                result = DtcScan(status?.first, status?.second, s, p, perm)
            } catch (e: Exception) {
                error = e.message
            }
            scanning = false
        }
    }

    LaunchedEffect(link.state) { if (link.state == LinkState.Connected && result == null) scan() }

    ScreenScaffold("Vehicle diagnosis", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
            NotConnectedHint(connect)
            val r = result
            AppCard(Modifier.fillMaxWidth(), accent = if (r?.mil == true) AppColors.red else AppColors.green) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(14.dp).clip(CircleShape)
                            .background(if (r?.mil == true) AppColors.red else if (r == null) AppColors.dim else AppColors.green)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            when {
                                r == null -> "Not scanned yet"
                                r.mil == true -> "Check engine light is ON"
                                r.stored.isEmpty() && r.pending.isEmpty() -> "No faults found"
                                else -> "Faults found"
                            },
                            color = AppColors.text, fontFamily = Sora, fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
                        )
                        if (r != null) Text(
                            "${r.stored.size} stored · ${r.pending.size} pending · ${r.permanent.size} permanent",
                            color = AppColors.dim, fontSize = 12.sp,
                        )
                    }
                    if (scanning) CircularProgressIndicator(Modifier.size(22.dp), color = AppColors.cyan, strokeWidth = 2.dp)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AppButton("Scan codes", { scan() }, Modifier.weight(1f), enabled = link.state == LinkState.Connected && !scanning)
                AppButton(
                    "Clear codes", { confirmClear = true }, Modifier.weight(1f), color = AppColors.red,
                    enabled = link.state == LinkState.Connected && !scanning,
                )
            }
            error?.let { Text(it, color = AppColors.red, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp)) }
            info?.let { Text(it, color = AppColors.green, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp)) }
            if (r != null) {
                DtcSection("Stored codes", r.stored, AppColors.red)
                DtcSection("Pending codes", r.pending, AppColors.amber)
                DtcSection("Permanent codes", r.permanent, AppColors.magenta)
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear trouble codes?") },
            text = {
                Text(
                    "This turns off the check-engine light and erases stored codes, freeze-frame data and readiness " +
                        "monitors. Do it with the ignition ON and the engine OFF. If the fault is still there, the code will come back."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scanning = true
                    scope.launch {
                        info = try {
                            if (Obd.clearDtcs()) "Codes cleared." else "The ECU refused to clear codes (engine running?)."
                        } catch (e: Exception) {
                            e.message
                        }
                        delay(800)
                        scanning = false
                        scan()
                    }
                }) { Text("Clear", color = AppColors.red) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DtcSection(title: String, codes: List<String>, color: Color) {
    SectionLabel(title)
    if (codes.isEmpty()) {
        Text("None", color = AppColors.dim, fontSize = 13.sp, modifier = Modifier.padding(start = 12.dp))
        return
    }
    codes.forEach { code ->
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(AppColors.card)
                .border(1.dp, color.copy(alpha = 0.4f), RoundedCornerShape(14.dp)).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlowText(code, color, 20.sp, Modifier.width(92.dp))
            Column(Modifier.weight(1f)) {
                Text(Dtc.describe(code), color = AppColors.text, fontSize = 14.sp)
                Text(Dtc.system(code), color = AppColors.dim, fontSize = 11.sp)
            }
        }
    }
}

// ---------------------------------------------------------------- Fuel

@Composable
fun FuelScreen(onBack: () -> Unit, connect: () -> Unit) {
    val values by Obd.values.collectAsState()
    Subscribe("fuel", fast = listOf(0x0D, 0x10, 0x5E, 0x0C, 0x0B), slow = listOf(0x0F, 0x2F, 0x06, 0x07))
    KeepScreenOn()
    ScreenScaffold("Fuel economy", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
            NotConnectedHint(connect)
            val speed = values[0x0D] ?: 0.0
            val eco = values[Virtual.KM_PER_L]
            AppCard(Modifier.fillMaxWidth(), accent = AppColors.green) {
                Text("Instant economy", color = AppColors.dim, fontFamily = Sora, fontSize = 13.sp)
                Row(verticalAlignment = Alignment.Bottom) {
                    if (speed > 3) {
                        GlowText(Fmt.value(PidUnit.KML, eco), AppColors.green, 64.sp)
                        Text(" km/L", color = AppColors.dim, fontSize = 16.sp, modifier = Modifier.padding(bottom = 14.dp))
                        Spacer(Modifier.weight(1f))
                        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(bottom = 10.dp)) {
                            GlowText(Fmt.value(PidUnit.L100, values[Virtual.ECONOMY]), AppColors.cyan, 24.sp)
                            Text("L/100km", color = AppColors.dim, fontSize = 11.sp)
                        }
                    } else {
                        GlowText(Fmt.value(PidUnit.LPH, values[Virtual.FUEL_RATE]), AppColors.amber, 64.sp)
                        Text(" L/h (idle)", color = AppColors.dim, fontSize = 16.sp, modifier = Modifier.padding(bottom = 14.dp))
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            TileGrid(listOf(Virtual.FUEL_RATE, 0x2F, Virtual.TRIP_KM, Virtual.TRIP_FUEL, Virtual.TRIP_AVG, 0x07), values, columns = 2)
            AppButton("Reset trip", { Obd.resetTrip() }, Modifier.fillMaxWidth())
            Text(
                when {
                    values[0x5E] != null -> "Using the ECU's own fuel-rate reading."
                    values[0x10] != null -> "Calculated from the mass air flow sensor."
                    else -> "Estimated from manifold pressure and RPM using a ${Settings.displacement} L engine — set your engine size in Settings for accuracy."
                },
                color = AppColors.dim.copy(alpha = 0.7f), fontSize = 12.sp, modifier = Modifier.padding(vertical = 12.dp),
            )
        }
    }
}

// ---------------------------------------------------------------- Performance

private enum class Run(val label: String, val from: Double, val to: Double) {
    ZeroTo100("0–100 km/h", 0.0, 100.0), ZeroTo60("0–60 km/h", 0.0, 60.0), From80To120("80–120 km/h", 80.0, 120.0)
}

@Composable
fun PerformanceScreen(onBack: () -> Unit, connect: () -> Unit) {
    val values by Obd.values.collectAsState()
    var run by remember { mutableStateOf(Run.ZeroTo100) }
    var state by remember { mutableStateOf("Ready") }
    var startNs by remember { mutableLongStateOf(0L) }
    var elapsed by remember { mutableDoubleStateOf(0.0) }
    var best by remember { mutableStateOf(mapOf<Run, Double>()) }
    var armed by remember { mutableStateOf(false) }
    Subscribe("perf", fast = listOf(0x0D))
    KeepScreenOn()

    LaunchedEffect(run) {
        state = "Ready"; armed = false; startNs = 0L; elapsed = 0.0
        var prev: com.mtgofa.carinfo.obd.Sample? = null
        Obd.samples.collect { s ->
            if (s.pid != 0x0D) return@collect
            val p = prev
            prev = s
            // Linear interpolation between samples gives the crossing time to ~10 ms.
            fun cross(target: Double): Long? {
                if (p == null || p.value >= target || s.value < target) return null
                val k = (target - p.value) / (s.value - p.value)
                return p.timeNanos + ((s.timeNanos - p.timeNanos) * k).toLong()
            }
            when {
                !armed && startNs == 0L -> {
                    val ok = if (run.from == 0.0) s.value < 1 else s.value < run.from - 5
                    if (ok) { armed = true; state = if (run.from == 0.0) "Armed — go!" else "Armed — accelerate past ${run.from.toInt()}" }
                }
                armed && startNs == 0L -> {
                    val t = if (run.from == 0.0) (if (s.value >= 1) p?.timeNanos ?: s.timeNanos else null) else cross(run.from)
                    if (t != null) { startNs = t; state = "Running" }
                }
                startNs != 0L && armed -> {
                    elapsed = (s.timeNanos - startNs) / 1e9
                    cross(run.to)?.let { end ->
                        val secs = (end - startNs) / 1e9
                        elapsed = secs
                        armed = false
                        state = "Done"
                        if (secs < (best[run] ?: Double.MAX_VALUE)) best = best + (run to secs)
                    }
                    if (s.value < 1 && run.from == 0.0) { startNs = 0L; armed = true; state = "Armed — go!" }
                }
            }
        }
    }

    ScreenScaffold("Performance", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
            NotConnectedHint(connect)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Run.entries.forEach { r ->
                    AppButton(r.label, { run = r }, Modifier.weight(1f), color = if (r == run) AppColors.magenta else AppColors.dim)
                }
            }
            Spacer(Modifier.height(16.dp))
            AppCard(Modifier.fillMaxWidth(), accent = AppColors.magenta) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(state, color = AppColors.dim, fontFamily = Sora)
                    GlowText(String.format(Locale.US, "%.2f", elapsed), AppColors.magenta, 80.sp)
                    Text("seconds", color = AppColors.dim, fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        GlowText(Fmt.value(PidUnit.SPEED, values[0x0D]), AppColors.cyan, 36.sp)
                        Text(" " + Fmt.unit(PidUnit.SPEED), color = AppColors.dim, fontSize = 13.sp)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            AppButton("Reset", { state = "Ready"; armed = false; startNs = 0L; elapsed = 0.0 }, Modifier.fillMaxWidth())
            SectionLabel("Best times")
            Run.entries.forEach { r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(r.label, color = AppColors.text, modifier = Modifier.weight(1f))
                    Text(best[r]?.let { String.format(Locale.US, "%.2f s", it) } ?: "--", color = AppColors.green, fontFamily = Digits)
                }
            }
            Text(
                "Stop the car to arm the timer, then accelerate. Only measure on a closed road or track.",
                color = AppColors.dim.copy(alpha = 0.7f), fontSize = 12.sp, modifier = Modifier.padding(vertical = 12.dp),
            )
        }
    }
}

// ---------------------------------------------------------------- Vehicle info

@Composable
fun InfoScreen(onBack: () -> Unit, connect: () -> Unit) {
    val vehicle by Obd.vehicle.collectAsState()
    val supported by Obd.supported.collectAsState()
    val values by Obd.values.collectAsState()
    val link by Obd.link.collectAsState()
    Subscribe("info", fast = emptyList(), slow = listOf(Virtual.BATTERY, 0xA6, 0x31, 0x21))
    ScreenScaffold("Vehicle info", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
            NotConnectedHint(connect)
            val v = vehicle
            AppCard(Modifier.fillMaxWidth(), accent = AppColors.cyan) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BrandBadge(v?.make)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(v?.title ?: "No vehicle", color = AppColors.text, fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text(
                            if (v?.vin != null) "Detected automatically from VIN" else "VIN not reported by this car",
                            color = AppColors.dim, fontSize = 12.sp,
                        )
                    }
                }
            }
            SectionLabel("Identification")
            InfoRow("VIN", v?.vin)
            InfoRow("Manufacturer", v?.make)
            InfoRow("Model year", v?.year?.toString())
            InfoRow("Built in", v?.country)
            InfoRow("Fuel type", v?.fuelType)
            InfoRow("Odometer (ECU)", values[0xA6]?.let { Fmt.value(PidUnit.KM, it) + " " + Fmt.unit(PidUnit.KM) })
            SectionLabel("ECU")
            InfoRow("ECU name", v?.ecuName)
            InfoRow("Calibration ID", v?.calibrationId)
            InfoRow("OBD standard", v?.obdStandard)
            InfoRow("Protocol", v?.protocol)
            InfoRow("Supported PIDs", if (link.state == LinkState.Connected) supported.size.toString() else null)
            InfoRow("Since codes cleared", values[0x31]?.let { Fmt.value(PidUnit.KM, it) + " " + Fmt.unit(PidUnit.KM) })
            SectionLabel("Adapter")
            InfoRow("Adapter", v?.elmVersion)
            InfoRow("Connection", link.via.ifBlank { null })
            InfoRow("Battery", values[Virtual.BATTERY]?.let { "%.1f V".format(Locale.US, it) })
            Spacer(Modifier.height(12.dp))
            AppButton("Detect again", { Obd.redetectVehicle() }, Modifier.fillMaxWidth(), enabled = link.state == LinkState.Connected)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp, horizontal = 4.dp)) {
        Text(label, color = AppColors.dim, fontSize = 14.sp, modifier = Modifier.weight(0.42f))
        Text(value ?: "—", color = AppColors.text, fontSize = 14.sp, fontFamily = if (label == "VIN") Digits else Body, modifier = Modifier.weight(0.58f))
    }
}

// ---------------------------------------------------------------- Terminal

@Composable
fun TerminalScreen(onBack: () -> Unit) {
    val lines by Obd.traffic.collectAsState()
    val scope = rememberCoroutineScope()
    var cmd by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.scrollToItem(lines.size - 1) }

    fun send(c: String) {
        if (c.isBlank()) return
        scope.launch { runCatching { Obd.raw(c.trim().uppercase()) } }
        cmd = ""
    }

    ScreenScaffold("ELM327 terminal", onBack) {
        Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 14.dp)) {
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0xFF15172A)).padding(10.dp),
                state = listState,
            ) {
                items(lines) { l ->
                    Text(l, color = if (l.startsWith(">")) AppColors.cyan else AppColors.green, fontFamily = Digits, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("ATI", "ATRV", "ATDP", "0100", "0902", "03").forEach { q ->
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(AppColors.card).clickable { send(q) }.padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(q, color = AppColors.text, fontSize = 12.sp, fontFamily = Digits) }
                }
            }
            Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    cmd, { cmd = it }, Modifier.weight(1f), singleLine = true,
                    placeholder = { Text("AT command or PID, e.g. 010C") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send(cmd) }),
                    colors = fieldColors(),
                )
                Spacer(Modifier.width(8.dp))
                AppButton("Send", { send(cmd) })
            }
            Text(
                "Live polling shares this link, so you'll also see dashboard traffic here.",
                color = AppColors.dim.copy(alpha = 0.6f), fontSize = 11.sp, modifier = Modifier.padding(bottom = 8.dp),
            )
        }
    }
}

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = AppColors.cyan, unfocusedBorderColor = AppColors.cardBorder,
    focusedTextColor = AppColors.text, unfocusedTextColor = AppColors.text, cursorColor = AppColors.cyan,
    focusedLabelColor = AppColors.cyan, unfocusedLabelColor = AppColors.dim, focusedPlaceholderColor = AppColors.dim, unfocusedPlaceholderColor = AppColors.dim,
)

// ---------------------------------------------------------------- Settings

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var carName by remember { mutableStateOf(Settings.carName) }
    var disp by remember { mutableStateOf(Settings.displacement.toString()) }
    ScreenScaffold("Settings", onBack) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
            SectionLabel("Units")
            Toggle("Miles (mph)", "Show speed and distance in miles", Settings.mph, Settings::updateMph)
            Toggle("Fahrenheit (°F)", "Show temperatures in °F", Settings.fahrenheit, Settings::updateFahrenheit)
            SectionLabel("Behaviour")
            Toggle("Keep screen on", "While dashboard, HUD or monitoring is open", Settings.keepScreenOn, Settings::updateKeepScreenOn)
            Toggle("Auto-connect", "Reconnect to the last adapter when the app opens", Settings.autoConnect, Settings::updateAutoConnect)
            SectionLabel("Vehicle")
            OutlinedTextField(
                carName, { carName = it; Settings.updateCarName(it) }, Modifier.fillMaxWidth(),
                label = { Text("Car name (optional)") }, placeholder = { Text("Leave empty to use the detected make/year") },
                singleLine = true, colors = fieldColors(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                disp, { s -> disp = s; s.toFloatOrNull()?.takeIf { it in 0.6f..8f }?.let(Settings::updateDisplacement) },
                Modifier.fillMaxWidth(), label = { Text("Engine size (litres)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), colors = fieldColors(),
                supportingText = { Text("Used to estimate fuel use on cars without a MAF sensor") },
            )
            SectionLabel("Updates")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Version ${BuildConfig.VERSION_NAME}", color = AppColors.text, fontSize = 15.sp)
                    Text(
                        Updater.message.ifBlank { "Build ${BuildConfig.VERSION_CODE}" },
                        color = AppColors.dim, fontSize = 12.sp,
                    )
                }
                if (Updater.release != null) AppButton("Install", { Updater.install(ctx) }, color = AppColors.green, enabled = !Updater.downloading)
                else AppButton("Check", { Updater.check() }, enabled = !Updater.checking)
            }
            SectionLabel("About")
            Text(
                "Car Info — every feature unlocked, no account, no ads, nothing leaves your phone.\n" +
                    "Works with ELM327 adapters over Bluetooth Classic, Bluetooth LE and Wi-Fi.",
                color = AppColors.dim, fontSize = 13.sp,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Toggle(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(AppColors.card)
            .clickable { onChange(!checked) }.padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = AppColors.text, fontSize = 15.sp)
            Text(sub, color = AppColors.dim, fontSize = 12.sp)
        }
        Switch(
            checked, onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = AppColors.cyan.copy(alpha = 0.5f), checkedThumbColor = AppColors.cyan),
        )
    }
}
