package com.utemaster.dealtexter

import android.app.Application
import com.utemaster.dealtexter.work.CheckScheduler

class TexterApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppNotifier.createChannels(this)
        if (Graph.settings(this).state.value.enabled) CheckScheduler.ensureScheduled(this)
    }
}
