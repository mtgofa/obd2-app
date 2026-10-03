package com.mtgofa.carinfo

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import com.mtgofa.carinfo.ui.ConnectionBar
import com.mtgofa.carinfo.ui.DarkSection
import com.mtgofa.carinfo.ui.UpdatePrompt
import androidx.compose.runtime.remember
import com.mtgofa.carinfo.obd.LinkState
import com.mtgofa.carinfo.obd.Obd
import com.mtgofa.carinfo.obd.Target
import com.mtgofa.carinfo.ui.AppTheme
import com.mtgofa.carinfo.ui.ConnectScreen
import com.mtgofa.carinfo.ui.DashboardScreen
import com.mtgofa.carinfo.ui.DiagnosisScreen
import com.mtgofa.carinfo.ui.FuelScreen
import com.mtgofa.carinfo.ui.HomeScreen
import com.mtgofa.carinfo.ui.HudScreen
import com.mtgofa.carinfo.ui.InfoScreen
import com.mtgofa.carinfo.ui.MonitorScreen
import com.mtgofa.carinfo.ui.PerformanceScreen
import com.mtgofa.carinfo.ui.Route
import com.mtgofa.carinfo.ui.SettingsScreen
import com.mtgofa.carinfo.ui.TemperaturesScreen
import com.mtgofa.carinfo.ui.TerminalScreen
import com.mtgofa.carinfo.ui.hasBtPermissions

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        if (savedInstanceState == null) {
            autoConnect()
            Updater.check(auto = true)
        }

        setContent {
            AppTheme {
                val stack = remember { mutableStateListOf(Route.Home) }
                val go: (Route) -> Unit = { stack.add(it) }
                val back: () -> Unit = { if (stack.size > 1) stack.removeAt(stack.lastIndex) else finish() }
                val connect = { go(Route.Connect) }
                BackHandler(stack.size > 1) { back() }

                UpdatePrompt()
                AnimatedContent(stack.last(), transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "nav") { route ->
                    // Only the dashboard and HUD use the dark palette; the rest of the app is light.
                    val dark = route == Route.Dashboard || route == Route.Hud
                    val screen: @Composable () -> Unit = { Screen(route, go, back, connect) }
                    val body: @Composable () -> Unit = {
                        Column(Modifier.fillMaxSize()) {
                            Box(Modifier.weight(1f)) { screen() }
                            if (route != Route.Hud) ConnectionBar { if (route != Route.Connect) go(Route.Connect) }
                        }
                    }
                    if (dark) DarkSection(body) else body()
                }
            }
        }
    }

    @Composable
    private fun Screen(route: Route, go: (Route) -> Unit, back: () -> Unit, connect: () -> Unit) {
                    when (route) {
                        Route.Home -> HomeScreen(go)
                        Route.Connect -> ConnectScreen(back)
                        Route.Dashboard -> DashboardScreen(back)
                        Route.Temps -> TemperaturesScreen(back)
                        Route.Monitor -> MonitorScreen(back, connect)
                        Route.Diagnosis -> DiagnosisScreen(back, connect)
                        Route.Hud -> HudScreen(back)
                        Route.Fuel -> FuelScreen(back, connect)
                        Route.Performance -> PerformanceScreen(back, connect)
                        Route.Info -> InfoScreen(back, connect)
                        Route.Terminal -> TerminalScreen(back)
                        Route.Settings -> SettingsScreen(back)
                    }
    }

    /** Reconnect to the last adapter on launch so the dashboard is live without any taps. */
    private fun autoConnect() {
        if (!Settings.autoConnect || Obd.link.value.state != LinkState.Disconnected) return
        val target = Target.decode(Settings.lastTarget) ?: return
        if (target is Target.Bt && !hasBtPermissions(this)) return
        Obd.connect(target)
    }
}
