package com.mtgofa.carinfo.obd

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** One emissions self-test the ECU runs (catalyst, O2 sensor...). */
data class Monitor(val name: String, val complete: Boolean)

/** Mode 01 PID 01: check-engine lamp, stored code count and the readiness monitors. */
data class Readiness(val mil: Boolean, val codeCount: Int, val monitors: List<Monitor>) {
    companion object {
        private val sparkNames = listOf(
            "Catalyst", "Heated catalyst", "Evaporative system", "Secondary air",
            "A/C refrigerant", "Oxygen sensor", "Oxygen sensor heater", "EGR / VVT system",
        )
        private val dieselNames = listOf(
            "NMHC catalyst", "NOx / SCR aftertreatment", null, "Boost pressure",
            null, "Exhaust gas sensor", "Particulate filter", "EGR / VVT system",
        )

        /** Decode the 4 data bytes A B C D of PID 01 (SAE J1979). */
        fun decode(d: IntArray): Readiness? {
            if (d.size < 4) return null
            val (a, b, c, dd) = d
            val monitors = mutableListOf<Monitor>()
            // Byte B: continuous monitors. Low nibble = supported, high nibble = still incomplete.
            listOf("Misfire", "Fuel system", "Components").forEachIndexed { i, name ->
                if (b and (1 shl i) != 0) monitors += Monitor(name, b and (0x10 shl i) == 0)
            }
            // Bytes C (supported) / D (incomplete): non-continuous tests, spark or diesel set.
            val names = if (b and 0x08 != 0) dieselNames else sparkNames
            for (i in 0 until 8) {
                val name = names[i] ?: continue
                if (c and (1 shl i) != 0) monitors += Monitor(name, dd and (1 shl i) == 0)
            }
            return Readiness(a and 0x80 != 0, a and 0x7F, monitors)
        }
    }
}

/** A finished scan, kept so the Diagnosis page still has something to show while disconnected. */
data class DiagScan(
    val timeMs: Long,
    val stored: List<String>,
    val pending: List<String>,
    val permanent: List<String>,
    val readiness: Readiness?,
    /** km since codes were last cleared (PID 31) and driven with the lamp on (PID 21). */
    val kmSinceClear: Double?,
    val kmWithMil: Double?,
)

object DiagStore {
    private const val KEY = "lastScan"

    fun save(context: Context, s: DiagScan) {
        val o = JSONObject()
            .put("t", s.timeMs)
            .put("stored", JSONArray(s.stored)).put("pending", JSONArray(s.pending)).put("permanent", JSONArray(s.permanent))
            .put("kmSinceClear", s.kmSinceClear).put("kmWithMil", s.kmWithMil)
        s.readiness?.let { r ->
            o.put("mil", r.mil).put("count", r.codeCount)
                .put("monitors", JSONArray(r.monitors.map { JSONObject().put("n", it.name).put("ok", it.complete) }))
        }
        prefs(context).edit().putString(KEY, o.toString()).apply()
    }

    fun load(context: Context): DiagScan? = runCatching {
        val o = JSONObject(prefs(context).getString(KEY, null) ?: return null)
        fun list(k: String) = o.getJSONArray(k).let { a -> List(a.length()) { a.getString(it) } }
        fun dbl(k: String) = if (o.has(k) && !o.isNull(k)) o.getDouble(k) else null
        val readiness = if (o.has("monitors")) {
            val m = o.getJSONArray("monitors")
            Readiness(o.getBoolean("mil"), o.getInt("count"), List(m.length()) {
                m.getJSONObject(it).let { j -> Monitor(j.getString("n"), j.getBoolean("ok")) }
            })
        } else null
        DiagScan(o.getLong("t"), list("stored"), list("pending"), list("permanent"), readiness, dbl("kmSinceClear"), dbl("kmWithMil"))
    }.getOrNull()

    private fun prefs(context: Context) = context.getSharedPreferences("diagnosis", Context.MODE_PRIVATE)
}
