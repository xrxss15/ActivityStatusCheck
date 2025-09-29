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
        private const val TAG = "ActStatus"

        const val ACTION_TRIGGER = "net.xrxss15.ACTIVITY_STATUS_CHECK"
        const val ACTION_RESULT  = "net.xrxss15.ACTIVITY_STATUS_RESULT"

        const val EXTRA_PAYLOAD      = "payload"
        const val EXTRA_DEBUG        = "debug"
        const val EXTRA_SUCCESS      = "success"
        const val EXTRA_DEVICE_COUNT = "deviceCount"

        private const val UNIQUE_WORK = "ciq_query_work"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TRIGGER) {
            Log.d(TAG, "[RECEIVER] Ignoring action: ${intent.action}")
            return
        }
        Log.i(TAG, "[RECEIVER] Triggered, enqueuing WorkManager job")
        val req = OneTimeWorkRequestBuilder<ConnectIQQueryWorker>().build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.REPLACE, req)
    }
}