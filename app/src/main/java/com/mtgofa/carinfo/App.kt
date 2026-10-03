package com.mtgofa.carinfo

import android.app.Application
import com.mtgofa.carinfo.obd.Obd

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Settings.init(this)
        Obd.init(this)
    }
}
