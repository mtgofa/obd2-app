package com.mtgofa.carinfo.obd

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import com.mtgofa.carinfo.Settings
import com.mtgofa.carinfo.ui.hasBtPermissions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class LinkState { Disconnected, Connecting, Initializing, Connected, Error }

/** The car side of the link, separate from the adapter: the ELM327 can be up while the ECU is off. */
enum class CarState { None, Waiting, Online, Lost }

data class Link(
    val state: LinkState,
    val message: String = "",
    val via: String = "",
    val car: CarState = CarState.None,
    val protocol: String = "",
) {
    val adapterUp: Boolean get() = state == LinkState.Initializing || state == LinkState.Connected
}

data class Vehicle(
    val vin: String? = null,
    val make: String? = null,
    val model: String? = null,
    val year: Int? = null,
    val country: String? = null,
    val protocol: String = "",
    val elmVersion: String = "",
    val ecuName: String? = null,
    val calibrationId: String? = null,
    val obdStandard: String? = null,
    val fuelType: String? = null,
) {
    val title: String
        get() = Settings.carName.ifBlank {
            when {
                make != null -> listOfNotNull(make, model, year?.toString()).joinToString(" ")
                year != null -> "Car · $year"
                else -> "Unknown vehicle"
            }
        }
}

data class Sample(val pid: Int, val value: Double, val timeNanos: Long)

/** Where to connect: a Bluetooth device or a Wi-Fi adapter. */
sealed class Target {
    data class Bt(val address: String, val name: String) : Target()
    data class Wifi(val host: String, val port: Int) : Target()

    fun encode(): String = when (this) {
        is Bt -> "bt|$address|$name"
        is Wifi -> "wifi|$host|$port"
    }

    companion object {
        fun decode(s: String): Target? {
            val p = s.split("|")
            return when (p.firstOrNull()) {
                "bt" -> if (p.size >= 3) Bt(p[1], p[2]) else null
                "wifi" -> if (p.size >= 3) Wifi(p[1], p[2].toIntOrNull() ?: 35000) else null
                else -> null
            }
        }
    }
}

object Obd {
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _link = MutableStateFlow(Link(LinkState.Disconnected))
    val link = _link.asStateFlow()
    private val _vehicle = MutableStateFlow<Vehicle?>(null)
    val vehicle = _vehicle.asStateFlow()
    private val _supported = MutableStateFlow<Set<Int>>(emptySet())
    val supported = _supported.asStateFlow()
    private val _values = MutableStateFlow<Map<Int, Double>>(emptyMap())
    val values = _values.asStateFlow()
    private val _samples = MutableSharedFlow<Sample>(extraBufferCapacity = 256)
    val samples = _samples.asSharedFlow()
    private val _traffic = MutableStateFlow<List<String>>(emptyList())
    val traffic = _traffic.asStateFlow()

    private var elm: Elm327? = null
    private var connectJob: Job? = null
    private var pollJob: Job? = null

    /** Screens declare which PIDs they need: "fast" ones every cycle, "slow" ones round-robin. */
    private val subscriptions = mutableMapOf<String, Pair<List<Int>, List<Int>>>()
    private val dead = mutableSetOf<Int>()
    private val misses = mutableMapOf<Int, Int>()
    /** Last successful read per PID; PIDs that answered once are never dropped from polling. */
    private val lastOk = mutableMapOf<Int, Long>()
    private var lastCarAnswer = 0L
    private const val STALE_MS = 5000L

    private var tripKm = 0.0
    private var tripFuel = 0.0
    private var lastTick = 0L

    val isConnected get() = _link.value.state == LinkState.Connected

    fun init(context: Context) {
        app = context.applicationContext
    }

    /** Reconnect to the last adapter, from the phone UI or the car screen, whichever starts first. */
    fun autoConnect(context: Context) {
        if (!Settings.autoConnect || _link.value.state != LinkState.Disconnected) return
        val target = Target.decode(Settings.lastTarget) ?: return
        if (target is Target.Bt && !hasBtPermissions(context)) return
        connect(target)
    }

    @Synchronized
    fun subscribe(owner: String, fast: List<Int>, slow: List<Int> = emptyList()) {
        subscriptions[owner] = fast to slow
    }

    @Synchronized
    fun unsubscribe(owner: String) {
        subscriptions.remove(owner)
    }

    fun isSupported(pid: Int): Boolean =
        pid >= 0x1000 || _supported.value.isEmpty() || pid in _supported.value

    /** The adapter the user asked for; cleared by [disconnect]. Used to reconnect after a drop. */
    private var wanted: Target? = null

    /** [retry] marks an automatic reconnect after a dropped link: on failure it tries again. */
    fun connect(target: Target, retry: Boolean = false) {
        wanted = target
        val previous = connectJob
        connectJob = scope.launch {
            previous?.cancelAndJoin()
            teardown()
            _link.value = Link(LinkState.Connecting, "Connecting…")
            var transport: Transport? = null
            try {
                transport = openTransport(target)
                _link.value = Link(LinkState.Initializing, "Setting up the adapter…", transport.label)
                val e = Elm327(transport)
                e.allowFast = Settings.fastPolling
                e.onTraffic = { cmd, reply ->
                    Log.d("CarInfo", "> $cmd | ${reply.replace("\n", " / ")}")
                    _traffic.update { (it + "> $cmd" + reply.lines().map { l -> "  $l" }).takeLast(400) }
                }
                e.setupAdapter()
                elm = e
                _link.value = Link(LinkState.Initializing, "Talking to the car…", transport.label)
                // Keep the adapter link and retry until the ignition is switched on.
                while (!e.probeCar()) {
                    _link.value = Link(
                        LinkState.Initializing, "Car not responding — turn the ignition ON", transport.label, CarState.Waiting,
                    )
                    delay(3000)
                    if (!transport.alive) throw ObdException("Connection to the adapter was lost")
                }
                val listed = runCatching { e.supportedPids() }.getOrDefault(emptySet())
                _supported.value = listed + probeUnlisted(e, listed)
                _vehicle.value = detectVehicle(e)
                Settings.updateLastTarget(target.encode())
                resetTrip()
                lastCarAnswer = System.currentTimeMillis()
                _link.value = Link(LinkState.Connected, "Connected", transport.label, CarState.Online, e.protocolName)
                startPolling(e)
            } catch (ex: CancellationException) {
                transport?.close()
                throw ex
            } catch (ex: Exception) {
                transport?.close()
                elm = null
                if (retry && wanted == target) {
                    _link.value = Link(LinkState.Connecting, "Reconnecting…")
                    delay(4000)
                    if (wanted == target) scope.launch { connect(target, retry = true) }
                } else {
                    _link.value = Link(LinkState.Error, ex.message ?: "Connection failed")
                }
            }
        }
    }

    fun disconnect() {
        wanted = null
        scope.launch {
            connectJob?.cancelAndJoin()
            teardown()
            _link.value = Link(LinkState.Disconnected)
        }
    }

    private suspend fun teardown() {
        pollJob?.cancelAndJoin()
        pollJob = null
        elm?.transport?.close()
        elm = null
        dead.clear()
        misses.clear()
        lastOk.clear()
        _values.value = emptyMap()
    }

    @SuppressLint("MissingPermission")
    private suspend fun openTransport(target: Target): Transport = when (target) {
        is Target.Wifi -> WifiTransport(app, target.host, target.port).also { it.open() }
        is Target.Bt -> {
            val adapter = app.getSystemService(BluetoothManager::class.java)?.adapter
                ?: throw ObdException("This phone has no Bluetooth")
            if (!adapter.isEnabled) throw ObdException("Bluetooth is off")
            val device = adapter.getRemoteDevice(target.address)
            when (device.type) {
                BluetoothDevice.DEVICE_TYPE_LE -> BleTransport(app, device).also { it.open() }
                BluetoothDevice.DEVICE_TYPE_CLASSIC -> ClassicBtTransport(device, adapter).also { it.open() }
                else -> {
                    // Dual-mode or unknown: most ELM327 clones are Classic SPP; fall back to BLE.
                    try {
                        ClassicBtTransport(device, adapter).also { it.open() }
                    } catch (e: Exception) {
                        _link.value = Link(LinkState.Connecting, "Trying Bluetooth LE…")
                        BleTransport(app, device).also { it.open() }
                    }
                }
            }
        }
    }

    /**
     * Some ECUs answer PIDs their support bitmap leaves out. For the ones we'd otherwise have to
     * estimate (barometric pressure feeds the boost reading), ask once directly before giving up.
     */
    private suspend fun probeUnlisted(e: Elm327, listed: Set<Int>): Set<Int> {
        if (listed.isEmpty()) return emptySet()
        return listOf(0x33).filter { it !in listed && runCatching { e.readPidRetry(it, 2) }.getOrNull() != null }.toSet()
    }

    private suspend fun detectVehicle(e: Elm327): Vehicle {
        val vin = runCatching { e.readVin() }.getOrNull()
        val decoded = vin?.let { Vin.decode(it) }
        val standard = if (isSupported(0x1C)) runCatching { e.readPidRetry(0x1C)?.firstOrNull() }.getOrNull() else null
        val fuel = if (isSupported(0x51)) runCatching { e.readPidRetry(0x51)?.firstOrNull() }.getOrNull() else null
        return Vehicle(
            vin = vin,
            make = decoded?.make,
            model = decoded?.model,
            year = decoded?.year,
            country = decoded?.country,
            protocol = e.protocolName,
            elmVersion = e.version,
            ecuName = runCatching { e.readInfoText("0A") }.getOrNull(),
            calibrationId = runCatching { e.readInfoText("04") }.getOrNull(),
            obdStandard = standard?.let { Pids.obdStandards[it] },
            fuelType = fuel?.let { Pids.fuelTypes[it] },
        )
    }

    fun redetectVehicle() {
        val e = elm ?: return
        scope.launch { _vehicle.value = detectVehicle(e) }
    }

    private fun startPolling(e: Elm327) {
        pollJob = scope.launch {
            var rr = 0
            var cycle = 0
            while (isActive) {
                if (!e.transport.alive || e.silentCount >= 4) {
                    e.transport.close()
                    elm = null
                    _link.value = Link(LinkState.Connecting, "Connection lost — reconnecting…")
                    // Bluetooth links to clone adapters drop now and then; bring it back by itself.
                    wanted?.let { t -> scope.launch { delay(1500); if (wanted == t) connect(t, retry = true) } }
                    break
                }
                val (fast, slow) = synchronized(this@Obd) {
                    val f = subscriptions.values.flatMap { it.first }.distinct()
                    val s = subscriptions.values.flatMap { it.second }.distinct() - f.toSet()
                    f.filter { isSupported(it) && it !in dead } to s.filter { isSupported(it) && it !in dead }
                }
                if (fast.isEmpty() && slow.isEmpty()) {
                    delay(250)
                    continue
                }
                for (p in fast) poll(e, p)
                if (slow.isNotEmpty()) {
                    // Interleave more slow PIDs when there are few fast ones so lists stay fresh.
                    val n = if (fast.size <= 1) 1 else if (fast.size <= 2) 2 else 3
                    repeat(minOf(n, slow.size)) { poll(e, slow[rr++ % slow.size]) }
                }
                computeDerived()
                if (++cycle % 20 == 0) delay(5)
            }
        }
    }

    private suspend fun poll(e: Elm327, pid: Int) {
        try {
            val v: Double? = when {
                pid == Virtual.BATTERY -> e.batteryVoltage()
                pid >= 0x1000 -> return
                else -> e.readPid(pid)?.let { Pids[pid]?.decode?.invoke(it) }
            }
            val now = System.currentTimeMillis()
            if (v == null) {
                if (pid != Virtual.BATTERY) markCar(false)
                val m = (misses[pid] ?: 0) + 1
                misses[pid] = m
                // Single dropped replies are normal on clone adapters: keep the last value, and only
                // blank it once it has been stale for a while so a frozen number never looks live.
                val last = lastOk[pid]
                if (last != null && now - last > STALE_MS) _values.update { it - pid }
                if (last == null && m >= 3 && _supported.value.isNotEmpty()) dead += pid
                return
            }
            misses[pid] = 0
            lastOk[pid] = now
            if (pid != Virtual.BATTERY) markCar(true)
            _values.update { it + (pid to v) }
            _samples.tryEmit(Sample(pid, v, System.nanoTime()))
        } catch (_: Exception) {
            // A transport failure is detected at the top of the polling loop.
        }
    }

    /** No answer from the ECU for a while means it went quiet (ignition off) while the adapter is fine. */
    private fun markCar(answered: Boolean) {
        val l = _link.value
        val now = System.currentTimeMillis()
        if (answered) {
            lastCarAnswer = now
            if (l.car == CarState.Lost) _link.value = l.copy(car = CarState.Online, message = "Connected")
        } else if (now - lastCarAnswer > STALE_MS && l.car == CarState.Online) {
            _link.value = l.copy(car = CarState.Lost, message = "Car stopped responding")
        }
    }

    /** Atmospheric pressure (kPa): PID 33 when supported, else MAP read with the engine stopped. */
    private var baroKpa = 101.3

    private fun computeDerived() {
        val v = _values.value
        v[0x0B]?.let { map ->
            v[0x33]?.let { baroKpa = it }
                ?: run { if ((v[0x0C] ?: 1.0) < 1.0) baroKpa = map }
        }
        val now = System.nanoTime()
        val dt = if (lastTick == 0L) 0.0 else ((now - lastTick) / 1e9).coerceAtMost(2.0)
        lastTick = now

        val diesel = _vehicle.value?.fuelType?.contains("Diesel") == true
        val afr = if (diesel) 14.5 else 14.7
        val density = if (diesel) 832.0 else 740.0
        val rate: Double? = v[0x5E]
            ?: v[0x10]?.let { maf -> maf * 3600 / (afr * density) }
            ?: run {
                val rpm = v[0x0C] ?: return@run null
                val map = v[0x0B] ?: return@run null
                val iat = v[0x0F] ?: 25.0
                // Speed-density estimate when the car has no MAF sensor.
                val maf = (rpm * map / (iat + 273.15) / 2) / 60 * 0.85 * Settings.displacement * 28.97 / 8.314
                maf * 3600 / (afr * density)
            }
        val speed = v[0x0D]
        val out = mutableMapOf<Int, Double>()
        if (rate != null) {
            out[Virtual.FUEL_RATE] = rate
            if (speed != null && speed > 3) {
                out[Virtual.ECONOMY] = rate / speed * 100
                out[Virtual.KM_PER_L] = if (rate > 0.01) speed / rate else 99.9
            }
            tripFuel += rate * dt / 3600
        }
        if (speed != null) tripKm += speed * dt / 3600
        out[Virtual.TRIP_KM] = tripKm
        out[Virtual.TRIP_FUEL] = tripFuel
        if (tripKm > 0.1) out[Virtual.TRIP_AVG] = tripFuel / tripKm * 100
        v[0x0B]?.let { out[Virtual.BOOST] = (it - baroKpa) / 100 }
        _values.update { it + out }
    }

    fun resetTrip() {
        tripKm = 0.0
        tripFuel = 0.0
        lastTick = 0L
    }

    /** Raw command for the terminal. */
    suspend fun raw(cmd: String): String = elm?.send(cmd, 6000) ?: throw ObdException("Not connected")

    suspend fun readDtcs(): Triple<List<String>, List<String>, List<String>> {
        val e = elm ?: throw ObdException("Not connected")
        return Triple(e.readDtcs("03"), e.readDtcs("07"), runCatching { e.readDtcs("0A") }.getOrDefault(emptyList()))
    }

    suspend fun clearDtcs(): Boolean {
        val e = elm ?: throw ObdException("Not connected")
        return e.clearDtcs()
    }

    /** Mode 01 PID 01: MIL lamp state and number of stored codes. */
    /** PID 01 decoded: lamp, code count and readiness monitors. */
    suspend fun readiness(): Readiness? = elm?.readPidRetry(0x01)?.let { Readiness.decode(it) }

    /** One decoded reading on demand (null when unsupported or no answer). */
    suspend fun readValue(pid: Int): Double? {
        val e = elm ?: return null
        if (!isSupported(pid)) return null
        return e.readPidRetry(pid, 2)?.let { Pids[pid]?.decode?.invoke(it) }
    }

    suspend fun monitorStatus(): Pair<Boolean, Int>? {
        val e = elm ?: return null
        val d = e.readPid(0x01) ?: return null
        if (d.isEmpty()) return null
        return (d[0] and 0x80 != 0) to (d[0] and 0x7F)
    }
}
