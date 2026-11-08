package com.phonedoctor

import android.app.Application
import rikka.shizuku.Shizuku

class App : Application() {

    override fun onCreate() {
        super.onCreate()

        // Держим подписки, чтобы UI мог узнать о запуске/остановке Shizuku.
        Shizuku.addBinderReceivedListenerSticky { }
        Shizuku.addBinderDeadListener { }
    }
}
