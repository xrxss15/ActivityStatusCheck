package net.xrxss15

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class ActivityStatusCheckReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ActStatusReceiver"
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
        
        Log.i(TAG, "[RECEIVER] Triggered by Tasker")
        val pendingResult = goAsync()
        val connectIQService = ConnectIQService.getInstance()
        
        // CRITICAL: Background queries should try showUi=false first
        connectIQService.queryActivityStatus(
            context = context, 
            tag = TAG, 
            showUi = false, // Service handles UI fallback automatically
            callback = object : ConnectIQService.StatusQueryCallback {
                override fun onSuccess(payload: String, debug: String) {
                    Log.i(TAG, "[RECEIVER] Query SUCCESS: $payload")
                    sendResult(context, true, payload, debug)
                    pendingResult.finish()
                }

                override fun onFailure(error: String, debug: String) {
                    Log.e(TAG, "[RECEIVER] Query FAILED: $error")
                    sendResult(context, false, error, debug)
                    pendingResult.finish()
                }

                override fun onLog(tag: String, message: String, level: ConnectIQService.LogLevel) {
                    when (level) {
                        ConnectIQService.LogLevel.ERROR -> Log.e(tag, message)
                        ConnectIQService.LogLevel.WARN -> Log.w(tag, message)
                        ConnectIQService.LogLevel.INFO -> Log.i(tag, message)
                        ConnectIQService.LogLevel.DEBUG -> Log.d(tag, message)
                    }
                }
            }
        )
    }

    private fun sendResult(context: Context, success: Boolean, payload: String, debug: String) {
        val resultIntent = Intent(ACTION_RESULT).apply {
            putExtra(EXTRA_SUCCESS, success)
            putExtra(EXTRA_PAYLOAD, payload)
            putExtra(EXTRA_DEBUG, debug)
            putExtra(EXTRA_DEVICE_COUNT, if (success) 1 else 0)
        }
        Log.i(TAG, "[RECEIVER] Broadcasting result: success=$success, payload='$payload'")
        context.sendBroadcast(resultIntent)
    }
}