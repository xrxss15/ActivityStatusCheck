package net.xrxss15

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/**
 * Activity Status Check Receiver
 * Handles START and STOP intents for the Garmin message listener
 */
class ActivityStatusCheckReceiver : BroadcastReceiver() {

    companion object {
        // Control intents
        const val ACTION_START = "net.xrxss15.START_GARMIN_LISTENER"
        const val ACTION_STOP = "net.xrxss15.STOP_GARMIN_LISTENER"
        
        // Response intent - single action for all messages
        const val ACTION_MESSAGE = "net.xrxss15.GARMIN_MESSAGE"
        
        // Message payload key
        const val EXTRA_MESSAGE = "message"
        
        // Unique work identifier
        private const val UNIQUE_WORK = "garmin_listener"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        
        when (action) {
            ACTION_START -> {
                startListener(context)
            }
            ACTION_STOP -> {
                stopListener(context)
            }
        }
    }
    
    private fun startListener(context: Context) {
        // REPLACE ensures only one instance runs - terminates any existing instance
        val workRequest = OneTimeWorkRequestBuilder<ConnectIQQueryWorker>().build()
        
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.REPLACE, workRequest)
    }
    
    private fun stopListener(context: Context) {
        // Cancel all work and terminate
        WorkManager.getInstance(context.applicationContext)
            .cancelUniqueWork(UNIQUE_WORK)
        
        // Also send termination message
        sendMessage(context, "terminating|Stopped by user")
    }
    
    private fun sendMessage(context: Context, message: String) {
        val intent = Intent(ACTION_MESSAGE).apply {
            putExtra(EXTRA_MESSAGE, message)
        }
        context.sendBroadcast(intent)
    }
}