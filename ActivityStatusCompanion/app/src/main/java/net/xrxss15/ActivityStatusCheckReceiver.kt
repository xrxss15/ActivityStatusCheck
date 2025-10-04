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
        Log.i(TAG, "========== BROADCAST RECEIVED ==========")
        Log.i(TAG, "Action: ${intent.action}")
        
        when (intent.action) {
            ACTION_START -> {
                Log.i(TAG, "Starting listener")
                startListener(context)
            }
            ACTION_STOP -> {
                Log.i(TAG, "Stopping listener")
                stopListener(context)
            }
            else -> {
                Log.w(TAG, "Unknown action: ${intent.action}")
            }
        }
    }
    
    private fun startListener(context: Context) {
        try {
            Log.d(TAG, "Creating worker request")
            val workRequest = OneTimeWorkRequestBuilder<ConnectIQQueryWorker>().build()
            
            Log.d(TAG, "Enqueuing worker")
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.REPLACE, workRequest)
            
            Log.i(TAG, "Worker enqueued successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start worker", e)
        }
    }
    
    private fun stopListener(context: Context) {
        try {
            Log.d(TAG, "Cancelling worker")
            WorkManager.getInstance(context.applicationContext)
                .cancelUniqueWork(UNIQUE_WORK)
            
            sendMessage(context, "terminating|Stopped by user")
            Log.i(TAG, "Worker cancelled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop worker", e)
        }
    }
    
    private fun sendMessage(context: Context, message: String) {
        try {
            val intent = Intent(ACTION_MESSAGE).apply {
                putExtra(EXTRA_MESSAGE, message)
            }
            context.sendBroadcast(intent)
            Log.d(TAG, "Message broadcast sent: $message")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send message", e)
        }
    }
}