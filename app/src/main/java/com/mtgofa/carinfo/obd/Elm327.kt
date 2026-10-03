package com.mtgofa.carinfo.obd

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

class ObdException(message: String) : Exception(message)

/** Talks the ELM327 AT/OBD dialect over any [Transport]. Commands are serialized by a mutex. */
class Elm327(val transport: Transport) {
    private val mutex = Mutex()

    var version = ""
        private set
    var protocolNumber = ""
        private set
    var protocolName = ""
        private set
    val isCan: Boolean get() = protocolNumber.lastOrNull()?.let { it in '6'..'9' || it in 'A'..'C' } == true

    /** Append the "expected responses" digit to mode 01 requests so CAN adapters return immediately. */
    private var fastResponses = false

    /** Count of consecutive commands that timed out with no '>' prompt — a sign the link is dead. */
    var silentCount = 0
        private set

    var onTraffic: ((String, String) -> Unit)? = null

    suspend fun send(cmd: String, timeoutMs: Long = 2500): String = mutex.withLock {
        if (!transport.alive) throw IOException("Adapter disconnected")
        transport.drain()
        transport.write((cmd + "\r").toByteArray(Charsets.US_ASCII))
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        var prompt = false
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) break
            val chunk = transport.read(left) ?: break
            sb.append(String(chunk, Charsets.ISO_8859_1))
            if (sb.indexOf(">") >= 0) {
                prompt = true
                break
            }
        }
        silentCount = if (prompt || sb.isNotBlank()) 0 else silentCount + 1
        val cleaned = clean(sb.toString(), cmd)
        onTraffic?.invoke(cmd, cleaned)
        cleaned
    }

    private fun clean(raw: String, cmd: String): String =
        raw.replace(">", "")
            .split('\r', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { it.equals(cmd, ignoreCase = true) || it.startsWith("SEARCHING") }
            .map { it.removePrefix("BUS INIT: ...OK").removePrefix("BUS INIT: OK").trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")

    /** Reset and configure the adapter (doesn't need the car). */
    suspend fun setupAdapter() {
        send("ATZ", 3000)
        delay(400)
        // Echo may still be on from the reset; send twice so the second reply is clean.
        send("ATE0")
        if (!send("ATE0").contains("OK")) send("ATE0")
        version = send("ATI").lineSequence().firstOrNull { it.contains("ELM", true) } ?: "ELM327"
        send("ATL0")
        send("ATS0")
        send("ATH0")
        send("ATAT1")
        send("ATSP0")
        if (version.isBlank() && !transport.alive) throw ObdException("The adapter stopped responding.")
    }

    /**
     * Ask the car's ECU for PID support. Returns true once the car answers; false while the
     * ignition is off / the ECU is silent. Throws only if the adapter itself stopped talking.
     */
    suspend fun probeCar(): Boolean {
        val probe = send("0100", 15_000)
        if (!probe.contains("4100")) {
            if (probe.isBlank() && silentCount >= 2) throw ObdException("The adapter stopped responding.")
            return false
        }
        protocolNumber = send("ATDPN").trim().let { if (it.length == 2 && it[0] == 'A') it.substring(1) else it }
        protocolName = send("ATDP").removePrefix("AUTO, ").trim()
        fastResponses = isCan && versionAtLeast(1.3)
        return true
    }

    private fun versionAtLeast(v: Double): Boolean =
        Regex("""v(\d+\.\d+)""", RegexOption.IGNORE_CASE).find(version)?.groupValues?.get(1)?.toDoubleOrNull()?.let { it >= v } ?: false

    /** Split a reply into messages; CAN multi-frame ("0:", "1:"...) lines are joined into one. */
    fun messages(reply: String): List<String> {
        val lines = reply.lines().map { it.replace(" ", "").uppercase() }.filter { it.isNotEmpty() }
        val multi = lines.filter { Regex("^[0-9A-F]:").containsMatchIn(it) }
        val result = mutableListOf<String>()
        if (multi.isNotEmpty()) result += multi.joinToString("") { it.substring(2) }
        for (l in lines) {
            if (Regex("^[0-9A-F]:").containsMatchIn(l)) continue
            if (multi.isNotEmpty() && l.length <= 3) continue // the multi-frame byte count line
            result += l
        }
        return result.filter { Regex("^[0-9A-F]+$").matches(it) }
    }

    private fun hexBytes(s: String): IntArray =
        IntArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16) }

    /** Read a mode 01 PID; returns the data bytes after "41 xx", or null for NO DATA. */
    suspend fun readPid(pid: Int): IntArray? {
        val hex = "%02X".format(pid)
        var reply = send("01$hex" + if (fastResponses) "1" else "")
        if (fastResponses && reply.contains("?")) {
            fastResponses = false
            reply = send("01$hex")
        }
        val prefix = "41$hex"
        val msg = messages(reply).firstOrNull { it.startsWith(prefix) } ?: return null
        return hexBytes(msg.substring(prefix.length))
    }

    /** Query the support bitmaps (0100, 0120, ...) and return every supported mode 01 PID. */
    suspend fun supportedPids(): Set<Int> {
        val out = mutableSetOf<Int>()
        var base = 0x00
        while (base <= 0xE0) {
            val data = readPid(base) ?: break
            if (data.size < 4) break
            for (i in 0 until 32) {
                val byte = data[i / 8]
                if (byte and (0x80 shr (i % 8)) != 0) out += base + i + 1
            }
            if (base + 0x20 !in out) break
            base += 0x20
        }
        return out
    }

    /** Read DTCs for mode 03 (stored), 07 (pending) or 0A (permanent). */
    suspend fun readDtcs(mode: String): List<String> {
        val reply = send(mode, 6000)
        val prefix = "%02X".format(mode.toInt(16) + 0x40)
        val codes = linkedSetOf<String>()
        for (m in messages(reply)) {
            if (!m.startsWith(prefix)) continue
            var bytes = hexBytes(m.substring(2))
            if (isCan && bytes.isNotEmpty()) {
                val count = bytes[0]
                bytes = bytes.copyOfRange(1, minOf(bytes.size, 1 + count * 2))
            }
            var i = 0
            while (i + 1 < bytes.size) {
                val b1 = bytes[i]
                val b2 = bytes[i + 1]
                i += 2
                if (b1 == 0 && b2 == 0) continue
                val letter = "PCBU"[b1 shr 6]
                codes += "%c%d%X%02X".format(letter, (b1 shr 4) and 3, b1 and 0x0F, b2)
            }
        }
        return codes.toList()
    }

    suspend fun clearDtcs(): Boolean = send("04", 6000).let { it.contains("44") }

    /** Mode 09 text value (VIN = 02, calibration ID = 04, ECU name = 0A). */
    suspend fun readInfoText(pid: String): String? {
        val reply = send("09$pid", 6000)
        if (reply.contains("NO DATA") || reply.contains("?")) return null
        val bytes = messages(reply).filter { it.startsWith("49") }.flatMap { m ->
            // Skip "49 xx" and the record counter; keep printable characters only.
            hexBytes(m).drop(3)
        }
        val text = bytes.filter { it in 0x20..0x7E }.map { it.toChar() }.joinToString("")
        return text.trim().ifEmpty { null }
    }

    suspend fun readVin(): String? {
        val reply = send("0902", 6000)
        if (reply.contains("NO DATA")) return null
        // VINs never contain I, O or Q, so 0x49 ('I') headers and counters drop out naturally.
        val chars = messages(reply).joinToString("") { m ->
            hexBytes(m).map { it.toChar() }.filter { it in 'A'..'Z' || it in '0'..'9' }
                .filter { it != 'I' && it != 'O' && it != 'Q' }.joinToString("")
        }
        return if (chars.length >= 17) chars.takeLast(17) else null
    }

    suspend fun batteryVoltage(): Double? =
        Regex("""(\d+(\.\d+)?)""").find(send("ATRV"))?.value?.toDoubleOrNull()
}
