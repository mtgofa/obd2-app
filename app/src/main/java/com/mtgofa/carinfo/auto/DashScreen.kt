package com.mtgofa.carinfo.auto

import android.graphics.Rect
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.mtgofa.carinfo.Settings
import com.mtgofa.carinfo.obd.LinkState
import com.mtgofa.carinfo.obd.Obd
import com.mtgofa.carinfo.obd.Target
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The dashboard on the car's screen: gauges drawn straight onto the Android Auto surface.
 * It's a single screen, laid out by the user on the phone's Android Auto page ([AutoLayout]).
 */
class DashScreen(carContext: CarContext) : Screen(carContext), DefaultLifecycleObserver {
    private val renderer = DashRenderer(carContext)
    private var drawJob: Job? = null

    private val surfaceCallback = object : SurfaceCallback {
        override fun onSurfaceAvailable(container: SurfaceContainer) = renderer.attach(container)
        override fun onSurfaceDestroyed(container: SurfaceContainer) = renderer.detach()
        override fun onVisibleAreaChanged(visibleArea: Rect) = renderer.setVisibleArea(visibleArea)
        override fun onStableAreaChanged(stableArea: Rect) = Unit
    }

    init {
        lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(surfaceCallback)
    }

    override fun onStart(owner: LifecycleOwner) {
        var lastState: LinkState? = null
        var polled: List<Widget>? = null
        drawJob = lifecycleScope.launch {
            while (isActive) {
                // Ask the car only for what's on screen; follow edits made on the phone live.
                val wanted = AutoLayout.widgets
                if (wanted != polled) {
                    polled = wanted
                    Obd.subscribe(OWNER, fast = AutoLayout.fastPids(), slow = AutoLayout.slowPids())
                }
                renderer.draw()
                // Rebuild the action strip only when its label would change.
                val state = Obd.link.value.state
                if (state != lastState) {
                    lastState = state
                    invalidate()
                }
                delay(80)
            }
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        drawJob?.cancel()
        Obd.unsubscribe(OWNER)
    }

    override fun onGetTemplate(): Template {
        val connected = Obd.link.value.state == LinkState.Connected
        val action = Action.Builder()
            .setTitle(if (connected) "Connected" else "Connect")
            .setOnClickListener {
                if (!connected) Target.decode(Settings.lastTarget)?.let { Obd.connect(it) }
            }
            .build()
        return NavigationTemplate.Builder()
            .setActionStrip(ActionStrip.Builder().addAction(action).build())
            .build()
    }

    private companion object {
        const val OWNER = "android-auto"
    }
}
