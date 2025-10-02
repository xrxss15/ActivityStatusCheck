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

class ActivityStatusCheckReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ActStatusReceiver"
        
        const val ACTION_TRIGGER = "net.xrxss15.ACTIVITY_STATUS_TRIGGER"
        const val ACTION_RESPONSE = "net.xrxss15.ACTIVITY_STATUS_RESPONSE"
        
        const val EXTRA_STAGE = "stage"
        const val EXTRA_SUCCESS = "success"
        const val EXTRA_PAYLOAD = "payload"
        const val EXTRA_TIMESTAMP = "timestamp"
        const val EXTRA_TERMINATED = "terminated"
        const val EXTRA_HEADLESS = "headless"
        
        const val STAGE_ERROR = "error"
        const val STAGE_DEVICES_FOUND = "devices_found"
        const val STAGE_NO_DEVICES = "no_devices"
        const val STAGE_MESSAGE_SENT = "message_sent"
        const val STAGE_MESSAGE_FAILED = "message_failed"
        const val STAGE_RESPONSE_RECEIVED = "response_received"
        const val STAGE_TIMEOUT = "timeout"
        
        private const val UNIQUE_WORK = "ciq_listener_work"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: "null"
        
        Log.i(TAG, "Intent received: $action")
        
        if (action != ACTION_TRIGGER) {
            Log.w(TAG, "Ignoring action: $action")
            return
        }

        Log.i(TAG, "Tasker trigger accepted - starting headless worker")
        
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
            .setRequiresBatteryNotLow(false)
            .setRequiresDeviceIdle(false)
            .setRequiresCharging(false)
            .build()
        
        val workRequest = OneTimeWorkRequestBuilder<ConnectIQQueryWorker>()
            .setConstraints(constraints)
            .build()
            
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.REPLACE, workRequest)
            
        Log.i(TAG, "Worker enqueued: $UNIQUE_WORK")
    }
}