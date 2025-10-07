package net.xrxss15

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

class ActivityStatusCheckReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "GarminActivityListener.Receiver"
        const val ACTION_START = "net.xrxss15.START_GARMIN_LISTENER"
        const val ACTION_STOP = "net.xrxss15.STOP_GARMIN_LISTENER"
        const val ACTION_MESSAGE = "net.xrxss15.GARMIN_MESSAGE"
        const val EXTRA_MESSAGE = "message"
        private const val UNIQUE_WORK_NAME = "garmin_listener"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_START -> {
                Log.i(TAG, "Starting listener")
                val workRequest = OneTimeWorkRequestBuilder<ConnectIQQueryWorker>().build()
                WorkManager.getInstance(context.applicationContext)
                    .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, workRequest)
            }
            ACTION_STOP -> {
                Log.i(TAG, "Stopping listener")
                WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_WORK_NAME)
            }
        }
    }
}