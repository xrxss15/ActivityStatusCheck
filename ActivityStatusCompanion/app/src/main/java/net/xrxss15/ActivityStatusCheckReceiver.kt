package net.xrxss15

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/**
 * TASKER INTEGRATION RECEIVER WITH ENHANCED INTENT LOGGING
 * 
 * Receives broadcast intent from Tasker to trigger companion app functionality.
 * All intent actions are logged for debugging and monitoring purposes.
 */
class ActivityStatusCheckReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ActStatusReceiver"
        
        // INTENT SPECIFICATIONS WITH CLEAR LOGGING:
        
        // 1. TRIGGER INTENT (Tasker → Companion)
        const val ACTION_TRIGGER = "net.xrxss15.ACTIVITY_STATUS_TRIGGER"
        
        // 2. DEVICE LIST INTENT (Companion → Tasker)  
        const val ACTION_DEVICE_LIST = "net.xrxss15.DEVICE_LIST"
        const val EXTRA_DEVICES = "devices"  // Format: "device1/device2/device3"
        
        // 3. RESPONSE INTENT (Companion → Tasker)
        const val ACTION_CIQ_RESPONSE = "net.xrxss15.CIQ_RESPONSE"
        const val EXTRA_RESPONSE = "response"     // CIQ app response payload
        const val EXTRA_TIMESTAMP = "timestamp"  // Response timestamp
        const val EXTRA_DEVICE = "device"        // Responding device name
        
        // Internal WorkManager identifier
        private const val UNIQUE_WORK = "ciq_query_work"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: "null"
        
        // Log all received intents for debugging
        Log.i(TAG, "📨 INTENT RECEIVED: action='$action'")
        
        if (action != ACTION_TRIGGER) {
            Log.w(TAG, "⚠️ INTENT IGNORED: Expected '$ACTION_TRIGGER', got '$action'")
            return
        }

        Log.i(TAG, "✅ TASKER TRIGGER INTENT ACCEPTED")
        Log.i(TAG, "🚀 Starting ConnectIQ background worker for device discovery and query")
        
        // Use WorkManager to handle background processing
        val workRequest = OneTimeWorkRequestBuilder<ConnectIQQueryWorker>().build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.REPLACE, workRequest)
            
        Log.i(TAG, "📋 WorkManager job enqueued: $UNIQUE_WORK")
    }
}