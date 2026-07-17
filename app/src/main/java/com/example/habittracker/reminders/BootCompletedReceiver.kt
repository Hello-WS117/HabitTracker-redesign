package com.example.habittracker.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.habittracker.data.settings.AppSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BootReminderRescheduleHandler.isSupportedAction(intent.action)) {
            return
        }
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                BootReminderRescheduleHandler.handle(context.applicationContext, intent.action)
            } finally {
                pendingResult.finish()
            }
        }
    }
}

object BootReminderRescheduleHandler {
    fun isSupportedAction(action: String?): Boolean {
        return action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == Intent.ACTION_TIME_CHANGED ||
            action == Intent.ACTION_TIMEZONE_CHANGED
    }

    suspend fun handle(context: Context, action: String?): Boolean {
        if (!isSupportedAction(action)) return false
        val appContext = context.applicationContext
        val settings = AppSettingsRepository(appContext).settings.first()
        return ReminderScheduler(appContext).schedule(settings)
    }
}
