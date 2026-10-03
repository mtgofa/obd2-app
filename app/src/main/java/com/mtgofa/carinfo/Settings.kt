package com.mtgofa.carinfo

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mtgofa.carinfo.obd.PidUnit
import java.util.Locale

/** User settings, observable from Compose and persisted to SharedPreferences. */
object Settings {
    private lateinit var sp: SharedPreferences

    var mph by mutableStateOf(false); private set
    var fahrenheit by mutableStateOf(false); private set
    var keepScreenOn by mutableStateOf(true); private set
    var autoConnect by mutableStateOf(true); private set
    var carName by mutableStateOf(""); private set
    var displacement by mutableStateOf(1.6f); private set
    var wifiHost by mutableStateOf("192.168.0.10"); private set
    var wifiPort by mutableStateOf(35000); private set
    var lastTarget by mutableStateOf(""); private set

    fun init(context: Context) {
        sp = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        mph = sp.getBoolean("mph", false)
        fahrenheit = sp.getBoolean("fahrenheit", false)
        keepScreenOn = sp.getBoolean("keepScreenOn", true)
        autoConnect = sp.getBoolean("autoConnect", true)
        carName = sp.getString("carName", "") ?: ""
        displacement = sp.getFloat("displacement", 1.6f)
        wifiHost = sp.getString("wifiHost", "192.168.0.10") ?: "192.168.0.10"
        wifiPort = sp.getInt("wifiPort", 35000)
        lastTarget = sp.getString("lastTarget", "") ?: ""
    }

    fun updateMph(v: Boolean) { mph = v; sp.edit().putBoolean("mph", v).apply() }
    fun updateFahrenheit(v: Boolean) { fahrenheit = v; sp.edit().putBoolean("fahrenheit", v).apply() }
    fun updateKeepScreenOn(v: Boolean) { keepScreenOn = v; sp.edit().putBoolean("keepScreenOn", v).apply() }
    fun updateAutoConnect(v: Boolean) { autoConnect = v; sp.edit().putBoolean("autoConnect", v).apply() }
    fun updateCarName(v: String) { carName = v; sp.edit().putString("carName", v).apply() }
    fun updateDisplacement(v: Float) { displacement = v; sp.edit().putFloat("displacement", v).apply() }
    fun updateWifi(host: String, port: Int) {
        wifiHost = host; wifiPort = port
        sp.edit().putString("wifiHost", host).putInt("wifiPort", port).apply()
    }
    fun updateLastTarget(v: String) { lastTarget = v; sp.edit().putString("lastTarget", v).apply() }
}

/** Converts a raw metric value to the user's units and formats it. */
object Fmt {
    fun value(unit: PidUnit, v: Double?): String {
        if (v == null || v.isNaN() || v.isInfinite()) return "--"
        val x = convert(unit, v)
        return when (unit) {
            PidUnit.SPEED, PidUnit.RPM, PidUnit.TEMP, PidUnit.KPA, PidUnit.SEC, PidUnit.MIN -> "%.0f".format(Locale.US, x)
            PidUnit.KM -> if (x >= 1000) "%,.0f".format(Locale.US, x) else "%.1f".format(Locale.US, x)
            PidUnit.VOLT, PidUnit.LPH, PidUnit.L100, PidUnit.KML, PidUnit.LITER, PidUnit.GPS, PidUnit.DEG -> "%.1f".format(Locale.US, x)
            PidUnit.RATIO -> "%.2f".format(Locale.US, x)
            else -> "%.0f".format(Locale.US, x)
        }
    }

    fun convert(unit: PidUnit, v: Double): Double = when (unit) {
        PidUnit.SPEED -> if (Settings.mph) v * 0.621371 else v
        PidUnit.KM -> if (Settings.mph) v * 0.621371 else v
        PidUnit.TEMP -> if (Settings.fahrenheit) v * 9 / 5 + 32 else v
        else -> v
    }

    fun unit(unit: PidUnit): String = when (unit) {
        PidUnit.SPEED -> if (Settings.mph) "mph" else "km/h"
        PidUnit.KM -> if (Settings.mph) "mi" else "km"
        PidUnit.TEMP -> if (Settings.fahrenheit) "°F" else "°C"
        else -> unit.symbol
    }
}
