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
        const val ACTION_RESULT = "net.xrxss15.ACTIVITY_STATUS_RESULT"
        
        const val EXTRA_PAYLOAD = "payload"
        const val EXTRA_DEBUG = "debug"
        const val EXTRA_DEVICE_ID = "deviceId"
        const val EXTRA_DEVICE_NAME = "deviceName"
        const val EXTRA_STATUS = "status"

        private const val CIQ_APP_ID = "5cd85684-4b48-419b-b63a-a2065368ae1e"
        private const val SIMULATOR_ID: Long = 12345L
        private const val TOTAL_TIMEOUT_MS = 15000L
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != ACTION_TRIGGER) return
        
        val pending = goAsync()
        val handler = Handler(Looper.getMainLooper())
        
        Log.d(TAG, "Tasker trigger received")
        
        val flow = DeviceMessageFlow(ctx, pending, handler)
        
        // Single timeout for entire operation
        handler.postDelayed({ flow.timeout() }, TOTAL_TIMEOUT_MS)
        
        handler.post { flow.start() }
    }

    private class DeviceMessageFlow(
        private val ctx: Context,
        private val pending: BroadcastReceiver.PendingResult,
        private val handler: Handler
    ) {
        private var ciq: ConnectIQ? = null
        private val app = IQApp(CIQ_APP_ID)
        private val processedDevices = mutableSetOf<Long>()
        private var totalDevices = 0
        private var finished = false

        fun start() {
            try {
                ciq = ConnectIQ.getInstance(ctx, IQConnectType.TETHERED)
                ciq!!.initialize(ctx, false, object : ConnectIQListener {
                    override fun onSdkReady() {
                        Log.d(TAG, "SDK ready")
                        handler.postDelayed({ processDevices() }, 2000)
                    }
                    
                    override fun onInitializeError(status: ConnectIQ.IQSdkErrorStatus?) {
                        emitError("initError=$status")
                    }
                    
                    override fun onSdkShutDown() {}
                })
            } catch (t: Throwable) {
                emitError("initException=${t.message}")
            }
        }

        fun timeout() {
            if (!finished) {
                Log.w(TAG, "Operation timeout")
                processedDevices.forEach { /* already emitted */ }
                if (processedDevices.isEmpty()) {
                    emitGlobal("TIMEOUT", "operationTimeout")
                }
                finish()
            }
        }

        private fun processDevices() {
            val devices = getRealDevices()
            if (devices.isEmpty()) {
                emitGlobal("NO_DEVICES", "noRealDevices")
                return
            }
            
            totalDevices = devices.size
            Log.d(TAG, "Processing ${devices.size} devices")
            
            devices.forEach { device ->
                setupDevice(device)
            }
        }

        private fun getRealDevices(): List<IQDevice> {
            val known = try { ciq?.knownDevices ?: emptyList() } catch (_: Throwable) { emptyList() }
            return known.filter { it.deviceIdentifier != SIMULATOR_ID }
        }

        private fun setupDevice(device: IQDevice) {
            try {
                // Register status listener
                ciq?.registerForDeviceEvents(device) { dev, status ->
                    if (status == IQDeviceStatus.CONNECTED) {
                        sendMessage(dev)
                    }
                }
                
                // Try immediate send if already connected
                val connected = try { ciq?.getConnectedDevices() ?: emptyList() } catch (_: Throwable) { emptyList() }
                if (connected.any { it.deviceIdentifier == device.deviceIdentifier }) {
                    sendMessage(device)
                }
                
            } catch (t: Throwable) {
                deviceDone(device, "ERROR", "setupError=${t.message}")
            }
        }

        private fun sendMessage(device: IQDevice) {
            if (processedDevices.contains(device.deviceIdentifier)) return
            
            try {
                ciq?.registerForAppEvents(device, app, object : IQApplicationEventListener {
                    override fun onMessageReceived(
                        iqDevice: IQDevice,
                        iqApp: IQApp,
                        messageData: List<Any>,
                        status: IQMessageStatus
                    ) {
                        if (processedDevices.contains(iqDevice.deviceIdentifier)) return
                        
                        if (status == IQMessageStatus.SUCCESS && messageData.isNotEmpty()) {
                            deviceDone(iqDevice, messageData.joinToString(""), "SUCCESS")
                        } else {
                            deviceDone(iqDevice, "ERROR", "emptyResponse")
                        }
                    }
                })
                
                ciq?.sendMessage(device, app, listOf("status?")) { _, _, _ -> }
                
            } catch (t: Throwable) {
                deviceDone(device, "ERROR", "sendError=${t.message}")
            }
        }

        private fun deviceDone(device: IQDevice, payload: String, debug: String) {
            if (processedDevices.contains(device.deviceIdentifier)) return
            processedDevices.add(device.deviceIdentifier)
            
            val status = if (payload == "ERROR") "ERROR" else "SUCCESS"
            
            val intent = Intent(ACTION_RESULT).apply {
                putExtra(EXTRA_PAYLOAD, payload)
                putExtra(EXTRA_DEBUG, debug)
                putExtra(EXTRA_STATUS, status)
                putExtra(EXTRA_DEVICE_ID, device.deviceIdentifier.toString())
                putExtra(EXTRA_DEVICE_NAME, device.friendlyName ?: "")
            }
            ctx.sendBroadcast(intent)
            
            Log.d(TAG, "Device done: ${device.friendlyName} -> $payload")
            
            if (processedDevices.size >= totalDevices) {
                finish()
            }
        }

        private fun emitGlobal(payload: String, debug: String) {
            val intent = Intent(ACTION_RESULT).apply {
                putExtra(EXTRA_PAYLOAD, payload)
                putExtra(EXTRA_DEBUG, debug)
                putExtra(EXTRA_STATUS, payload)
                putExtra(EXTRA_DEVICE_ID, "")
                putExtra(EXTRA_DEVICE_NAME, "")
            }
            ctx.sendBroadcast(intent)
        }

        private fun emitError(debug: String) {
            emitGlobal("ERROR", debug)
            finish()
        }

        private fun finish() {
            if (!finished) {
                finished = true
                pending.finish()
            }
        }
    }
}