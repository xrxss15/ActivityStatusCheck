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

        const val EXTRA_PAYLOAD = "payload"
        const val EXTRA_DEBUG   = "debug"
        const val EXTRA_DEVICE_ID = "deviceId"
        const val EXTRA_DEVICE_NAME = "deviceName"
        const val EXTRA_STATUS  = "status"

        private const val CIQ_APP_ID = "5cd85684-4b48-419b-b63a-a2065368ae1e"
        private const val TIMEOUT_MS = 10000L
        private const val SIMULATOR_ID: Long = 12345L
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != ACTION_TRIGGER) return
        Log.d(TAG, "onReceive trigger: $intent")
        val pending = goAsync()
        val main = Handler(Looper.getMainLooper())

        main.post {
            try {
                val ciq = ConnectIQ.getInstance(ctx, IQConnectType.TETHERED)
                Log.d(TAG, "Initializing SDK (showUi=false)")
                ciq.initialize(ctx, false, object : ConnectIQListener {
                    override fun onSdkReady() {
                        Log.d(TAG, "SDK ready in receiver")
                        runFlow(ctx, ciq, pending)
                    }
                    override fun onInitializeError(status: ConnectIQ.IQSdkErrorStatus?) {
                        Log.e(TAG, "SDK init error: $status")
                        emitResult(ctx, null, "ERROR", "initError=$status", "ERROR")
                        pending.finish()
                    }
                    override fun onSdkShutDown() {
                        Log.d(TAG, "SDK shutdown in receiver")
                    }
                })
            } catch (t: Throwable) {
                Log.e(TAG, "Receiver init exception: ${t.message}", t)
                emitResult(ctx, null, "ERROR", "initException=${t.message}", "ERROR")
                pending.finish()
            }
        }
    }

    private fun runFlow(ctx: Context, ciq: ConnectIQ, pending: PendingResult) {
        val app = IQApp(CIQ_APP_ID)

        // Known devices; status will arrive via registerForDeviceEvents
        val known = try {
            Log.d(TAG, "Querying known devices")
            ciq.knownDevices ?: emptyList()
        } catch (t: Throwable) {
            Log.e(TAG, "knownDevices failed: ${t.message}", t)
            emptyList()
        }.filter { it.deviceIdentifier != SIMULATOR_ID }

        Log.d(TAG, "Known real devices: ${known.map { it.friendlyName to it.deviceIdentifier }}")
        if (known.isEmpty()) {
            Log.w(TAG, "No known devices")
            emitResult(ctx, null, "NO_DEVICES", "noKnownDevices", "NO_DEVICES")
            pending.finish()
            return
        }

        val main = Handler(Looper.getMainLooper())
        val statusMap = mutableMapOf<IQDevice, IQDeviceStatus>()
        val sent = known.associateWith { false }.toMutableMap()
        val responded = known.associateWith { false }.toMutableMap()
        var done = 0

        fun finishIfAll() {
            Log.d(TAG, "Progress: done=$done total=${known.size}")
            if (done == known.size) {
                Log.d(TAG, "All devices processed, finishing")
                pending.finish()
            }
        }

        // Per-device timeout
        main.postDelayed({
            known.forEach { dev ->
                if (!responded[dev]!!) {
                    Log.w(TAG, "Timeout for device: ${dev.friendlyName}")
                    responded[dev] = true
                    done += 1
                    emitResult(ctx, dev, "TIMEOUT", "timeoutMs=$TIMEOUT_MS", "TIMEOUT")
                }
            }
            finishIfAll()
        }, TIMEOUT_MS)

        // Device status updates; send only when CONNECTED
        known.forEach { dev ->
            try {
                ciq.registerForDeviceEvents(dev) { d, st ->
                    Log.d(TAG, "Device ${d.friendlyName} status=${st.name}")
                    statusMap[d] = st
                    if (!sent[d]!! && st == IQDeviceStatus.CONNECTED) {
                        sent[d] = true
                        listenAndSend(ctx, ciq, d, app, responded) {
                            done += 1
                            finishIfAll()
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "registerForDeviceEvents failed for ${dev.friendlyName}: ${t.message}", t)
                if (!responded[dev]!!) {
                    responded[dev] = true
                    done += 1
                    emitResult(ctx, dev, "ERROR", "deviceEventsError=${t.message}", "ERROR")
                    finishIfAll()
                }
            }
        }

        // Trigger send immediately if already CONNECTED
        known.forEach { dev ->
            if (!sent[dev]!! && statusMap[dev] == IQDeviceStatus.CONNECTED) {
                sent[dev] = true
                listenAndSend(ctx, ciq, dev, app, responded) {
                    done += 1
                    finishIfAll()
                }
            }
        }
    }

    private fun listenAndSend(
        ctx: Context,
        ciq: ConnectIQ,
        dev: IQDevice,
        app: IQApp,
        responded: MutableMap<IQDevice, Boolean>,
        onDone: () -> Unit
    ) {
        Log.d(TAG, "Registering app events for ${dev.friendlyName}")
        try {
            ciq.registerForAppEvents(dev, app, object : IQApplicationEventListener {
                override fun onMessageReceived(
                    iqDevice: IQDevice,
                    iqApp: IQApp,
                    messageData: List<Any>,
                    status: IQMessageStatus
                ) {
                    Log.d(TAG, "onMessageReceived from ${iqDevice.friendlyName} status=$status data=$messageData")
                    if (responded[iqDevice] == true) return
                    if (status == IQMessageStatus.SUCCESS && messageData.isNotEmpty()) {
                        val txt = messageData.joinToString("")
                        responded[iqDevice] = true
                        Log.d(TAG, "Emitting SUCCESS for ${iqDevice.friendlyName} payload=$txt")
                        emitResult(ctx, iqDevice, txt, "rxStatus=$status", "SUCCESS")
                        onDone()
                    }
                }
            })
        } catch (t: Throwable) {
            Log.e(TAG, "registerForAppEvents failed for ${dev.friendlyName}: ${t.message}", t)
            if (!responded[dev]!!) {
                responded[dev] = true
                emitResult(ctx, dev, "ERROR", "registerForAppEventsError=${t.message}", "ERROR")
                onDone()
            }
            return
        }

        // Send after listener is in place
        try {
            Log.d(TAG, "Sending message to ${dev.friendlyName}")
            ciq.sendMessage(dev, app, listOf("status?")) { _, _, _ ->
                Log.d(TAG, "sendMessage callback for ${dev.friendlyName}")
            }
        } catch (t: Throwable) {
            Log.e(TAG, "sendMessage failed for ${dev.friendlyName}: ${t.message}", t)
            if (!responded[dev]!!) {
                responded[dev] = true
                emitResult(ctx, dev, "ERROR", "sendError=${t.message}", "ERROR")
                onDone()
            }
        }
    }

    private fun emitResult(ctx: Context, dev: IQDevice?, payload: String, dbg: String, status: String) {
        Log.d(TAG, "Emit result: device=${dev?.friendlyName ?: "n/a"} payload=$payload status=$status debug=$dbg")
        val out = Intent(ACTION_RESULT).apply {
            putExtra(EXTRA_PAYLOAD, payload)
            putExtra(EXTRA_DEBUG, dbg)
            putExtra(EXTRA_STATUS, status)
            putExtra(EXTRA_DEVICE_ID, (dev?.deviceIdentifier ?: 0L).toString())
            putExtra(EXTRA_DEVICE_NAME, dev?.friendlyName ?: "")
        }
        ctx.sendBroadcast(out)
    }
}