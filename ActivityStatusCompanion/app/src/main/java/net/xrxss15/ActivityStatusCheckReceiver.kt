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
import com.garmin.android.connectiq.ConnectIQ.IQConnectType
import com.garmin.android.connectiq.ConnectIQ.IQMessageStatus
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice
import com.garmin.android.connectiq.IQDevice.IQDeviceStatus

class ActivityStatusCheckReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ActStatusCheck"

        const val ACTION_TRIGGER = "net.xrxss15.ACTIVITY_STATUS_CHECK"
        const val ACTION_RESULT  = "net.xrxss15.ACTIVITY_STATUS_RESULT"

        private const val CIQ_APP_ID = "5cd85684-4b48-419b-b63a-a2065368ae1e"
        private const val SIMULATOR_ID: Long = 12345L

        private const val BRIDGE_WAIT_MS   = 10_000L   // consensus 10 s
        private const val DEVICE_TIMEOUTMS = 10_000L   // per-device timeout
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != ACTION_TRIGGER) return

        Log.d(TAG, "Broadcast received from Tasker")
        val pending = goAsync()
        val main    = Handler(Looper.getMainLooper())

        val ciq = ConnectIQ.getInstance(ctx, IQConnectType.TETHERED)
        Log.d(TAG, "Initializing SDK (showUi=true)")

        ciq.initialize(ctx, /*showUi=*/true, object : ConnectIQListener {

            override fun onSdkReady() {
                Log.d(TAG, "SDK ready – waiting ${BRIDGE_WAIT_MS} ms for bridge")
                main.postDelayed({ startWorkflow(ctx, ciq, pending) }, BRIDGE_WAIT_MS)
            }

            override fun onInitializeError(status: ConnectIQ.IQSdkErrorStatus?) {
                Log.e(TAG, "SDK init error: $status")
                sendResult(ctx, null, "INIT_ERROR", status.toString())
                pending.finish()
            }

            override fun onSdkShutDown() = Log.d(TAG, "SDK shutdown")
        })
    }

    /* ----------  Full device / app workflow after bridge wait ---------- */

    private fun startWorkflow(ctx: Context, ciq: ConnectIQ, pending: PendingResult) {

        val app  = IQApp(CIQ_APP_ID)
        val main = Handler(Looper.getMainLooper())

        val devices = try { ciq.knownDevices ?: emptyList() } catch (_: Throwable) { emptyList() }
            .filter { it.deviceIdentifier != SIMULATOR_ID }

        if (devices.isEmpty()) {
            Log.w(TAG, "No real devices found")
            sendResult(ctx, null, "NO_DEVICE", "knownDevicesEmpty")
            pending.finish()
            return
        }

        /* state maps */
        val sent      = devices.associateWith { false }.toMutableMap()
        val completed = devices.associateWith { false }.toMutableMap()

        fun doneIfAll() {
            if (completed.values.all { it })  pending.finish()
        }

        /* device timeout */
        main.postDelayed({
            devices.filter { !completed[it]!! }.forEach { dev ->
                Log.w(TAG, "Timeout $DEVICE_TIMEOUTMS ms for ${dev.friendlyName}")
                completed[dev] = true
                sendResult(ctx, dev, "TIMEOUT", "bridgeNoReply")
            }
            doneIfAll()
        }, DEVICE_TIMEOUTMS + BRIDGE_WAIT_MS)

        /* per device handling */
        devices.forEach { dev ->

            ciq.registerForDeviceEvents(dev) { _, status ->
                Log.d(TAG, "Status ${dev.friendlyName} = ${status.name}")

                if (status == IQDeviceStatus.CONNECTED && !sent[dev]!!) {
                    sent[dev] = true
                    registerAndSend(ctx, ciq, dev, app) { payload, dbg ->
                        completed[dev] = true
                        sendResult(ctx, dev, payload, dbg)
                        doneIfAll()
                    }
                }
            }
        }
    }

    /* ----------  Helpers ---------- */

    private fun registerAndSend(
        ctx: Context,
        ciq: ConnectIQ,
        dev: IQDevice,
        app: IQApp,
        onComplete: (String, String) -> Unit
    ) {
        try {
            ciq.registerForAppEvents(dev, app, object : IQApplicationEventListener {
                override fun onMessageReceived(
                    d: IQDevice,
                    a: IQApp,
                    data: List<Any>,
                    status: IQMessageStatus
                ) {
                    if (status == IQMessageStatus.SUCCESS && data.isNotEmpty()) {
                        val payload = data.joinToString("")
                        Log.d(TAG, "RX from ${d.friendlyName}: $payload")
                        onComplete(payload, "SUCCESS")
                    }
                }
            })
            ciq.sendMessage(dev, app, listOf("status?")) { _, _, msgStatus ->
                Log.d(TAG, "sendMessage ${dev.friendlyName} result=$msgStatus")
                // wait for app event or timeout
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error messaging ${dev.friendlyName}: ${t.message}")
            onComplete("ERROR", t.message ?: "exception")
        }
    }

    private fun sendResult(ctx: Context, dev: IQDevice?, payload: String, debug: String) {
        val out = Intent(ACTION_RESULT).apply {
            putExtra("payload",      payload)
            putExtra("debug",        debug)
            putExtra("deviceName",   dev?.friendlyName ?: "")
            putExtra("deviceId",     (dev?.deviceIdentifier ?: 0L).toString())
        }
        ctx.sendBroadcast(out)
    }
}