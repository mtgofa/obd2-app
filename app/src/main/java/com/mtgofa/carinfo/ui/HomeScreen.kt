package com.mtgofa.carinfo.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.DirectionsCar
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.Tv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mtgofa.carinfo.Fmt
import com.mtgofa.carinfo.obd.LinkState
import com.mtgofa.carinfo.obd.Obd
import com.mtgofa.carinfo.obd.PidUnit
import com.mtgofa.carinfo.obd.Pids

enum class Route { Home, Connect, Dashboard, Temps, Monitor, Diagnosis, Hud, Fuel, Performance, Info, Terminal, Settings }

private class Feature(val title: String, val icon: ImageVector, val route: Route, val color: (Palette) -> Color)

private val features = listOf(
    Feature("Dashboard", Icons.Rounded.Speed, Route.Dashboard, { it.cyan }),
    Feature("Monitoring", Icons.Rounded.MonitorHeart, Route.Monitor, { it.green }),
    Feature("Diagnosis", Icons.Rounded.Build, Route.Diagnosis, { it.amber }),
    Feature("Temperatures", Icons.Rounded.Thermostat, Route.Temps, { it.red }),
    Feature("HUD", Icons.Rounded.Tv, Route.Hud, { it.magenta }),
    Feature("Fuel", Icons.Rounded.LocalGasStation, Route.Fuel, { it.green }),
    Feature("Performance", Icons.Rounded.Timer, Route.Performance, { it.violet }),
    Feature("Vehicle info", Icons.Rounded.DirectionsCar, Route.Info, { it.blue }),
    Feature("Terminal", Icons.Rounded.Terminal, Route.Terminal, { it.dim }),
)

@Composable
fun HomeScreen(go: (Route) -> Unit) {
    val link by Obd.link.collectAsState()
    val vehicle by Obd.vehicle.collectAsState()
    val values by Obd.values.collectAsState()
    Subscribe("home", fast = listOf(0x0D, 0x0C), slow = listOf(0x05))

    AppBackground {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)
        ) {
            Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Logo() }
                Box(
                    Modifier.size(40.dp).clip(CircleShape).clickable { go(Route.Settings) },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Settings, "Settings", tint = AppColors.text) }
            }

            // Car card: detected vehicle + connect button, then three live numbers.
            AppCard(Modifier.fillMaxWidth(), onClick = { go(Route.Connect) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BrandBadge(vehicle?.make)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (link.state == LinkState.Connected) vehicle?.title ?: "Vehicle" else "No vehicle connected",
                            color = AppColors.text, fontFamily = Sora, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1,
                        )
                        Text(
                            when (link.state) {
                                LinkState.Connected -> link.via
                                LinkState.Error -> link.message
                                LinkState.Disconnected -> "Tap to connect your ELM327 adapter"
                                else -> link.message
                            },
                            color = if (link.state == LinkState.Error) AppColors.red else AppColors.dim, fontSize = 12.sp, maxLines = 2,
                        )
                    }
                    StatusPill(link.state)
                }
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    QuickValue("Speed", Fmt.value(PidUnit.SPEED, values[0x0D]), Fmt.unit(PidUnit.SPEED), AppColors.cyan, Modifier.weight(1f))
                    QuickValue("RPM", Fmt.value(PidUnit.RPM, values[0x0C]), "rpm", AppColors.magenta, Modifier.weight(1f))
                    QuickValue("Coolant", Fmt.value(PidUnit.TEMP, values[0x05]), Fmt.unit(PidUnit.TEMP), tempColor(0x05, values[0x05]), Modifier.weight(1f))
                }
            }

            Spacer(Modifier.height(16.dp))
            features.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { f -> FeatureTile(f, Modifier.weight(1f)) { go(f.route) } }
                }
                Spacer(Modifier.height(12.dp))
            }
            Text(
                "All features unlocked · No account · No ads",
                color = AppColors.dim.copy(alpha = 0.7f), fontSize = 11.sp, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun FeatureTile(f: Feature, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier
            .aspectRatio(0.95f)
            .clip(shape)
            .background(AppColors.card)
            .border(1.dp, AppColors.cardBorder, shape)
            .clickable { onClick() }
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(f.icon, null, tint = f.color(LocalPalette.current), modifier = Modifier.size(36.dp))
        Spacer(Modifier.height(8.dp))
        Text(
            f.title, color = AppColors.text, fontSize = 13.sp, fontFamily = Sora, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center, maxLines = 1,
        )
    }
}

@Composable
private fun QuickValue(label: String, value: String, unit: String, color: Color, modifier: Modifier) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier.clip(shape).background(AppColors.inset).border(1.dp, color.copy(alpha = 0.35f), shape)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = AppColors.dim, fontSize = 11.sp, fontFamily = Sora)
        GlowText(value, color, 26.sp)
        Text(unit, color = AppColors.dim, fontSize = 10.sp)
    }
}

@Composable
fun BrandBadge(make: String?) {
    val initials = make?.split(" ", "(", "/")?.firstOrNull { it.isNotBlank() }?.take(2)?.uppercase() ?: "?"
    Box(
        Modifier.size(42.dp).clip(CircleShape).background(AppColors.inset).border(1.5.dp, AppColors.cyan, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initials, color = AppColors.cyan, fontFamily = Sora, fontWeight = FontWeight.Bold, fontSize = 15.sp,
            style = TextStyle(shadow = Shadow(AppColors.cyan, blurRadius = 12f)),
        )
    }
}

@Composable
fun StatusPill(state: LinkState) {
    val (text, color) = when (state) {
        LinkState.Connected -> "Connected" to AppColors.green
        LinkState.Connecting, LinkState.Initializing -> "Connecting" to AppColors.amber
        LinkState.Error -> "Retry" to AppColors.red
        LinkState.Disconnected -> "Connect" to AppColors.cyan
    }
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.15f)).border(1.dp, color.copy(alpha = 0.7f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state == LinkState.Connecting || state == LinkState.Initializing) {
            CircularProgressIndicator(Modifier.size(12.dp), color = color, strokeWidth = 2.dp)
            Spacer(Modifier.width(6.dp))
        } else {
            Box(Modifier.size(7.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = color, fontSize = 12.sp, fontFamily = Sora, fontWeight = FontWeight.SemiBold)
    }
}

/** Shows the name for a PID, used by several screens. */
fun pidLabel(pid: Int): String = Pids[pid]?.short ?: "PID %02X".format(pid)
