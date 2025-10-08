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
        
        // INTERNAL INTENTS - Used by MainActivity
        const val ACTION_START = "net.xrxss15.internal.START"
        const val ACTION_STOP = "net.xrxss15.internal.STOP"
        
        // PUBLIC INTENT - For Tasker to terminate app completely
        const val ACTION_TERMINATE = "net.xrxss15.GARMIN_ACTIVITY_LISTENER_TERMINATE"
        
        // OUTBOUND BROADCAST - Sent to Tasker when events occur
        const val ACTION_EVENT = "net.xrxss15.GARMIN_ACTIVITY_LISTENER_EVENT"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "Received intent: ${intent.action}")
        
        when (intent.action) {
            ACTION_START -> {
                Log.i(TAG, "Starting listener (internal)")
                startListener(context)
            }
            
            ACTION_STOP -> {
                Log.i(TAG, "Stopping listener (internal)")
                stopListener(context)
            }
            
            ACTION_TERMINATE -> {
                Log.i(TAG, "Terminating app completely (Tasker)")
                terminateApp(context)
            }
        }
    }

    private fun startListener(context: Context) {
        Log.i(TAG, "Starting Garmin listener...")
        
        val workRequest = OneTimeWorkRequestBuilder<ConnectIQQueryWorker>().build()
        
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(
                "garmin_listener",
                ExistingWorkPolicy.KEEP,
                workRequest
            )
        
        Log.i(TAG, "Listener started")
    }

    private fun stopListener(context: Context) {
        Log.i(TAG, "Stopping Garmin listener...")
        
        WorkManager.getInstance(context.applicationContext)
            .cancelUniqueWork("garmin_listener")
        
        Log.i(TAG, "Listener stopped")
    }

    private fun terminateApp(context: Context) {
        Log.i(TAG, "Terminating entire app...")
        
        // Stop worker
        stopListener(context)
        
        // Shutdown SDK
        ConnectIQService.getInstance().shutdown()
        
        // Kill app process
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}