package net.xrxss15

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

class ActivityStatusCheckReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_START = "net.xrxss15.START_GARMIN_LISTENER"
        const val ACTION_STOP = "net.xrxss15.STOP_GARMIN_LISTENER"
        const val ACTION_MESSAGE = "net.xrxss15.GARMIN_MESSAGE"
        const val EXTRA_MESSAGE = "message"
        private const val UNIQUE_WORK = "garmin_listener"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_START -> startListener(context)
            ACTION_STOP -> stopListener(context)
        }
    }
    
    private fun startListener(context: Context) {
        val workRequest = OneTimeWorkRequestBuilder<ConnectIQQueryWorker>().build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.REPLACE, workRequest)
    }
    
    private fun stopListener(context: Context) {
        WorkManager.getInstance(context.applicationContext)
            .cancelUniqueWork(UNIQUE_WORK)
        sendMessage(context, "terminating|Stopped by user")
    }
    
    private fun sendMessage(context: Context, message: String) {
        val intent = Intent(ACTION_MESSAGE).apply {
            putExtra(EXTRA_MESSAGE, message)
        }
        context.sendBroadcast(intent)
    }
}