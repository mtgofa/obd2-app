package com.mtgofa.carinfo.obd

import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** Simulated ELM327 + CAN car (a Chery Tiggo-like petrol SUV) for trying the app without a vehicle. */
class DemoTransport : Transport() {
    override val label = "Demo car (simulated)"
    private val start = System.currentTimeMillis()
    private var stored = mutableListOf("P0301", "P0420")
    private var pending = mutableListOf("P0171")
    private val vin = "LVVDB21B5PD123456"

    private val supported = setOf(
        0x01, 0x04, 0x05, 0x06, 0x07, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x1C, 0x1F, 0x20,
        0x21, 0x2F, 0x31, 0x33, 0x3C, 0x3E, 0x40, 0x42, 0x44, 0x46, 0x49, 0x4C, 0x51, 0x5C, 0x60, 0x80, 0xA0, 0xA6,
    )

    override suspend fun open() {
        delay(600)
        alive = true
    }

    override fun close() {
        alive = false
    }

    override suspend fun write(data: ByteArray) {
        val cmd = String(data).trim().uppercase().replace(" ", "")
        delay(if (cmd.startsWith("01")) 25L else 60L)
        incoming.trySend((respond(cmd) + "\r\r>").toByteArray())
    }

    private fun t() = (System.currentTimeMillis() - start) / 1000.0

    // A loop of accelerate / cruise / brake / idle every 40 s.
    private fun speed(): Double {
        val p = t() % 40
        return when {
            p < 10 -> p * 11.5
            p < 22 -> 115 + 8 * sin(p)
            p < 30 -> max(0.0, 115 - (p - 22) * 14.5)
            else -> 0.0
        }
    }

    private fun rpm(): Double {
        val s = speed()
        if (s < 1) return 780 + Random.nextDouble(-15.0, 15.0)
        val gear = when { s < 20 -> 1; s < 40 -> 2; s < 65 -> 3; s < 90 -> 4; else -> 5 }
        val ratio = doubleArrayOf(0.0, 125.0, 72.0, 48.0, 36.0, 28.0)[gear]
        return (s * ratio).coerceIn(900.0, 6800.0)
    }

    private fun coolant() = minOf(92.0, 35 + t() * 1.2) + sin(t() / 7) * 1.5
    private fun throttle() = if (t() % 40 < 10) 62.0 else if (t() % 40 < 22) 24.0 else 0.0

    private fun hex(vararg b: Int) = b.joinToString("") { "%02X".format(it.coerceIn(0, 255)) }

    private fun bitmap(base: Int): String {
        val bytes = IntArray(4)
        for (i in 0 until 32) if (base + i + 1 in supported) bytes[i / 8] = bytes[i / 8] or (0x80 shr (i % 8))
        return hex(*bytes)
    }

    private fun pid(p: Int): String? {
        if (p != 0 && p % 0x20 == 0 && p !in supported) return null
        if (p % 0x20 == 0) return bitmap(p)
        if (p !in supported) return null
        val s = speed()
        return when (p) {
            0x01 -> hex(0x80 or stored.size, 0x07, 0x65, 0x00)
            0x04 -> hex((throttle() * 1.6 + 18).coerceAtMost(100.0).times(2.55).roundToInt())
            0x05 -> hex((coolant() + 40).roundToInt())
            0x06 -> hex(128 + Random.nextInt(-6, 6))
            0x07 -> hex(128 + 4)
            0x0B -> hex((35 + throttle() * 0.9).roundToInt())
            0x0C -> (rpm() * 4).roundToInt().let { hex(it shr 8, it and 0xFF) }
            0x0D -> hex(s.roundToInt())
            0x0E -> hex(((12 + sin(t()) * 4) + 64).times(2).roundToInt())
            0x0F -> hex((34 + sin(t() / 30) * 2 + 40).roundToInt())
            0x10 -> (rpm() * (0.004 + throttle() / 4000) * 100).roundToInt().let { hex(it shr 8, it and 0xFF) }
            0x11 -> hex((throttle() * 2.55 + 30).roundToInt())
            0x1C -> hex(6)
            0x1F -> t().roundToInt().let { hex(it shr 8, it and 0xFF) }
            0x21 -> hex(0, 42)
            0x2F -> hex((63 - t() / 120).times(2.55).roundToInt())
            0x31 -> hex(0x04, 0xD2)
            0x33 -> hex(101)
            0x3C -> ((420 + s * 2.5 + 40) * 10).roundToInt().let { hex(it shr 8, it and 0xFF) }
            0x3E -> ((360 + s * 2 + 40) * 10).roundToInt().let { hex(it shr 8, it and 0xFF) }
            0x42 -> ((14.1 + sin(t() / 5) * 0.15) * 1000).roundToInt().let { hex(it shr 8, it and 0xFF) }
            0x44 -> hex(0x80, 0x00)
            0x46 -> hex(31 + 40)
            0x49 -> hex((throttle() * 2.55 + 35).roundToInt())
            0x4C -> hex((throttle() * 2.55).roundToInt())
            0x51 -> hex(1)
            0x5C -> hex((minOf(98.0, 30 + t() * 0.9) + 40).roundToInt())
            0xA6 -> (847_315 + (t() * 0.3).roundToInt()).let { hex(it shr 24, it shr 16 and 0xFF, it shr 8 and 0xFF, it and 0xFF) }
            else -> null
        }
    }

    private fun dtcBytes(codes: List<String>): String = codes.joinToString("") { c ->
        val letter = "PCBU".indexOf(c[0])
        val b1 = (letter shl 6) or (c[1].digitToInt() shl 4) or c[2].digitToInt(16)
        hex(b1, c.substring(3).toInt(16))
    }

    /** ISO-TP style multi-frame text reply like a real CAN ECU ("014\r0:...\r1:..."). */
    private fun multiFrame(pid: Int, text: String): String {
        val payload = hex(0x49, pid, 0x01) + text.map { "%02X".format(it.code) }.joinToString("")
        val total = payload.length / 2
        val frames = mutableListOf("%03X".format(total))
        frames += "0:" + payload.take(12)
        var rest = payload.drop(12)
        var n = 1
        while (rest.isNotEmpty()) {
            frames += "${n % 16}:" + rest.take(14).padEnd(14, '0')
            rest = rest.drop(14)
            n++
        }
        return frames.joinToString("\r")
    }

    private fun respond(cmd: String): String = when {
        cmd == "ATZ" -> "\r\rELM327 v2.1"
        cmd == "ATI" -> "ELM327 v2.1"
        cmd == "ATDPN" -> "A6"
        cmd == "ATDP" -> "AUTO, ISO 15765-4 (CAN 11/500)"
        cmd == "ATRV" -> "%.1fV".format(if (speed() > 0 || t() > 2) 14.2 + sin(t() / 6) * 0.1 else 12.6)
        cmd.startsWith("AT") -> "OK"
        cmd == "03" -> "43" + hex(stored.size) + dtcBytes(stored)
        cmd == "07" -> "47" + hex(pending.size) + dtcBytes(pending)
        cmd == "0A" -> "4A00"
        cmd == "04" -> { stored.clear(); pending.clear(); "44" }
        cmd == "0902" -> multiFrame(0x02, vin)
        cmd == "0904" -> multiFrame(0x04, "F4J16-EMS-V3.2")
        cmd == "090A" -> multiFrame(0x0A, "ECM-EngineControl")
        cmd.startsWith("01") && cmd.length >= 4 -> {
            val p = cmd.substring(2, 4).toIntOrNull(16)
            p?.let { pid(it) }?.let { "41" + cmd.substring(2, 4) + it } ?: "NO DATA"
        }
        else -> "?"
    }

}
