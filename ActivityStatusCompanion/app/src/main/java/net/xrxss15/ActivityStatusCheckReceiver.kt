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
        private const val UNIQUE_WORK = "garmin_listener"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_START -> {
                Log.i(TAG, "Starting listener")
                startListener(context)
            }
            ACTION_STOP -> {
                Log.i(TAG, "Stopping listener")
                stopListener(context)
            }
        }
    }
    
    private fun startListener(context: Context) {
        try {
            val workRequest = OneTimeWorkRequestBuilder<ConnectIQQueryWorker>().build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.REPLACE, workRequest)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start worker", e)
        }
    }
    
    private fun stopListener(context: Context) {
        try {
            WorkManager.getInstance(context.applicationContext)
                .cancelUniqueWork(UNIQUE_WORK)
            sendMessage(context, "terminating|Stopped by user")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop worker", e)
        }
    }
    
    private fun sendMessage(context: Context, message: String) {
        try {
            val intent = Intent(ACTION_MESSAGE).apply {
                putExtra(EXTRA_MESSAGE, message)
                setPackage("net.xrxss15")
            }
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send message", e)
        }
    }
}