package com.utemaster.dealtexter

import android.content.Context
import com.utemaster.dealtexter.core.DealProcessor
import com.utemaster.dealtexter.data.AppDatabase
import com.utemaster.dealtexter.data.RoomTextStore
import com.utemaster.dealtexter.data.SettingsRepository
import com.utemaster.dealtexter.hubspot.HubSpotClient
import com.utemaster.dealtexter.sms.AndroidSmsGateway

/** Tiny manual dependency wiring. */
object Graph {
    fun settings(context: Context) = SettingsRepository.get(context)

    fun hubSpot(context: Context) = HubSpotClient { settings(context).token() }

    fun db(context: Context) = AppDatabase.get(context)

    fun processor(context: Context): DealProcessor {
        val app = context.applicationContext
        return DealProcessor(
            crm = hubSpot(app),
            sms = AndroidSmsGateway(app),
            store = RoomTextStore(db(app).dao()),
            notifier = AppNotifier(app),
        )
    }
}
