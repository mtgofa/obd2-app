package com.mtgofa.carinfo.auto

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mtgofa.carinfo.obd.Virtual

/** A widget size in grid cells. */
data class WidgetSize(val w: Int, val h: Int, val label: String)

/** One widget on the Android Auto grid: a reading drawn as a round gauge or a number, [w]×[h] cells at ([x],[y]). */
data class Widget(val id: Int, val pid: Int, val gauge: Boolean, val x: Int, val y: Int, val w: Int, val h: Int) {
    fun overlaps(ox: Int, oy: Int, ow: Int, oh: Int) = x < ox + ow && ox < x + w && y < oy + oh && oy < y + h
    fun contains(cx: Int, cy: Int) = cx in x until x + w && cy in y until y + h
}

/**
 * The Android Auto screen as the user laid it out: a [COLS]×[ROWS] grid of widgets, edited live on
 * the phone. Only the readings on it are requested from the car while Android Auto shows it.
 */
object AutoLayout {
    // Declared before the state below: object properties initialize in source order.
    // 8×4 keeps cells close to square on car screens and phones in landscape, so a 2×2 gauge is round.
    const val COLS = 8
    const val ROWS = 4
    private const val KEY = "autoGrid8"

    /** Every widget can take any of these; wide gauges are drawn as half circles. */
    val SIZES = listOf(
        WidgetSize(1, 1, "1×1"), WidgetSize(2, 1, "2×1"), WidgetSize(3, 1, "3×1"), WidgetSize(4, 1, "4×1"),
        WidgetSize(2, 2, "2×2"), WidgetSize(3, 2, "3×2"), WidgetSize(4, 2, "4×2"), WidgetSize(3, 3, "3×3"), WidgetSize(4, 4, "4×4"),
    )

    private val DEFAULT = listOf(
        Widget(0, 0x0D, true, 0, 0, 3, 3),
        Widget(1, 0x0C, true, 3, 0, 3, 3),
        Widget(2, 0x04, true, 6, 0, 2, 2),
        Widget(3, Virtual.FUEL_RATE, false, 6, 2, 2, 1),
        Widget(4, 0x05, false, 0, 3, 2, 1),
        Widget(5, 0x0F, false, 2, 3, 2, 1),
        Widget(6, Virtual.BATTERY, false, 4, 3, 2, 1),
        Widget(7, 0x46, false, 6, 3, 2, 1),
    )

    /** Readings offered when adding a widget, most useful first. */
    val OPTIONS = listOf(
        0x0D, 0x0C, 0x05, 0x5C, 0x0F, 0x46, Virtual.BATTERY, 0x04, Virtual.BOOST, 0x0B, 0x11, 0x0E,
        Virtual.FUEL_RATE, Virtual.KM_PER_L, Virtual.ECONOMY, 0x2F, 0x10, 0x06, 0x07, 0x3C, 0x3E, 0x42,
    )

    private var sp: SharedPreferences? = null

    var widgets by mutableStateOf(DEFAULT); private set

    fun init(context: Context) {
        sp = context.getSharedPreferences("androidAuto", Context.MODE_PRIVATE)
        widgets = decode(sp?.getString(KEY, null)) ?: DEFAULT
    }

    fun at(cx: Int, cy: Int): Widget? = widgets.firstOrNull { it.contains(cx, cy) }

    /** Whether a [w]×[h] block at ([x],[y]) is inside the grid and clear of every widget except [ignore]. */
    fun fits(x: Int, y: Int, w: Int, h: Int, ignore: Set<Int> = emptySet(), among: List<Widget> = widgets): Boolean =
        x >= 0 && y >= 0 && x + w <= COLS && y + h <= ROWS &&
            among.none { it.id !in ignore && it.overlaps(x, y, w, h) }

    /** Nearest free spot for a [w]×[h] block, trying ([px],[py]) first. */
    private fun place(w: Int, h: Int, px: Int, py: Int, ignore: Set<Int> = emptySet()): Pair<Int, Int>? {
        val spots = (0..ROWS - h).flatMap { y -> (0..COLS - w).map { x -> x to y } }
        return spots.filter { (x, y) -> fits(x, y, w, h, ignore) }
            .minByOrNull { (x, y) -> (x - px) * (x - px) + (y - py) * (y - py) }
    }

    /** Whether a [w]×[h] widget can go anywhere on the grid (ignoring widget [ignore], e.g. the one being resized). */
    fun hasRoom(w: Int, h: Int, ignore: Int? = null): Boolean =
        place(w, h, 0, 0, ignore?.let { setOf(it) } ?: emptySet()) != null

    /** Top-left corner for a [w]×[h] widget that covers the tapped cell, if any placement there is free. */
    fun spotCovering(cx: Int, cy: Int, w: Int, h: Int): Pair<Int, Int>? {
        val spots = (cy - h + 1..cy).flatMap { y -> (cx - w + 1..cx).map { x -> x to y } }
        // Prefer the one centred on the tapped cell.
        val ix = cx - (w - 1) / 2
        val iy = cy - (h - 1) / 2
        return spots.filter { (x, y) -> fits(x, y, w, h) }.minByOrNull { (x, y) -> (x - ix) * (x - ix) + (y - iy) * (y - iy) }
    }

    /** Add a widget on the tapped cell. Returns false when that size doesn't fit there. */
    fun add(pid: Int, gauge: Boolean, size: WidgetSize, cx: Int, cy: Int): Boolean {
        val (x, y) = spotCovering(cx, cy, size.w, size.h) ?: return false
        save(widgets + Widget((widgets.maxOfOrNull { it.id } ?: -1) + 1, pid, gauge, x, y, size.w, size.h))
        return true
    }

    /** Change reading, kind or size in place; a bigger size slides to the nearest spot that fits. */
    fun update(id: Int, pid: Int, gauge: Boolean, size: WidgetSize): Boolean {
        val old = widgets.firstOrNull { it.id == id } ?: return false
        val (x, y) = if (fits(old.x, old.y, size.w, size.h, setOf(id))) old.x to old.y
        else place(size.w, size.h, old.x, old.y, setOf(id)) ?: return false
        save(widgets.map { if (it.id == id) it.copy(pid = pid, gauge = gauge, x = x, y = y, w = size.w, h = size.h) else it })
        return true
    }

    /**
     * Drop widget [id] with its top-left at ([tx],[ty]). Moves it if the spot is free; if it lands on
     * exactly one other widget, the two swap places when both still fit.
     */
    fun move(id: Int, tx: Int, ty: Int): Boolean {
        val a = widgets.firstOrNull { it.id == id } ?: return false
        val x = tx.coerceIn(0, COLS - a.w)
        val y = ty.coerceIn(0, ROWS - a.h)
        if (x == a.x && y == a.y) return false
        if (fits(x, y, a.w, a.h, setOf(id))) {
            save(widgets.map { if (it.id == id) it.copy(x = x, y = y) else it })
            return true
        }
        val hit = widgets.filter { it.id != id && it.overlaps(x, y, a.w, a.h) }
        if (hit.size != 1) return false
        val b = hit[0]
        // Swap: A takes B's corner, B takes A's old corner.
        val movedA = a.copy(x = b.x.coerceIn(0, COLS - a.w), y = b.y.coerceIn(0, ROWS - a.h))
        val movedB = b.copy(x = a.x.coerceIn(0, COLS - b.w), y = a.y.coerceIn(0, ROWS - b.h))
        val rest = widgets.filter { it.id != a.id && it.id != b.id }
        val ok = fits(movedA.x, movedA.y, movedA.w, movedA.h, among = rest + movedB) &&
            fits(movedB.x, movedB.y, movedB.w, movedB.h, among = rest)
        if (!ok) return false
        save(rest + movedA + movedB)
        return true
    }

    fun remove(id: Int) = save(widgets.filter { it.id != id })

    fun reset() {
        widgets = DEFAULT
        sp?.edit()?.remove(KEY)?.apply()
    }

    private fun save(list: List<Widget>) {
        widgets = list
        sp?.edit()?.putString(KEY, encode(list))?.apply()
    }

    // ---------------------------------------------------------------- polling

    /** Gauges move, so they're polled every cycle; number widgets round-robin. */
    fun fastPids(): List<Int> = sources(widgets.filter { it.gauge }.map { it.pid })

    fun slowPids(): List<Int> = sources(widgets.filter { !it.gauge }.map { it.pid }) - fastPids().toSet()

    /** Raw PIDs behind each reading (some are computed from others). */
    fun sources(pids: List<Int>): List<Int> = pids.flatMap {
        when (it) {
            Virtual.BOOST -> listOf(0x0B, 0x33, 0x0C)
            Virtual.FUEL_RATE -> listOf(0x5E, 0x10, 0x0B, 0x0C, 0x0F)
            Virtual.KM_PER_L, Virtual.ECONOMY -> listOf(0x0D, 0x5E, 0x10, 0x0B, 0x0C, 0x0F)
            else -> listOf(it)
        }
    }.distinct()

    // ---------------------------------------------------------------- storage: "0D:g:0:0:3:3;05:n:0:3:2:1"

    private fun encode(list: List<Widget>) =
        list.joinToString(";") { "%X:%s:%d:%d:%d:%d".format(it.pid, if (it.gauge) "g" else "n", it.x, it.y, it.w, it.h) }

    private fun decode(s: String?): List<Widget>? {
        if (s == null) return null
        return runCatching {
            s.split(";").filter { it.isNotBlank() }.mapIndexed { i, part ->
                val f = part.split(":")
                Widget(i, f[0].toInt(16), f[1] == "g", f[2].toInt(), f[3].toInt(), f[4].toInt(), f[5].toInt())
            }.filter { it.x >= 0 && it.y >= 0 && it.x + it.w <= COLS && it.y + it.h <= ROWS }
        }.getOrNull()
    }
}
