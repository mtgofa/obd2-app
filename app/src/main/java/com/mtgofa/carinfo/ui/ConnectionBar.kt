package com.mtgofa.carinfo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mtgofa.carinfo.BuildConfig
import com.mtgofa.carinfo.Updater
import com.mtgofa.carinfo.obd.CarState
import com.mtgofa.carinfo.obd.LinkState
import com.mtgofa.carinfo.obd.Obd

/** Bottom bar on every screen: OBD2 adapter link on the left, car (ECU) link on the right. */
@Composable
fun ConnectionBar(onClick: () -> Unit) {
    val link by Obd.link.collectAsState()
    val vehicle by Obd.vehicle.collectAsState()
    val p = LocalPalette.current

    val adapter: Triple<String, Color, Boolean> = when (link.state) {
        LinkState.Disconnected -> Triple("Not connected", p.dim, false)
        LinkState.Connecting -> Triple("Connecting…", p.amber, true)
        LinkState.Initializing, LinkState.Connected -> Triple(link.via.substringAfter(" · ").ifBlank { "Connected" }, p.green, false)
        LinkState.Error -> Triple("Failed — tap", p.red, false)
    }
    val car: Triple<String, Color, Boolean> = when {
        link.state == LinkState.Connecting || (link.state == LinkState.Initializing && link.car == CarState.None) ->
            Triple("Waiting…", p.dim, false)
        link.car == CarState.Waiting -> Triple("Ignition off?", p.amber, true)
        link.car == CarState.Online -> Triple(vehicle?.title?.takeIf { it != "Unknown vehicle" } ?: "Online", p.green, false)
        link.car == CarState.Lost -> Triple("Not responding", p.red, false)
        else -> Triple("—", p.dim, false)
    }

    Column(Modifier.fillMaxWidth().background(if (p.dark) Color(0xFF120D26) else Color.White)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.cardBorder))
        Row(
            Modifier.fillMaxWidth().clickable { onClick() }.navigationBarsPadding()
                .padding(horizontal = 14.dp, vertical = if (isLandscape()) 3.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusItem(Icons.Rounded.Bluetooth, "OBD2", adapter, Modifier.weight(1f))
            Box(Modifier.width(1.dp).height(26.dp).background(p.cardBorder))
            Spacer(Modifier.width(12.dp))
            StatusItem(Icons.Rounded.DirectionsCar, "Car", car, Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatusItem(icon: ImageVector, title: String, s: Triple<String, Color, Boolean>, modifier: Modifier) {
    val (text, color, busy) = s
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        if (isLandscape()) {
            // One line in landscape so the bar takes as little height as possible.
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = AppColors.dim, fontSize = 11.sp, fontFamily = Sora, fontWeight = FontWeight.SemiBold)
                Text("  $text", color = AppColors.text, fontSize = 12.sp, maxLines = 1)
            }
        } else {
            Column(Modifier.weight(1f)) {
                Text(title, color = AppColors.dim, fontSize = 10.sp, fontFamily = Sora, fontWeight = FontWeight.SemiBold)
                Text(text, color = AppColors.text, fontSize = 12.sp, maxLines = 1)
            }
        }
        if (busy) CircularProgressIndicator(Modifier.size(12.dp), color = color, strokeWidth = 1.5.dp)
        else Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(10.dp))
    }
}

@Composable
fun UpdatePrompt() {
    if (!Updater.prompt) return
    val rel = Updater.release ?: return
    val ctx = LocalContext.current
    AlertDialog(
        onDismissRequest = { if (!Updater.downloading) Updater.later() },
        title = {
            Text(
                text = "New Update Available",
                fontFamily = Sora,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                color = AppColors.text,
            )
        },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(AppColors.inset)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "New: ${rel.tagName}",
                        fontFamily = Sora,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        color = AppColors.cyan
                    )
                    Text("Current: v${BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = AppColors.dim)
                }

                if (rel.notes.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text("What's new:", fontFamily = Sora, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = AppColors.dim)
                    Spacer(Modifier.height(4.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 150.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(AppColors.card)
                            .verticalScroll(rememberScrollState())
                            .padding(10.dp)
                    ) {
                        Text(rel.notes, fontSize = 12.sp, color = AppColors.text, lineHeight = 16.sp)
                    }
                }

                if (Updater.downloading) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                        color = AppColors.cyan,
                        trackColor = AppColors.inset
                    )
                }

                if (Updater.message.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        Updater.message,
                        color = if (Updater.message.contains("failed", ignoreCase = true) || Updater.message.contains("mismatch", ignoreCase = true)) AppColors.red else AppColors.dim,
                        fontSize = 12.sp
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { Updater.install(ctx) },
                enabled = !Updater.downloading
            ) {
                Text(
                    when {
                        Updater.downloading -> "Downloading…"
                        Updater.downloadedFile != null -> "Install"
                        else -> "Update now"
                    },
                    color = if (Updater.downloading) AppColors.dim else AppColors.cyan,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            if (!Updater.downloading) {
                TextButton(onClick = { Updater.later() }) {
                    Text("Later", color = AppColors.dim)
                }
            }
        },
    )
}
