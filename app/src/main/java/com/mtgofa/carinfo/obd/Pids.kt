package com.mtgofa.carinfo.obd

enum class PidUnit(val symbol: String) {
    SPEED("km/h"), RPM("rpm"), TEMP("°C"), PERCENT("%"), KPA("kPa"), VOLT("V"), GPS("g/s"),
    DEG("°"), SEC("s"), MIN("min"), KM("km"), LPH("L/h"), L100("L/100km"), KML("km/L"),
    LITER("L"), RATIO("λ"), NONE("")
}

enum class Category { Engine, Temperature, Fuel, Air, Electrical, Status }

class Pid(
    val id: Int,
    val name: String,
    val short: String,
    val unit: PidUnit,
    val min: Double,
    val max: Double,
    val category: Category,
    val decode: (IntArray) -> Double?,
)

/** Virtual values computed by the app rather than read as a mode 01 PID. */
object Virtual {
    const val BATTERY = 0x1000      // ATRV adapter voltage
    const val FUEL_RATE = 0x1001    // L/h
    const val ECONOMY = 0x1002      // L/100km instantaneous
    const val KM_PER_L = 0x1003
    const val TRIP_KM = 0x1004
    const val TRIP_FUEL = 0x1005
    const val TRIP_AVG = 0x1006     // L/100km over the trip
}

private fun a(d: IntArray) = d.getOrNull(0)?.toDouble()
private fun ab(d: IntArray) = if (d.size >= 2) (d[0] * 256 + d[1]).toDouble() else null
private fun temp1(d: IntArray) = a(d)?.minus(40)
private fun pct(d: IntArray) = a(d)?.times(100.0 / 255)
private fun trim(d: IntArray) = a(d)?.let { (it - 128) * 100 / 128 }

/** Sensor-mask PIDs (0x67, 0x68, 0x78...): first byte says which sensors follow. Return first present. */
private fun maskedTemp1(d: IntArray): Double? {
    if (d.size < 2) return null
    return if (d[0] and 1 != 0) d[1] - 40.0 else if (d.size > 2 && d[0] and 2 != 0) d[2] - 40.0 else null
}

private fun maskedEgt(d: IntArray): Double? {
    if (d.size < 3) return null
    for (i in 0 until 4) {
        if (d[0] and (1 shl i) != 0 && d.size >= 3 + i * 2) {
            return (d[1 + i * 2] * 256 + d[2 + i * 2]) / 10.0 - 40
        }
    }
    return null
}

object Pids {
    val all: List<Pid> = listOf(
        Pid(0x0D, "Vehicle speed", "Speed", PidUnit.SPEED, 0.0, 240.0, Category.Engine, ::a),
        Pid(0x0C, "Engine RPM", "RPM", PidUnit.RPM, 0.0, 8000.0, Category.Engine) { d -> ab(d)?.div(4) },
        Pid(0x04, "Calculated engine load", "Load", PidUnit.PERCENT, 0.0, 100.0, Category.Engine, ::pct),
        Pid(0x43, "Absolute load", "Abs load", PidUnit.PERCENT, 0.0, 100.0, Category.Engine) { d -> ab(d)?.times(100.0 / 255) },
        Pid(0x11, "Throttle position", "Throttle", PidUnit.PERCENT, 0.0, 100.0, Category.Engine, ::pct),
        Pid(0x45, "Relative throttle position", "Rel throttle", PidUnit.PERCENT, 0.0, 100.0, Category.Engine, ::pct),
        Pid(0x47, "Absolute throttle position B", "Throttle B", PidUnit.PERCENT, 0.0, 100.0, Category.Engine, ::pct),
        Pid(0x49, "Accelerator pedal position D", "Pedal D", PidUnit.PERCENT, 0.0, 100.0, Category.Engine, ::pct),
        Pid(0x4A, "Accelerator pedal position E", "Pedal E", PidUnit.PERCENT, 0.0, 100.0, Category.Engine, ::pct),
        Pid(0x4C, "Commanded throttle actuator", "Throttle cmd", PidUnit.PERCENT, 0.0, 100.0, Category.Engine, ::pct),
        Pid(0x5A, "Relative accelerator pedal", "Pedal", PidUnit.PERCENT, 0.0, 100.0, Category.Engine, ::pct),
        Pid(0x0E, "Timing advance", "Timing", PidUnit.DEG, -64.0, 64.0, Category.Engine) { d -> a(d)?.let { it / 2 - 64 } },
        Pid(0x1F, "Run time since engine start", "Run time", PidUnit.SEC, 0.0, 65535.0, Category.Status, ::ab),
        Pid(0x2C, "Commanded EGR", "EGR", PidUnit.PERCENT, 0.0, 100.0, Category.Engine, ::pct),

        Pid(0x05, "Engine coolant temperature", "Coolant", PidUnit.TEMP, -40.0, 130.0, Category.Temperature, ::temp1),
        Pid(0x5C, "Engine oil temperature", "Oil", PidUnit.TEMP, -40.0, 150.0, Category.Temperature, ::temp1),
        Pid(0x0F, "Intake air temperature", "Intake air", PidUnit.TEMP, -40.0, 80.0, Category.Temperature, ::temp1),
        Pid(0x46, "Ambient air temperature", "Ambient", PidUnit.TEMP, -40.0, 60.0, Category.Temperature, ::temp1),
        Pid(0x67, "Engine coolant temperature (sensor)", "Coolant 2", PidUnit.TEMP, -40.0, 130.0, Category.Temperature, ::maskedTemp1),
        Pid(0x68, "Intake air temperature (sensor)", "Intake 2", PidUnit.TEMP, -40.0, 80.0, Category.Temperature, ::maskedTemp1),
        Pid(0x84, "Manifold surface temperature", "Manifold", PidUnit.TEMP, -40.0, 150.0, Category.Temperature, ::temp1),
        Pid(0x3C, "Catalyst temperature B1S1", "Catalyst B1S1", PidUnit.TEMP, -40.0, 1000.0, Category.Temperature) { d -> ab(d)?.let { it / 10 - 40 } },
        Pid(0x3D, "Catalyst temperature B2S1", "Catalyst B2S1", PidUnit.TEMP, -40.0, 1000.0, Category.Temperature) { d -> ab(d)?.let { it / 10 - 40 } },
        Pid(0x3E, "Catalyst temperature B1S2", "Catalyst B1S2", PidUnit.TEMP, -40.0, 1000.0, Category.Temperature) { d -> ab(d)?.let { it / 10 - 40 } },
        Pid(0x3F, "Catalyst temperature B2S2", "Catalyst B2S2", PidUnit.TEMP, -40.0, 1000.0, Category.Temperature) { d -> ab(d)?.let { it / 10 - 40 } },
        Pid(0x78, "Exhaust gas temperature bank 1", "EGT B1", PidUnit.TEMP, -40.0, 1000.0, Category.Temperature, ::maskedEgt),
        Pid(0x79, "Exhaust gas temperature bank 2", "EGT B2", PidUnit.TEMP, -40.0, 1000.0, Category.Temperature, ::maskedEgt),

        Pid(0x2F, "Fuel tank level", "Fuel level", PidUnit.PERCENT, 0.0, 100.0, Category.Fuel, ::pct),
        Pid(0x5E, "Engine fuel rate", "Fuel rate", PidUnit.LPH, 0.0, 30.0, Category.Fuel) { d -> ab(d)?.div(20) },
        Pid(0x06, "Short term fuel trim B1", "STFT B1", PidUnit.PERCENT, -25.0, 25.0, Category.Fuel, ::trim),
        Pid(0x07, "Long term fuel trim B1", "LTFT B1", PidUnit.PERCENT, -25.0, 25.0, Category.Fuel, ::trim),
        Pid(0x08, "Short term fuel trim B2", "STFT B2", PidUnit.PERCENT, -25.0, 25.0, Category.Fuel, ::trim),
        Pid(0x09, "Long term fuel trim B2", "LTFT B2", PidUnit.PERCENT, -25.0, 25.0, Category.Fuel, ::trim),
        Pid(0x0A, "Fuel pressure", "Fuel press", PidUnit.KPA, 0.0, 765.0, Category.Fuel) { d -> a(d)?.times(3) },
        Pid(0x22, "Fuel rail pressure (rel. vacuum)", "Rail press", PidUnit.KPA, 0.0, 5177.0, Category.Fuel) { d -> ab(d)?.times(0.079) },
        Pid(0x23, "Fuel rail gauge pressure", "Rail gauge", PidUnit.KPA, 0.0, 655350.0, Category.Fuel) { d -> ab(d)?.times(10) },
        Pid(0x44, "Commanded air-fuel equivalence", "Lambda", PidUnit.RATIO, 0.0, 2.0, Category.Fuel) { d -> ab(d)?.times(2.0 / 65536) },
        Pid(0x52, "Ethanol fuel", "Ethanol", PidUnit.PERCENT, 0.0, 100.0, Category.Fuel, ::pct),
        Pid(0x5D, "Fuel injection timing", "Inj timing", PidUnit.DEG, -210.0, 302.0, Category.Fuel) { d -> ab(d)?.let { it / 128 - 210 } },

        Pid(0x0B, "Intake manifold pressure", "MAP", PidUnit.KPA, 0.0, 255.0, Category.Air, ::a),
        Pid(0x10, "Mass air flow", "MAF", PidUnit.GPS, 0.0, 300.0, Category.Air) { d -> ab(d)?.div(100) },
        Pid(0x33, "Barometric pressure", "Baro", PidUnit.KPA, 0.0, 255.0, Category.Air, ::a),

        Pid(0x42, "Control module voltage", "ECU volts", PidUnit.VOLT, 0.0, 16.0, Category.Electrical) { d -> ab(d)?.div(1000) },
        Pid(0x5B, "Hybrid battery remaining", "Hybrid batt", PidUnit.PERCENT, 0.0, 100.0, Category.Electrical, ::pct),

        Pid(0x21, "Distance with MIL on", "MIL dist", PidUnit.KM, 0.0, 65535.0, Category.Status, ::ab),
        Pid(0x31, "Distance since codes cleared", "Since clear", PidUnit.KM, 0.0, 65535.0, Category.Status, ::ab),
        Pid(0x4D, "Time run with MIL on", "MIL time", PidUnit.MIN, 0.0, 65535.0, Category.Status, ::ab),
        Pid(0x4E, "Time since codes cleared", "Clear time", PidUnit.MIN, 0.0, 65535.0, Category.Status, ::ab),
        Pid(0xA6, "Odometer", "Odometer", PidUnit.KM, 0.0, 9_999_999.0, Category.Status) { d ->
            if (d.size >= 4) ((d[0].toLong() shl 24) + (d[1] shl 16) + (d[2] shl 8) + d[3]) / 10.0 else null
        },
    )

    private val byId = all.associateBy { it.id }
    operator fun get(id: Int): Pid? = byId[id] ?: virtualPids[id]

    val virtualPids: Map<Int, Pid> = listOf(
        Pid(Virtual.BATTERY, "Battery voltage", "Battery", PidUnit.VOLT, 10.0, 16.0, Category.Electrical) { null },
        Pid(Virtual.FUEL_RATE, "Fuel consumption", "Fuel rate", PidUnit.LPH, 0.0, 30.0, Category.Fuel) { null },
        Pid(Virtual.ECONOMY, "Instant economy", "Economy", PidUnit.L100, 0.0, 30.0, Category.Fuel) { null },
        Pid(Virtual.KM_PER_L, "Instant economy", "Economy", PidUnit.KML, 0.0, 40.0, Category.Fuel) { null },
        Pid(Virtual.TRIP_KM, "Trip distance", "Trip", PidUnit.KM, 0.0, 1000.0, Category.Fuel) { null },
        Pid(Virtual.TRIP_FUEL, "Trip fuel used", "Used", PidUnit.LITER, 0.0, 100.0, Category.Fuel) { null },
        Pid(Virtual.TRIP_AVG, "Trip average", "Average", PidUnit.L100, 0.0, 30.0, Category.Fuel) { null },
    ).associateBy { it.id }

    /** Every temperature PID, ordered the way the dashboard shows them. */
    val temperatures = listOf(0x05, 0x5C, 0x0F, 0x46, 0x67, 0x68, 0x84, 0x3C, 0x3D, 0x3E, 0x3F, 0x78, 0x79)

    val fuelTypes = mapOf(
        1 to "Gasoline", 2 to "Methanol", 3 to "Ethanol", 4 to "Diesel", 5 to "LPG", 6 to "CNG",
        7 to "Propane", 8 to "Electric", 9 to "Bifuel gasoline", 10 to "Bifuel methanol",
        11 to "Bifuel ethanol", 12 to "Bifuel LPG", 13 to "Bifuel CNG", 14 to "Bifuel propane",
        15 to "Bifuel electric", 17 to "Hybrid gasoline", 18 to "Hybrid ethanol", 19 to "Hybrid diesel",
        20 to "Hybrid electric",
    )

    val obdStandards = mapOf(
        1 to "OBD-II (CARB)", 2 to "OBD (EPA)", 3 to "OBD + OBD-II", 4 to "OBD-I", 5 to "Not OBD compliant",
        6 to "EOBD (Europe)", 7 to "EOBD + OBD-II", 8 to "EOBD + OBD", 9 to "EOBD, OBD and OBD-II",
        10 to "JOBD (Japan)", 11 to "JOBD + OBD-II", 12 to "JOBD + EOBD", 13 to "JOBD, EOBD and OBD-II",
        17 to "EMD", 18 to "EMD+", 19 to "HD OBD-C", 20 to "HD OBD", 21 to "WWH OBD", 23 to "HD EOBD-I",
        24 to "HD EOBD-I N", 25 to "HD EOBD-II", 26 to "HD EOBD-II N", 28 to "OBDBr-1", 29 to "OBDBr-2",
        30 to "KOBD (Korea)", 31 to "IOBD I (India)", 32 to "IOBD II (India)", 33 to "HD EOBD-IV",
    )
}
