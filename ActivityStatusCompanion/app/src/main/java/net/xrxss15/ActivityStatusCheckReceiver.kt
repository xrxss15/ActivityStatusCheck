package net.xrxss15

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.garmin.android.connectiq.ConnectIQ
import com.garmin.android.connectiq.ConnectIQ.ConnectIQListener
import com.garmin.android.connectiq.ConnectIQ.IQApplicationEventListener
import com.garmin.android.connectiq.ConnectIQ.IQMessageStatus
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice
import java.util.concurrent.atomic.AtomicBoolean

class ActivityStatusCheckReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ActStatusCheck"
        // Replace with the actual UUID from the watch app manifest
        private const val CIQ_APP_ID = "REPLACE_WITH_WATCH_APP_UUID"
        private const val ACTION_OUT = "net.xrxss15.ACTIVITY_STATUS_CHECK"
        private const val EXTRA_PAYLOAD = "payload"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        val ciq = ConnectIQ.getInstance(ctx, ConnectIQ.IQConnectType.TETHERED)
        ciq.initialize(ctx, true, object : ConnectIQListener {
            override fun onSdkReady() {
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

        val devices: List<IQDevice> = try {
            // Use known or connected devices; either returns an empty list if none are available
            ciq.knownDevices ?: emptyList()
        } catch (t: Throwable) {
            emptyList()
        }
        val device = devices.firstOrNull()
        if (device == null) {
            broadcast(ctx, "TIMEOUT")
            return
        }

        try {
            ciq.registerForAppEvents(device, app, object : IQApplicationEventListener {
                override fun onMessageReceived(
                    iqDevice: IQDevice,
                    iqApp: IQApp,
                    messageData: List<Any>,
                    status: IQMessageStatus
                ) {
                    if (status == IQMessageStatus.SUCCESS && messageData.isNotEmpty()) {
                        val txt = messageData.joinToString(separator = "")
                        if (txt.startsWith("running:")) {
                            responded.set(true)
                            broadcast(ctx, txt.removePrefix("running:"))
                        }
                    }
                }
            })
        } catch (t: Throwable) {
            Log.e(TAG, "registerForAppEvents failed", t)
            broadcast(ctx, "TIMEOUT")
            return
        }

        try {
            ciq.sendMessage(device, app, listOf("status?")) { _, _, status ->
                Log.d(TAG, "sendMessage status=$status")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "sendMessage failed", t)
            broadcast(ctx, "TIMEOUT")
            return
        }

        Handler(Looper.getMainLooper()).postDelayed({
            if (!responded.get()) broadcast(ctx, "TIMEOUT")
        }, 60_000)
    }

    private fun broadcast(ctx: Context, payload: String) {
        val out = Intent(ACTION_OUT).apply { putExtra(EXTRA_PAYLOAD, payload) }
        ctx.sendBroadcast(out)
    }
}