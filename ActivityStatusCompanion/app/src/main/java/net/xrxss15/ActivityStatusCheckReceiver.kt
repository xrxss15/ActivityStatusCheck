package net.xrxss15

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class ActivityStatusCheckReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ActStatus"

        const val ACTION_TRIGGER = "net.xrxss15.ACTIVITY_STATUS_CHECK"
        const val ACTION_RESULT = "net.xrxss15.ACTIVITY_STATUS_RESULT"

        const val EXTRA_PAYLOAD = "payload"
        const val EXTRA_DEBUG = "debug"
        const val EXTRA_SUCCESS = "success"
        const val EXTRA_DEVICE_COUNT = "deviceCount"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TRIGGER) {
            Log.d(TAG, "[RECEIVER] Ignoring action: ${intent.action}")
            return
        }
        Log.i(TAG, "[RECEIVER] Triggered by automation")
        val pending = goAsync()
        Thread {
            val svc = ConnectIQService.getInstance()
            val result = svc.queryActivityStatus(
                context = context,
                selected = null,
                showUiIfInitNeeded = false
            )
            val out = Intent(ACTION_RESULT).apply {
                putExtra(EXTRA_SUCCESS, result.success)
                putExtra(EXTRA_PAYLOAD, result.payload)
                putExtra(EXTRA_DEBUG, result.debug)
                putExtra(EXTRA_DEVICE_COUNT, result.connectedRealDevices)
            }
            context.sendBroadcast(out)
            pending.finish()
        }.start()
    }
}