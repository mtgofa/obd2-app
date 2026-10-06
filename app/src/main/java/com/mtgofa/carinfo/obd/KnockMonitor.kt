package com.mtgofa.carinfo.obd

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class KnockStats(
    /** Sudden timing pulls under steady load: the ECU's reaction to knock. */
    val events: Int = 0,
    /** Seconds of driving where knock could be judged (mid RPM, real load). */
    val loadedSeconds: Double = 0.0,
    /** Average ignition advance while under real load, in degrees. */
    val avgTimingUnderLoad: Double? = null,
    val lastEventMs: Long = 0,
) {
    enum class Verdict { NotEnoughData, Good, Fair, Poor }

    val eventsPerMinute: Double get() = if (loadedSeconds > 0) events / (loadedSeconds / 60) else 0.0

    val verdict: Verdict
        get() = when {
            loadedSeconds < 90 -> Verdict.NotEnoughData
            eventsPerMinute < 0.5 -> Verdict.Good
            eventsPerMinute < 2.0 -> Verdict.Fair
            else -> Verdict.Poor
        }
}

/**
 * Standard OBD-II has no knock-count PID, so knock is estimated from what the ECU does about it:
 * when the knock sensor fires, the ECU pulls ignition timing straight away. A drop of several
 * degrees in timing advance (PID 0E) between two readings, while RPM and load stay steady,
 * is counted as a probable knock event. Frequent events under load point to low-octane fuel.
 */
object KnockMonitor {
    private const val MIN_PULL_DEG = 4.0
    private const val MAX_GAP_NS = 1_500_000_000L
    private const val COOLDOWN_NS = 2_000_000_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _stats = MutableStateFlow(KnockStats())
    val stats = _stats.asStateFlow()

    private var prevTiming: Sample? = null
    private var prevRpm = 0.0
    private var prevLoad = 0.0
    private var lastEventNs = 0L
    private var timingSum = 0.0
    private var timingCount = 0

    fun start() {
        scope.launch {
            Obd.samples.collect { s -> if (s.pid == 0x0E) onTiming(s) }
        }
    }

    fun reset() {
        prevTiming = null
        timingSum = 0.0
        timingCount = 0
        _stats.value = KnockStats()
    }

    private fun onTiming(s: Sample) {
        val v = Obd.values.value
        val rpm = v[0x0C] ?: return
        val load = v[0x04] ?: return
        val loaded = rpm in 1200.0..4500.0 && load >= 40
        val prev = prevTiming
        var st = _stats.value

        if (loaded && prev != null && s.timeNanos - prev.timeNanos <= MAX_GAP_NS) {
            st = st.copy(loadedSeconds = st.loadedSeconds + (s.timeNanos - prev.timeNanos) / 1e9)
            timingSum += s.value
            timingCount++
            st = st.copy(avgTimingUnderLoad = timingSum / timingCount)
            val steady = kotlin.math.abs(rpm - prevRpm) < 300 && kotlin.math.abs(load - prevLoad) < 15
            if (steady && prev.value - s.value >= MIN_PULL_DEG && s.timeNanos - lastEventNs > COOLDOWN_NS) {
                lastEventNs = s.timeNanos
                st = st.copy(events = st.events + 1, lastEventMs = System.currentTimeMillis())
            }
        }
        _stats.value = st
        prevTiming = s
        prevRpm = rpm
        prevLoad = load
    }
}
