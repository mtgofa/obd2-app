package com.mtgofa.carinfo

import android.app.Application
import com.mtgofa.carinfo.obd.Obd
import com.mtgofa.carinfo.obd.KnockMonitor
import com.mtgofa.carinfo.obd.TripRecorder

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Settings.init(this)
        Obd.init(this)
        TripRecorder.init(this)
        KnockMonitor.start()
    }
}
