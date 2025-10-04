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
            // 1. Explicit broadcast for MainActivity
            val explicitIntent = Intent(ACTION_MESSAGE).apply {
                putExtra(EXTRA_MESSAGE, message)
                setPackage(context.packageName)
            }
            context.sendBroadcast(explicitIntent)
            
            // 2. Implicit broadcast for Tasker with standard extra name
            val implicitIntent = Intent(ACTION_MESSAGE).apply {
                // Use standard "message" key that Tasker can access via %message
                putExtra("message", message)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            context.sendBroadcast(implicitIntent)
            
            Log.d(TAG, "Dual broadcast sent: $message")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send message", e)
        }
    }
}