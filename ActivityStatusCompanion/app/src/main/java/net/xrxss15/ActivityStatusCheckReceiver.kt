package net.xrxss15

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/**
 * Broadcast receiver handling app control intents.
 * Manages worker lifecycle and app termination.
 */
class ActivityStatusCheckReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_START_BACKGROUND = "net.xrxss15.internal.START"
        const val ACTION_OPEN_GUI = "net.xrxss15.OPEN_GUI"
        const val ACTION_CLOSE_GUI = "net.xrxss15.CLOSE_GUI"
        const val ACTION_TERMINATE = "net.xrxss15.TERMINATE"
        
        const val ACTION_EVENT = "net.xrxss15.GARMIN_ACTIVITY_LISTENER_EVENT"
        
        private const val TAG = "GarminActivityListener.Receiver"
        private const val WORKER_NAME = "garmin_listener"

        /**
         * Terminates the entire app including worker and UI.
         */
        fun terminateApp(context: Context) {
            Log.i(TAG, "Terminating app")
            
            WorkManager.getInstance(context).cancelUniqueWork(WORKER_NAME)
            ConnectIQService.resetInstance()
            
            val closeIntent = Intent(MainActivity.ACTION_CLOSE_GUI).apply {
                setPackage(context.packageName)
            }
            context.sendBroadcast(closeIntent)
            
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        when (intent.action) {
            ACTION_START_BACKGROUND -> {
                Log.i(TAG, "START_BACKGROUND received (starting worker)")
                startWorker(context)
            }
            
            ACTION_OPEN_GUI -> {
                Log.i(TAG, "OPEN_GUI received")
                val openIntent = Intent(context, MainActivity::class.java).apply {
                    action = MainActivity.ACTION_OPEN_GUI
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(openIntent)
            }
            
            ACTION_CLOSE_GUI -> {
                Log.i(TAG, "CLOSE_GUI received")
                val closeIntent = Intent(MainActivity.ACTION_CLOSE_GUI).apply {
                    setPackage(context.packageName)
                }
                context.sendBroadcast(closeIntent)
            }
            
            ACTION_TERMINATE -> {
                Log.i(TAG, "TERMINATE received")
                terminateApp(context)
            }
        }
    }

    private fun startWorker(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(false)
            .setRequiresCharging(false)
            .setRequiresDeviceIdle(false)
            .build()
        
        val workRequest = OneTimeWorkRequestBuilder<ConnectIQQueryWorker>()
            .setConstraints(constraints)
            .build()
        
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORKER_NAME,
            ExistingWorkPolicy.KEEP,
            workRequest
        )
    }
}