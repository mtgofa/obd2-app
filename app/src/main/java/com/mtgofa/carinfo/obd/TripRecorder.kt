package com.mtgofa.carinfo.obd

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Something worth flagging that happened during a trip. */
data class TripEvent(val timeMs: Long, val severity: Severity, val text: String) {
    enum class Severity { Info, Warning, Fault }
}

/** Summary of one recorded trip, stored next to its CSV as JSON. */
data class TripSummary(
    val id: String,
    val vehicle: String,
    val startMs: Long,
    val endMs: Long,
    val samples: Int,
    val distanceKm: Double,
    val maxSpeed: Double?,
    val maxRpm: Double?,
    val maxCoolant: Double?,
    val avgSpeed: Double?,
    val minBattery: Double?,
    val events: List<TripEvent>,
    val codesAtStart: List<String>,
    val codesAtEnd: List<String>,
) {
    val durationMs get() = endMs - startMs
    val problems get() = events.count { it.severity != TripEvent.Severity.Info }
}

/**
 * Records a trip: one CSV row of readings per second, plus a JSON summary with the "check"
 * (new fault codes, overheating, charging problems, lost connection). Polls on its own
 * subscription, so it keeps recording whichever screen is open.
 */
object TripRecorder {
    /** Columns written to every CSV row, in this order. */
    val COLUMNS = listOf(
        0x0D, 0x0C, 0x05, 0x0F, 0x46, 0x5C, 0x04, 0x11, 0x0B, Virtual.BOOST,
        Virtual.BATTERY, Virtual.FUEL_RATE, 0x0E, 0x2F,
    )

    private const val OWNER = "trip"
    private const val DTC_CHECK_MS = 60_000L
    private const val SUMMARY_SAVE_MS = 30_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var dir: File
    private var job: Job? = null
    private var appContext: Context? = null

    var recording by mutableStateOf(false); private set
    var current by mutableStateOf<TripSummary?>(null); private set
    /** Bumped whenever the list of saved trips changes. */
    var version by mutableStateOf(0); private set

    fun init(context: Context) {
        dir = File(context.filesDir, "trips").apply { mkdirs() }
    }

    fun start(context: Context) {
        if (recording) return
        recording = true
        val app = context.applicationContext
        appContext = app
        startService(app)
        Obd.subscribe(OWNER, fast = listOf(0x0D, 0x0C, 0x0E, 0x04), slow = COLUMNS.filter { it < 0x1000 || it == Virtual.BATTERY } + 0x33)
        job = scope.launch { record() }
    }

    fun stop(context: Context) {
        if (!recording) return
        recording = false
        job?.cancel()
        Obd.unsubscribe(OWNER)
        context.applicationContext.stopService(Intent(context, TripService::class.java))
    }

    private fun startService(context: Context) {
        val i = Intent(context, TripService::class.java)
        runCatching {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }
    }

    private suspend fun record() {
        val start = System.currentTimeMillis()
        val id = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(start))
        val csv = File(dir, "$id.csv")
        val events = mutableListOf<TripEvent>()
        var samples = 0
        var distance = 0.0
        var speedSum = 0.0
        var maxSpeed: Double? = null
        var maxRpm: Double? = null
        var maxCoolant: Double? = null
        var minBattery: Double? = null
        var codesAtStart: List<String>? = null
        var knownCodes = emptySet<String>()
        var lastCodes = emptyList<String>()
        var lastDtcCheck = 0L
        var lastSave = start
        var lastNotify = 0L
        var lastLink: Link? = null
        var overheating = false
        var lowCharge = false
        var highCharge = false
        var milOn: Boolean? = null
        val knockAtStart = KnockMonitor.stats.value.events
        var knockReported = knockAtStart
        var lastKnockEvent = 0L

        fun event(sev: TripEvent.Severity, text: String) {
            events += TripEvent(System.currentTimeMillis(), sev, text)
        }

        fun summary(end: Long) = TripSummary(
            id, Obd.vehicle.value?.title ?: "Unknown vehicle", start, end, samples, distance,
            maxSpeed, maxRpm, maxCoolant, if (samples > 0) speedSum / samples else null, minBattery,
            events.toList(), codesAtStart ?: emptyList(), lastCodes,
        )

        event(TripEvent.Severity.Info, "Recording started")
        FileWriter(csv).use { w ->
            w.write("time_ms," + COLUMNS.joinToString(",") { Pids[it]?.short ?: "%02X".format(it) } + "\n")
            try {
                while (scope.isActive && recording) {
                    val now = System.currentTimeMillis()
                    val v = Obd.values.value
                    val link = Obd.link.value

                    // Connection changes.
                    if (lastLink != null && link.state != lastLink!!.state) {
                        when {
                            link.state == LinkState.Connected -> event(TripEvent.Severity.Info, "Connected to the car")
                            lastLink!!.state == LinkState.Connected -> event(TripEvent.Severity.Warning, "Lost connection to the adapter")
                        }
                    }
                    if (lastLink != null && link.car != lastLink!!.car && link.car == CarState.Lost) {
                        event(TripEvent.Severity.Warning, "Car stopped responding")
                    }
                    lastLink = link

                    if (link.state == LinkState.Connected) {
                        w.write(now.toString() + "," + COLUMNS.joinToString(",") { pid -> v[pid]?.let { "%.2f".format(Locale.US, it) } ?: "" } + "\n")
                        samples++
                        val speed = v[0x0D]
                        if (speed != null) {
                            distance += speed / 3600.0
                            speedSum += speed
                            maxSpeed = maxOf(maxSpeed ?: speed, speed)
                        }
                        v[0x0C]?.let { maxRpm = maxOf(maxRpm ?: it, it) }
                        v[0x05]?.let { ct ->
                            maxCoolant = maxOf(maxCoolant ?: ct, ct)
                            if (!overheating && ct >= 110) {
                                overheating = true
                                event(TripEvent.Severity.Fault, "Engine overheating: coolant reached ${ct.toInt()}°C")
                            } else if (overheating && ct < 104) overheating = false
                        }
                        val running = (v[0x0C] ?: 0.0) > 400
                        v[Virtual.BATTERY]?.let { b ->
                            if (running) {
                                minBattery = minOf(minBattery ?: b, b)
                                if (!lowCharge && b < 12.8) {
                                    lowCharge = true
                                    event(TripEvent.Severity.Warning, "Low charging voltage with the engine running: %.1f V".format(Locale.US, b))
                                } else if (lowCharge && b > 13.2) lowCharge = false
                                if (!highCharge && b > 15.2) {
                                    highCharge = true
                                    event(TripEvent.Severity.Warning, "Overcharging: battery at %.1f V".format(Locale.US, b))
                                } else if (highCharge && b < 14.8) highCharge = false
                            }
                        }

                        // Knock: report new timing pulls, at most once a minute so a bad tank doesn't flood the list.
                        val knocks = KnockMonitor.stats.value.events
                        if (knocks < knockReported) knockReported = knocks // monitor was reset
                        if (knocks > knockReported && now - lastKnockEvent >= 60_000) {
                            event(TripEvent.Severity.Warning, "Possible engine knock (${knocks - knockReported} timing pull${if (knocks - knockReported > 1) "s" else ""}) — check fuel quality")
                            knockReported = knocks
                            lastKnockEvent = now
                        }

                        // Fault codes: read at the start, then every minute; flag new ones.
                        if (now - lastDtcCheck >= DTC_CHECK_MS) {
                            lastDtcCheck = now
                            runCatching {
                                val (stored, pending, permanent) = Obd.readDtcs()
                                val all = (stored + pending + permanent).distinct()
                                if (codesAtStart == null) {
                                    codesAtStart = all
                                    knownCodes = all.toSet()
                                    if (all.isNotEmpty()) event(
                                        TripEvent.Severity.Info, "Codes already present at start: ${all.joinToString(", ")}",
                                    )
                                } else {
                                    for (code in all.filter { it !in knownCodes }) {
                                        val kind = if (code in stored) "stored" else if (code in pending) "pending" else "permanent"
                                        event(TripEvent.Severity.Fault, "New fault code $code ($kind): ${Dtc.describe(code)}")
                                    }
                                    knownCodes = knownCodes + all
                                }
                                lastCodes = all
                            }
                            runCatching { Obd.monitorStatus() }.getOrNull()?.let { (mil, _) ->
                                if (milOn == false && mil) event(TripEvent.Severity.Fault, "Check engine light turned ON")
                                milOn = mil
                            }
                        }
                    }

                    if (now - lastSave >= SUMMARY_SAVE_MS) {
                        lastSave = now
                        w.flush()
                        save(summary(now))
                    }
                    current = summary(now)
                    if (now - lastNotify >= 5000) {
                        lastNotify = now
                        val s = current!!
                        val km = "%.1f km".format(Locale.US, s.distanceKm)
                        val status = if (link.state == LinkState.Connected) "" else " · waiting for car"
                        val issues = if (s.problems > 0) " · ${s.problems} problem${if (s.problems > 1) "s" else ""}" else ""
                        if (recording) appContext?.let { TripService.update(it, "${clock(now - start)} · $km$issues$status") }
                    }
                    delay(1000 - (System.currentTimeMillis() - now) % 1000)
                }
            } finally {
                withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
                    events += TripEvent(System.currentTimeMillis(), TripEvent.Severity.Info, "Recording stopped")
                    w.flush()
                    save(summary(System.currentTimeMillis()))
                    current = null
                    version++
                    // A refresh racing with Stop could re-post the notification; make sure it's gone.
                    appContext?.let { TripService.cancel(it) }
                }
            }
        }
    }

    private fun clock(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    // ---------------------------------------------------------------- storage

    private fun save(s: TripSummary) {
        val o = JSONObject()
            .put("id", s.id).put("vehicle", s.vehicle).put("start", s.startMs).put("end", s.endMs)
            .put("samples", s.samples).put("distance", s.distanceKm)
            .put("maxSpeed", s.maxSpeed).put("maxRpm", s.maxRpm).put("maxCoolant", s.maxCoolant)
            .put("avgSpeed", s.avgSpeed).put("minBattery", s.minBattery)
            .put("codesAtStart", JSONArray(s.codesAtStart)).put("codesAtEnd", JSONArray(s.codesAtEnd))
            .put("events", JSONArray(s.events.map {
                JSONObject().put("t", it.timeMs).put("sev", it.severity.name).put("text", it.text)
            }))
        File(dir, "${s.id}.json").writeText(o.toString())
    }

    private fun JSONObject.optDoubleOrNull(k: String) = if (has(k) && !isNull(k)) optDouble(k) else null
    private fun JSONArray.strings() = List(length()) { getString(it) }

    private fun load(f: File): TripSummary? = runCatching {
        val o = JSONObject(f.readText())
        val ev = o.getJSONArray("events")
        TripSummary(
            o.getString("id"), o.optString("vehicle"), o.getLong("start"), o.getLong("end"), o.optInt("samples"),
            o.optDouble("distance"), o.optDoubleOrNull("maxSpeed"), o.optDoubleOrNull("maxRpm"),
            o.optDoubleOrNull("maxCoolant"), o.optDoubleOrNull("avgSpeed"), o.optDoubleOrNull("minBattery"),
            List(ev.length()) {
                val e = ev.getJSONObject(it)
                TripEvent(e.getLong("t"), TripEvent.Severity.valueOf(e.getString("sev")), e.getString("text"))
            },
            o.getJSONArray("codesAtStart").strings(), o.getJSONArray("codesAtEnd").strings(),
        )
    }.getOrNull()

    /** Saved trips, newest first (excluding the one being recorded). */
    fun list(): List<TripSummary> {
        val active = current?.id
        return dir.listFiles { f -> f.name.endsWith(".json") }.orEmpty()
            .mapNotNull { load(it) }.filter { it.id != active }.sortedByDescending { it.startMs }
    }

    fun get(id: String): TripSummary? = load(File(dir, "$id.json"))

    fun csvFile(id: String) = File(dir, "$id.csv")

    fun delete(id: String) {
        File(dir, "$id.json").delete()
        File(dir, "$id.csv").delete()
        version++
    }

    /** Readings for charts: one series per column, downsampled to at most [maxPoints]. */
    fun series(id: String, maxPoints: Int = 600): Map<Int, List<Pair<Long, Double>>> {
        val f = csvFile(id)
        if (!f.exists()) return emptyMap()
        val rows = f.readLines().drop(1).mapNotNull { line ->
            val parts = line.split(",")
            parts.firstOrNull()?.toLongOrNull()?.let { it to parts.drop(1) }
        }
        val step = maxOf(1, rows.size / maxPoints)
        return COLUMNS.withIndex().associate { (i, pid) ->
            pid to rows.filterIndexed { idx, _ -> idx % step == 0 }
                .mapNotNull { (t, cols) -> cols.getOrNull(i)?.toDoubleOrNull()?.let { t to it } }
        }.filterValues { it.size >= 2 }
    }
}
