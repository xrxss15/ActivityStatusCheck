package net.xrxss15

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.garmin.android.connectiq.ConnectIQ
import com.garmin.android.connectiq.ConnectIQ.ConnectIQListener
import com.garmin.android.connectiq.ConnectIQ.IQApplicationEventListener
import com.garmin.android.connectiq.ConnectIQ.IQConnectType
import com.garmin.android.connectiq.ConnectIQ.IQMessageStatus
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice

class ActivityStatusCheckReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ActStatusCheck"
        private const val ACTION_OUT = "net.xrxss15.ACTIVITY_STATUS_CHECK"
        private const val EXTRA_PAYLOAD = "payload"
        private const val EXTRA_DEBUG = "debug"
        private const val CIQ_APP_ID = "REPLACE_WITH_WATCH_APP_UUID"
        private const val TIMEOUT_MS = 10000L
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        Log.d(TAG, "Received: $intent")
        val pending = goAsync()
        val main = Handler(Looper.getMainLooper())

        main.post {
            val ciq = ConnectIQ.getInstance(ctx, IQConnectType.TETHERED)
            ciq.initialize(ctx, true, object : ConnectIQListener {
                override fun onSdkReady() {
                    Log.d(TAG, "SDK ready")
                    runFlow(ctx, ciq, pending)
                }
                override fun onInitializeError(status: ConnectIQ.IQSdkErrorStatus?) {
                    Log.e(TAG, "SDK init error: $status")
                    emit(ctx, "TIMEOUT", "initError=$status"); pending.finish()
                }
                override fun onSdkShutDown() = Unit
            })
        }
    }

    private fun runFlow(ctx: Context, ciq: ConnectIQ, pending: PendingResult) {
        val app = IQApp(CIQ_APP_ID)
        val devices = try { ciq.knownDevices ?: emptyList() } catch (t: Throwable) { Log.e(TAG, "knownDevices failed", t); emptyList() }
        if (devices.isEmpty()) { emit(ctx, "TIMEOUT", "noDevices"); pending.finish(); return }

        val device = devices.first()
        Log.d(TAG, "Using device: ${device.friendlyName} id=${device.deviceIdentifier}")

        // Log device status changes
        try {
            ciq.registerForDeviceEvents(device) { dev, status ->
                Log.d(TAG, "Device ${dev.friendlyName} status=${status.name}")
            }
        } catch (t: Throwable) { Log.e(TAG, "registerForDeviceEvents failed", t) }

        var finished = false
        val timeout = Handler(Looper.getMainLooper())
        timeout.postDelayed({
            if (!finished) {
                Log.w(TAG, "Timeout waiting for reply")
                emit(ctx, "TIMEOUT", "timeoutMs=$TIMEOUT_MS")
                pending.finish()
                finished = true
            }
        }, TIMEOUT_MS)

        // Listen first, then send
        try {
            ciq.registerForAppEvents(device, app, object : IQApplicationEventListener {
                override fun onMessageReceived(
                    iqDevice: IQDevice,
                    iqApp: IQApp,
                    messageData: List<Any>,
                    status: IQMessageStatus
                ) {
                    Log.d(TAG, "onMessageReceived status=$status data=$messageData")
                    if (!finished && status == IQMessageStatus.SUCCESS && messageData.isNotEmpty()) {
                        val txt = messageData.joinToString("")
                        emit(ctx, txt, "rxStatus=$status")
                        pending.finish()
                        finished = true
                    }
                }
            })
            Log.d(TAG, "Registered app event listener")
        } catch (t: Throwable) {
            Log.e(TAG, "registerForAppEvents failed", t)
            if (!finished) { emit(ctx, "TIMEOUT", "registerForAppEventsError=${t.message}"); pending.finish(); finished = true }
            return
        }

        try {
            ciq.sendMessage(device, app, listOf("status?")) { _, _, status ->
                Log.d(TAG, "sendMessage status=$status")
                if (status != IQMessageStatus.SUCCESS) {
                    // Do not finish here; wait for possible late app event or timeout
                    Toast.makeText(ctx, "sendMessage: $status", Toast.LENGTH_SHORT).show()
                }
            }
            Log.d(TAG, "Sent message: status?")
        } catch (t: Throwable) {
            Log.e(TAG, "sendMessage failed", t)
            if (!finished) { emit(ctx, "TIMEOUT", "sendError=${t.message}"); pending.finish(); finished = true }
        }
    }

    private fun emit(ctx: Context, payload: String, dbg: String) {
        Log.d(TAG, "Emit payload=$payload debug=$dbg")
        val out = Intent(ACTION_OUT).apply {
            putExtra(EXTRA_PAYLOAD, payload)
            putExtra(EXTRA_DEBUG, dbg)
        }
        ctx.sendBroadcast(out)
    }
}