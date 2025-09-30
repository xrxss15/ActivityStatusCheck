package net.xrxss15

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Constraints
import androidx.work.NetworkType

/**
 * Tasker Integration Broadcast Receiver - Headless Mode Operation
 * 
 * This receiver handles trigger intents from Tasker and initiates headless background processing.
 * The app operates in two distinct modes:
 * 
 * - **Headless Mode**: When triggered by Tasker intent - no GUI, immediate background execution
 * - **Debug Mode**: When launched via MainActivity - full GUI with logging and testing
 * 
 * @see ConnectIQQueryWorker for the background processing implementation
 * @see MainActivity for debug mode UI
 * 
 * Architecture:
 * - Receives broadcast intent from Tasker
 * - Enqueues WorkManager job for background processing
 * - No activity is launched - pure headless operation
 * - Complete app termination after reporting results via System.exit(0)
 * 
 * Battery Efficiency:
 * - Uses WorkManager with battery-aware constraints
 * - No unnecessary background services or polling
 * - Immediate termination after completion
 */
class ActivityStatusCheckReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ActStatusReceiver"
        
        // INTENT ACTION SPECIFICATIONS:
        
        /**
         * Trigger intent action sent by Tasker to initiate headless operation.
         * This intent starts the entire workflow without launching any Activity.
         */
        const val ACTION_TRIGGER = "net.xrxss15.ACTIVITY_STATUS_TRIGGER"
        
        /**
         * Response intent action sent back to Tasker with consolidated results.
         * All stages of processing report back via this single intent action.
         */
        const val ACTION_RESPONSE = "net.xrxss15.ACTIVITY_STATUS_RESPONSE"
        
        // INTENT EXTRAS:
        const val EXTRA_STAGE = "stage"           // Current processing stage
        const val EXTRA_SUCCESS = "success"       // Operation success boolean
        const val EXTRA_PAYLOAD = "payload"       // JSON response data
        const val EXTRA_TIMESTAMP = "timestamp"   // Response timestamp
        const val EXTRA_TERMINATED = "terminated" // Whether operation terminated
        const val EXTRA_HEADLESS = "headless"     // Headless mode indicator
        
        // RESPONSE STAGES:
        const val STAGE_ERROR = "error"
        const val STAGE_DEVICES_FOUND = "devices_found"
        const val STAGE_NO_DEVICES = "no_devices"
        const val STAGE_MESSAGE_SENT = "message_sent"
        const val STAGE_MESSAGE_FAILED = "message_failed"
        const val STAGE_RESPONSE_RECEIVED = "response_received"
        const val STAGE_TIMEOUT = "timeout"
        
        // Internal WorkManager identifier
        private const val UNIQUE_WORK = "ciq_query_work_headless"
    }

    /**
     * Receives broadcast intents and initiates headless background processing.
     * 
     * This method validates the intent action and enqueues a WorkManager job for
     * background processing. No UI components are created - the app operates entirely
     * in headless mode when triggered via this receiver.
     * 
     * @param context Application context
     * @param intent Broadcast intent (must have ACTION_TRIGGER action)
     */
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: "null"
        
        // Log received intent for debugging
        Log.i(TAG, "📨 INTENT RECEIVED: action='$action'")
        
        // Validate intent action
        if (action != ACTION_TRIGGER) {
            Log.w(TAG, "⚠️ INTENT IGNORED: Expected '$ACTION_TRIGGER', got '$action'")
            return
        }

        Log.i(TAG, "✅ TASKER TRIGGER ACCEPTED - Starting HEADLESS mode")
        Log.i(TAG, "🚀 No GUI will be launched - pure background operation")
        
        // Create WorkManager request with battery-efficient constraints
        // Note: We don't check battery level, just optimize for minimal resource usage
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)  // No network needed
            .setRequiresBatteryNotLow(false)                   // Don't check battery level per requirements
            .setRequiresDeviceIdle(false)                      // Allow during active use
            .setRequiresCharging(false)                        // Don't require charging
            .build()
        
        val workRequest = OneTimeWorkRequestBuilder<ConnectIQQueryWorker>()
            .setConstraints(constraints)
            .build()
            
        // Enqueue work with REPLACE policy to ensure only one instance runs
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.REPLACE, workRequest)
            
        Log.i(TAG, "📋 Headless WorkManager job enqueued: $UNIQUE_WORK")
        Log.i(TAG, "App will terminate completely after operation completes")
    }
}