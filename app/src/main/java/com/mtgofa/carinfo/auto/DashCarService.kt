package com.mtgofa.carinfo.auto

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import com.mtgofa.carinfo.obd.Obd

/**
 * Android Auto entry point. Declared as a navigation app because that's the only category
 * Android Auto gives a free drawing surface, which the gauge dashboard needs. That keeps it out
 * of the Play Store, so it's meant for sideloading with Android Auto's "Unknown sources" on.
 */
class DashCarService : CarAppService() {
    // Sideloaded build: accept any Android Auto host (phone projection or the desktop head unit).
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen {
            // The car may start us before the phone UI was ever opened: reconnect on our own.
            Obd.autoConnect(carContext)
            return DashScreen(carContext)
        }
    }
}
