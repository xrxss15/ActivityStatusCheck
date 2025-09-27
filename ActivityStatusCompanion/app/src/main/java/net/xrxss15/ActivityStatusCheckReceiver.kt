package net.xrxss15

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import android.util.Log
import com.garmin.android.connectiq.ConnectIQ
import com.garmin.android.connectiq.ConnectIQ.ConnectIQListener
import com.garmin.android.connectiq.ConnectIQ.IQApplicationEventListener
import com.garmin.android.connectiq.ConnectIQ.IQConnectType
import com.garmin.android.connectiq.ConnectIQ.IQMessageStatus
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice
import java.util.concurrent.atomic.AtomicBoolean

class ActivityStatusCheckReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ActStatusCheck"
        private const val ACTION_OUT = "net.xrxss15.ACTIVITY_STATUS_CHECK"
        private const val EXTRA_PAYLOAD = "payload"
        private const val CIQ_APP_ID = "REPLACE_WITH_WATCH_APP_UUID" // TODO
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        Log.d(TAG, "Received: $intent")
        Toast.makeText(ctx, "Receiver triggered", Toast.LENGTH_SHORT).show()
        val ciq = ConnectIQ.getInstance(ctx, IQConnectType.TETHERED)
        ciq.initialize(ctx, true, object : ConnectIQListener {
            override fun onSdkReady() {
                Log.d(TAG, "SDK ready")
                runFlow(ctx, ciq)
            }
            override fun onInitializeError(status: ConnectIQ.IQSdkErrorStatus?) {
                Log.e(TAG, "SDK init error: $status")
                broadcast(ctx, "TIMEOUT")
            }
            override fun onSdkShutDown() = Unit
        })
    }

    private fun runFlow(ctx: Context, ciq: ConnectIQ) {
        val responded = AtomicBoolean(false)
        val app = IQApp(CIQ_APP_ID)

        val devices = try { ciq.knownDevices ?: emptyList() } catch (_: Throwable) { emptyList() }
        val device = devices.firstOrNull()
        if (device == null) {
            Log.w(TAG, "No known devices")
            broadcast(ctx, "TIMEOUT")
            return
        }
        Log.d(TAG, "Using device: ${device.friendlyName}")

        try {
            ciq.registerForAppEvents(device, app, object : IQApplicationEventListener {
                override fun onMessageReceived(
                    iqDevice: IQDevice,
                    iqApp: IQApp,
                    messageData: List<Any>,
                    status: IQMessageStatus
                ) {
                    Log.d(TAG, "onMessageReceived status=$status data=$messageData")
                    if (status == IQMessageStatus.SUCCESS && messageData.isNotEmpty()) {
                        val txt = messageData.joinToString("")
                        if (txt.startsWith("running:")) {
                            responded.set(true)
                            broadcast(ctx, txt.removePrefix("running:"))
                        }
                    }
                }
            })
            Log.d(TAG, "Registered for app events")
        } catch (t: Throwable) {
            Log.e(TAG, "registerForAppEvents failed", t)
            broadcast(ctx, "TIMEOUT")
            return
        }

        try {
            ciq.sendMessage(device, app, listOf("status?")) { _, _, status ->
                Log.d(TAG, "sendMessage status=$status")
            }
            Log.d(TAG, "Sent message: status?")
        } catch (t: Throwable) {
            Log.e(TAG, "sendMessage failed", t)
            broadcast(ctx, "TIMEOUT")
            return
        }

        Handler(Looper.getMainLooper()).postDelayed({
            if (!responded.get()) {
                Log.w(TAG, "Timeout waiting for reply")
                broadcast(ctx, "TIMEOUT")
            }
        }, 60_000)
    }

    private fun broadcast(ctx: Context, payload: String) {
        Log.d(TAG, "Broadcast result: $payload")
        Toast.makeText(ctx, "Result: $payload", Toast.LENGTH_SHORT).show()
        val out = Intent(ACTION_OUT).apply { putExtra(EXTRA_PAYLOAD, payload) }
        ctx.sendBroadcast(out)
    }
}