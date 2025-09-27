package net.xrxss15

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.garmin.android.connectiq.CIQStatusListener
import com.garmin.android.connectiq.ConnectIQ
import com.garmin.android.connectiq.ConnectIQ.IQApp
import com.garmin.android.connectiq.ConnectIQ.IQDevice
import com.garmin.android.connectiq.listener.IQMessageListener
import java.util.concurrent.atomic.AtomicBoolean

class ActivityStatusCheckReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ActStatusCheck"
        // IMPORTANT: replace with the exact UUID from the watch app manifest
        private const val CIQ_APP_ID = "5cd85684-4b48-419b-b63a-a2065368ae1e"
        private const val ACTION_OUT = "net.xrxss15.ACTIVITY_STATUS_CHECK"
        private const val EXTRA_PAYLOAD = "payload"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        val ciq = ConnectIQ.getInstance(ctx, ConnectIQ.IQConnectType.TETHERED)
        ciq.initialize(ctx, true, object : CIQStatusListener {
            override fun onSdkReady() {
                runFlow(ctx, ciq)
            }
            override fun onInitializeError(e: ConnectIQ.IQSdkErrorStatus?) {
                Log.e(TAG, "SDK init error: $e")
                broadcast(ctx, "TIMEOUT")
            }
            override fun onSdkShutDown() {}
        })
    }

    private fun runFlow(ctx: Context, ciq: ConnectIQ) {
        val responded = AtomicBoolean(false)
        val app = IQApp(CIQ_APP_ID)

        // Listen for watch reply
        ciq.registerMessageListener(object : IQMessageListener {
            override fun onMessageReceived(device: IQDevice, iqApp: IQApp, data: ByteArray) {
                val msg = data.toString(Charsets.UTF_8)
                if (msg.startsWith("running:")) {
                    responded.set(true)
                    val yesNo = msg.removePrefix("running:")
                    broadcast(ctx, yesNo)
                }
            }
        })

        // Send "status?"
        try {
            val watch = ciq.knownDevices.firstOrNull()
            if (watch != null) {
                ciq.openSession(watch)
                ciq.sendMessage(watch, app, "status?")
            } else {
                broadcast(ctx, "TIMEOUT")
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendMessage failed", e)
            broadcast(ctx, "TIMEOUT")
            return
        }

        // 60 s timeout
        Handler(Looper.getMainLooper()).postDelayed({
            if (!responded.get()) broadcast(ctx, "TIMEOUT")
        }, 60_000)
    }

    private fun broadcast(ctx: Context, payload: String) {
        val out = Intent(ACTION_OUT).apply { putExtra(EXTRA_PAYLOAD, payload) }
        ctx.sendBroadcast(out)
    }
}