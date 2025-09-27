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
        const val EXTRA_PAYLOAD = "payload"          // e.g., "running:true", "TIMEOUT", or "ERROR"
        const val EXTRA_DEBUG = "debug"              // free-form debug text
        const val EXTRA_DEVICE_ID = "deviceId"       // empty if no device
        const val EXTRA_DEVICE_NAME = "deviceName"   // empty if no device
        const val EXTRA_STATUS = "status"            // "SUCCESS", "TIMEOUT", "ERROR", "NO_DEVICES"

        // Set exact UUID of the watch app
        private const val CIQ_APP_ID = "REPLACE_WITH_WATCH_APP_UUID"

        private const val TIMEOUT_MS = 10000L
        private const val SIMULATOR_ID: Long = 12345L // compare as Long
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != ACTION_TRIGGER) return
        val pending = goAsync()
        val main = Handler(Looper.getMainLooper())

        main.post {
            val ciq = ConnectIQ.getInstance(ctx, IQConnectType.TETHERED)
            ciq.initialize(ctx, true, object : ConnectIQListener {
                override fun onSdkReady() {
                    runFlow(ctx, ciq, pending)
                }
                override fun onInitializeError(status: ConnectIQ.IQSdkErrorStatus?) {
                    emitResult(ctx, null, "ERROR", "initError=$status", "ERROR")
                    pending.finish()
                }
                override fun onSdkShutDown() = Unit
            })
        }
    }

    private fun runFlow(ctx: Context, ciq: ConnectIQ, pending: PendingResult) {
        val app = IQApp(CIQ_APP_ID)

        // Connected, non-simulator devices only (deviceIdentifier is non-nullable Long)
        val connected = try {
            ciq.getConnectedDevices() ?: emptyList()
        } catch (_: Throwable) {
            emptyList()
        }.filter { it.deviceIdentifier != SIMULATOR_ID }

        if (connected.isEmpty()) {
            emitResult(ctx, null, "NO_DEVICES", "noConnectedDevices", "NO_DEVICES")
            pending.finish()
            return
        }

        // Track per-device completion
        var done = 0
        val responded = connected.associateWith { false }.toMutableMap()

        val finishIfAll = {
            if (done == connected.size) pending.finish()
        }

        // Global timeout
        val timeout = Handler(Looper.getMainLooper())
        timeout.postDelayed({
            connected.forEach { dev ->
                if (responded[dev] == false) {
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
                ciq.registerForAppEvents(dev, app, object : IQApplicationEventListener {
                    override fun onMessageReceived(
                        iqDevice: IQDevice,
                        iqApp: IQApp,
                        messageData: List<Any>,
                        status: IQMessageStatus
                    ) {
                        if (responded[iqDevice] == true) return
                        if (status == IQMessageStatus.SUCCESS && messageData.isNotEmpty()) {
                            val txt = messageData.joinToString("")
                            responded[iqDevice] = true
                            done += 1
                            emitResult(ctx, iqDevice, txt, "rxStatus=$status", "SUCCESS")
                            finishIfAll()
                        }
                    }
                })
            } catch (t: Throwable) {
                if (responded[dev] == false) {
                    responded[dev] = true
                    done += 1
                    emitResult(ctx, dev, "ERROR", "registerForAppEventsError=${t.message}", "ERROR")
                    finishIfAll()
                }
                return@forEach
            }

            try {
                ciq.sendMessage(dev, app, listOf("status?")) { _, _, _ ->
                    // Keep waiting for app event or timeout
                }
            } catch (t: Throwable) {
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