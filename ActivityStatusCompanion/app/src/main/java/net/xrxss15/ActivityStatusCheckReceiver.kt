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

class ActivityStatusCheckReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "ActStatusCheck"

        // Tasker triggers this
        const val ACTION_TRIGGER = "net.xrxss15.ACTIVITY_STATUS_CHECK"

        // Tasker listens for this (per-device and also for no-device/error cases)
        const val ACTION_RESULT = "net.xrxss15.ACTIVITY_STATUS_RESULT"

        // Result extras
        const val EXTRA_PAYLOAD = "payload"
        const val EXTRA_DEBUG = "debug"
        const val EXTRA_DEVICE_ID = "deviceId"
        const val EXTRA_DEVICE_NAME = "deviceName"
        const val EXTRA_STATUS = "status"

        private const val CIQ_APP_ID = "REPLACE_WITH_WATCH_APP_UUID"
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
                Log.d(TAG, "Creating ConnectIQ instance (TETHERED)")
                val ciq = ConnectIQ.getInstance(ctx, IQConnectType.TETHERED)
                Log.d(TAG, "Initializing SDK (showUi=false)")
                ciq.initialize(ctx, false, object : ConnectIQListener {
                    override fun onSdkReady() {
                        Log.d(TAG, "SDK ready in receiver")
                        runFlow(ctx, ciq, pending)
                    }
                    override fun onInitializeError(status: ConnectIQ.IQSdkErrorStatus?) {
                        Log.e(TAG, "SDK init error in receiver: $status")
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

        val connected = try {
            Log.d(TAG, "Querying connected devices")
            ciq.getConnectedDevices() ?: emptyList()
        } catch (t: Throwable) {
            Log.e(TAG, "getConnectedDevices failed: ${t.message}", t)
            emptyList()
        }.filter { it.deviceIdentifier != SIMULATOR_ID }

        Log.d(TAG, "Connected real devices: ${connected.map { it.friendlyName to it.deviceIdentifier }}")

        if (connected.isEmpty()) {
            Log.w(TAG, "No connected devices")
            emitResult(ctx, null, "NO_DEVICES", "noConnectedDevices", "NO_DEVICES")
            pending.finish()
            return
        }

        // Track per-device completion
        var done = 0
        val responded = connected.associateWith { false }.toMutableMap()

        val finishIfAll = {
            Log.d(TAG, "Progress: done=$done total=${connected.size}")
            if (done == connected.size) {
                Log.d(TAG, "All devices processed, finishing broadcast")
                pending.finish()
            }
        }

        // Global timeout
        val timeout = Handler(Looper.getMainLooper())
        timeout.postDelayed({
            connected.forEach { dev ->
                if (responded[dev] == false) {
                    Log.w(TAG, "Timeout for device: ${dev.friendlyName}")
                    responded[dev] = true
                    done += 1
                    emitResult(ctx, dev, "TIMEOUT", "timeoutMs=$TIMEOUT_MS", "TIMEOUT")
                }
            }
            finishIfAll()
        }, TIMEOUT_MS)

        // Listen first, then send
        connected.forEach { dev ->
            try {
                Log.d(TAG, "Registering app events for ${dev.friendlyName}")
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
                            done += 1
                            Log.d(TAG, "Emitting SUCCESS for ${iqDevice.friendlyName} payload=$txt")
                            emitResult(ctx, iqDevice, txt, "rxStatus=$status", "SUCCESS")
                            finishIfAll()
                        }
                    }
                })
            } catch (t: Throwable) {
                Log.e(TAG, "registerForAppEvents failed for ${dev.friendlyName}: ${t.message}", t)
                if (responded[dev] == false) {
                    responded[dev] = true
                    done += 1
                    emitResult(ctx, dev, "ERROR", "registerForAppEventsError=${t.message}", "ERROR")
                    finishIfAll()
                }
                return@forEach
            }

            try {
                Log.d(TAG, "Sending message to ${dev.friendlyName}")
                ciq.sendMessage(dev, app, listOf("status?")) { _, _, _ ->
                    Log.d(TAG, "sendMessage callback for ${dev.friendlyName}")
                    // Wait for app event or timeout
                }
            } catch (t: Throwable) {
                Log.e(TAG, "sendMessage failed for ${dev.friendlyName}: ${t.message}", t)
                if (responded[dev] == false) {
                    responded[dev] = true
                    done += 1
                    emitResult(ctx, dev, "ERROR", "sendError=${t.message}", "ERROR")
                    finishIfAll()
                }
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