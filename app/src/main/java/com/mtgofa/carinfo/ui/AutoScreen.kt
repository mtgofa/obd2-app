package com.mtgofa.carinfo.ui

import android.graphics.Rect
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mtgofa.carinfo.Fmt
import com.mtgofa.carinfo.auto.AutoLayout
import com.mtgofa.carinfo.auto.DashRenderer
import com.mtgofa.carinfo.auto.Widget
import com.mtgofa.carinfo.auto.WidgetSize
import com.mtgofa.carinfo.obd.LinkState
import com.mtgofa.carinfo.obd.Obd
import com.mtgofa.carinfo.obd.Pids
import com.mtgofa.carinfo.obd.Virtual
import kotlin.math.floor
import kotlin.math.roundToInt

/** What the add/edit dialog is for: a new widget at a cell, or an existing one. */
private sealed class Edit {
    data class Add(val cx: Int, val cy: Int) : Edit()
    data class Change(val id: Int) : Edit()
}

/**
 * The Android Auto screen itself, live, edited in place: tap an empty square to add a widget,
 * long-press a widget and drag it to move it (dropping on another widget swaps them), or
 * long-press and release for its options.
 */
@Composable
fun AutoScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val renderer = remember { DashRenderer(ctx) }
    val values by Obd.values.collectAsState()
    val link by Obd.link.collectAsState()
    val supported by Obd.supported.collectAsState()
    val widgets = AutoLayout.widgets
    var edit by remember { mutableStateOf<Edit?>(null) }
    // Drag state: the widget being moved, where it was grabbed (in cells) and the finger position.
    var dragId by remember { mutableStateOf<Int?>(null) }
    var grab by remember { mutableStateOf(Offset.Zero) }
    var finger by remember { mutableStateOf(Offset.Zero) }
    var moved by remember { mutableStateOf(false) }

    // Landscape, like the car screen, so the grid gets the whole phone screen.
    val activity = ctx as? android.app.Activity
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(Unit) {
        val old = activity?.requestedOrientation
        activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        // Full screen, like the car display: hide the status and navigation bars (swipe to peek).
        val bars = activity?.window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
        bars?.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        bars?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        onDispose {
            bars?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            activity?.requestedOrientation = old ?: android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    // Same readings the car screen asks for, so this page shows exactly what it will show.
    Subscribe("auto-editor", fast = AutoLayout.fastPids(), slow = AutoLayout.slowPids())

    // The whole screen is the car screen: no title bar; back sits before "OBD2", Reset after "Car".
    Box(Modifier.fillMaxSize()) {
        run {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val w = constraints.maxWidth
                val h = constraints.maxHeight
                val status = DashRenderer.statusRect(Rect(0, 0, w, h))
                val density = androidx.compose.ui.platform.LocalDensity.current
                val backPx = with(density) { 44.dp.toPx() }
                val grid = DashRenderer.gridRect(Rect(0, 0, w, h))
                val cw = grid.width() / AutoLayout.COLS
                val ch = grid.height() / AutoLayout.ROWS

                fun cellAt(o: Offset): Pair<Int, Int>? {
                    val cx = floor((o.x - grid.left) / cw).toInt()
                    val cy = floor((o.y - grid.top) / ch).toInt()
                    return if (cx in 0 until AutoLayout.COLS && cy in 0 until AutoLayout.ROWS) cx to cy else null
                }

                fun dropTarget(wd: Widget): Pair<Int, Int> {
                    val x = ((finger.x - grid.left) / cw - grab.x).roundToInt()
                    val y = ((finger.y - grid.top) / ch - grab.y).roundToInt()
                    return x.coerceIn(0, AutoLayout.COLS - wd.w) to y.coerceIn(0, AutoLayout.ROWS - wd.h)
                }

                Canvas(
                    Modifier.fillMaxSize()
                        .pointerInput(widgets, w, h) {
                            detectTapGestures { o ->
                                val (cx, cy) = cellAt(o) ?: return@detectTapGestures
                                edit = AutoLayout.at(cx, cy)?.let { Edit.Change(it.id) } ?: Edit.Add(cx, cy)
                            }
                        }
                        .pointerInput(widgets, w, h) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { o ->
                                    val cell = cellAt(o)
                                    val wd = cell?.let { AutoLayout.at(it.first, it.second) }
                                    if (wd != null) {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        dragId = wd.id
                                        grab = Offset((o.x - grid.left) / cw - wd.x, (o.y - grid.top) / ch - wd.y)
                                        finger = o
                                        moved = false
                                    }
                                },
                                onDrag = { change, delta ->
                                    if (dragId != null) {
                                        change.consume()
                                        finger += delta
                                        moved = true
                                    }
                                },
                                onDragEnd = {
                                    val wd = dragId?.let { id -> widgets.firstOrNull { it.id == id } }
                                    if (wd != null) {
                                        val (x, y) = dropTarget(wd)
                                        if (!moved || (x == wd.x && y == wd.y)) edit = Edit.Change(wd.id)
                                        else if (!AutoLayout.move(wd.id, x, y)) {
                                            Toast.makeText(ctx, "No room there", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    dragId = null
                                },
                                onDragCancel = { dragId = null },
                            )
                        },
                ) {
                    drawIntoCanvas { renderer.preview(it.nativeCanvas, w, h, values, statusLeft = backPx) }

                    // Faint dashed squares so the empty spots are visible.
                    val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 8f))
                    for (cy in 0 until AutoLayout.ROWS) for (cx in 0 until AutoLayout.COLS) {
                        if (widgets.any { it.contains(cx, cy) }) continue
                        drawRoundRect(
                            Color.White.copy(alpha = 0.14f),
                            Offset(grid.left + cx * cw + 6, grid.top + cy * ch + 6), Size(cw - 12, ch - 12),
                            CornerRadius(12f), style = Stroke(2f, pathEffect = dash),
                        )
                    }

                    // While dragging: show where it would land (green = ok / swap, red = no room) and the lifted widget.
                    val wd = dragId?.let { id -> widgets.firstOrNull { it.id == id } }
                    if (wd != null) {
                        val (tx, ty) = dropTarget(wd)
                        val free = AutoLayout.fits(tx, ty, wd.w, wd.h, setOf(wd.id))
                        val swap = !free && widgets.count { it.id != wd.id && it.overlaps(tx, ty, wd.w, wd.h) } == 1
                        val color = if (free || swap) Color(0xFF39FF88) else Color(0xFFFF4D5E)
                        val at = Offset(grid.left + tx * cw, grid.top + ty * ch)
                        val sz = Size(wd.w * cw, wd.h * ch)
                        drawRoundRect(color.copy(alpha = 0.18f), at, sz, CornerRadius(16f))
                        drawRoundRect(color, at, sz, CornerRadius(16f), style = Stroke(4f))
                        drawRoundRect(
                            Color.White.copy(alpha = 0.8f), Offset(finger.x - grab.x * cw, finger.y - grab.y * ch), sz,
                            CornerRadius(16f), style = Stroke(3f),
                        )
                    }
                }

                // Back before "OBD2" and Reset at the right end, both on the status line.
                val rowTop = with(density) { status.top.toDp() }
                val rowH = with(density) { status.height().toDp() }.coerceAtLeast(40.dp)
                val left = with(density) { status.left.toDp() }
                val right = with(density) { (w - status.right).toDp() }
                Row(
                    Modifier.fillMaxWidth().padding(start = left - 8.dp, end = right + 28.dp).offset(y = rowTop - (rowH - with(density) { status.height().toDp() }) / 2)
                        .height(rowH),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    androidx.compose.material3.IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
                        androidx.compose.material3.Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = AppColors.text,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { AutoLayout.reset() }) {
                        Text("Reset", color = AppColors.dim, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }

    edit?.let { e ->
        val current = (e as? Edit.Change)?.let { c -> widgets.firstOrNull { it.id == c.id } }
        WidgetDialog(
            current = current,
            fits = { size ->
                when (e) {
                    is Edit.Add -> AutoLayout.spotCovering(e.cx, e.cy, size.w, size.h) != null
                    is Edit.Change -> AutoLayout.hasRoom(size.w, size.h, e.id)
                }
            },
            isSupported = { pid ->
                val raw = if (pid == Virtual.BOOST) 0x0B else pid
                link.state != LinkState.Connected || raw >= 0x1000 || supported.isEmpty() || raw in supported
            },
            onSave = { pid, gauge, size ->
                val ok = when (e) {
                    is Edit.Add -> AutoLayout.add(pid, gauge, size, e.cx, e.cy)
                    is Edit.Change -> AutoLayout.update(e.id, pid, gauge, size)
                }
                if (ok) edit = null else Toast.makeText(ctx, "Not enough room for ${size.label}", Toast.LENGTH_SHORT).show()
            },
            onRemove = current?.let { c -> { AutoLayout.remove(c.id); edit = null } },
            onDismiss = { edit = null },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WidgetDialog(
    current: Widget?,
    fits: (WidgetSize) -> Boolean,
    isSupported: (Int) -> Boolean,
    onSave: (Int, Boolean, WidgetSize) -> Unit,
    onRemove: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var gauge by remember { mutableStateOf(current?.gauge ?: true) }
    var pid by remember { mutableStateOf(current?.pid ?: 0x0D) }
    val sizes = AutoLayout.SIZES
    var size by remember {
        mutableStateOf(current?.let { c -> WidgetSize(c.w, c.h, "${c.w}×${c.h}") })
    }
    // Keep the chosen size if this kind offers it and it fits; otherwise the biggest one that fits.
    val chosen = sizes.firstOrNull { it.w == size?.w && it.h == size?.h && fits(it) }
        ?: sizes.lastOrNull { fits(it) }

    AlertDialog(
        onDismissRequest = onDismiss,
        // Wider in landscape so the options and the readings list sit side by side.
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = !isLandscape()),
        modifier = if (isLandscape()) Modifier.fillMaxWidth(0.8f) else Modifier,
        title = { Text(if (current == null) "Add widget" else "Edit widget") },
        text = {
            val kindAndSize: @Composable () -> Unit = {
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppButton("Gauge", { gauge = true }, Modifier.weight(1f), color = if (gauge) AppColors.cyan else AppColors.dim)
                        AppButton("Number", { gauge = false }, Modifier.weight(1f), color = if (!gauge) AppColors.cyan else AppColors.dim)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("Size (squares)", color = AppColors.dim, fontSize = 12.sp)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.padding(top = 4.dp),
                    ) {
                        sizes.forEach { s ->
                            val on = s == chosen
                            val ok = fits(s)
                            Text(
                                s.label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                color = if (on) AppColors.cyan else if (ok) AppColors.text else AppColors.dim.copy(alpha = 0.4f),
                                modifier = Modifier.clip(RoundedCornerShape(10.dp))
                                    .background(if (on) AppColors.cyan.copy(alpha = 0.15f) else AppColors.inset)
                                    .clickable(enabled = ok) { size = s }.padding(horizontal = 12.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
            val readings: @Composable (Modifier) -> Unit = { mod ->
                // Open scrolled to the current reading.
                val list = rememberLazyListState(initialFirstVisibleItemIndex = AutoLayout.OPTIONS.indexOf(pid).coerceAtLeast(0))
                Column(mod) {
                    Text("Reading", color = AppColors.dim, fontSize = 12.sp)
                    LazyColumn(state = list) {
                        items(AutoLayout.OPTIONS) { option ->
                            val p = Pids[option] ?: return@items
                            val on = option == pid
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                    .background(if (on) AppColors.cyan.copy(alpha = 0.15f) else Color.Transparent)
                                    .clickable { pid = option }.padding(horizontal = 8.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    p.name, color = if (on) AppColors.cyan else if (isSupported(option)) AppColors.text else AppColors.dim,
                                    fontSize = 15.sp, modifier = Modifier.weight(1f),
                                )
                                Text(if (isSupported(option)) Fmt.unit(p.unit) else "not on your car", color = AppColors.dim, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
            if (isLandscape()) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f)) { kindAndSize() }
                    readings(Modifier.weight(1f).heightIn(max = 240.dp))
                }
            } else {
                Column {
                    kindAndSize()
                    Spacer(Modifier.height(10.dp))
                    readings(Modifier.heightIn(max = 300.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { chosen?.let { onSave(pid, gauge, it) } }, enabled = chosen != null) {
                Text(if (current == null) "Add" else "Save")
            }
        },
        dismissButton = {
            Row {
                if (onRemove != null) TextButton(onClick = onRemove) { Text("Remove", color = AppColors.red) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
