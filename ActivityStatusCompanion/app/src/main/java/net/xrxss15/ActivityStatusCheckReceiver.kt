package net.xrxss15

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
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
        private const val TAG = "ActStatusReceiver"
        const val ACTION_TRIGGER = "net.xrxss15.ACTIVITY_STATUS_CHECK"
        const val ACTION_RESULT = "net.xrxss15.ACTIVITY_STATUS_RESULT"
        const val EXTRA_PAYLOAD = "payload"
        const val EXTRA_DEBUG = "debug"
        const val EXTRA_DEVICE_COUNT = "deviceCount"
        const val EXTRA_SUCCESS = "success"
        private const val SIMULATOR_ID: Long = 12345L
        private const val APP_UUID = "5cd85684-4b48-419b-b63a-a2065368ae1e"
        private const val QUERY_TIMEOUT_MS = 15000L
        private const val BRIDGE_SETUP_DELAY_MS = 2000L
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_TRIGGER) {
            Log.d(TAG, "Received intent with action: ${intent.action}, ignoring")
            return
        }

        Log.d(TAG, "ActivityStatusCheckReceiver triggered by Tasker")

        if (!hasRequiredPermissions(context)) {
            Log.e(TAG, "Required permissions not granted")
            sendResult(context, false, "Missing permissions", "Location and Bluetooth permissions not granted")
            return
        }

        Log.d(TAG, "All required permissions verified")
        val pendingResult = goAsync()
        
        initializeConnectIQWithProperSequence(context, pendingResult)
    }

    private fun hasRequiredPermissions(context: Context): Boolean {
        val requiredPermissions = mutableListOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION
        )

        // For Android 12+ (API 31+), check new Bluetooth permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requiredPermissions.add(android.Manifest.permission.BLUETOOTH_SCAN)
            requiredPermissions.add(android.Manifest.permission.BLUETOOTH_CONNECT)
        }

        val missingPermissions = requiredPermissions.filter { permission ->
            ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            Log.w(TAG, "Missing permissions: ${missingPermissions.joinToString()}")
            return false
        }

        return true
    }

    private fun initializeConnectIQWithProperSequence(context: Context, pendingResult: PendingResult) {
        Log.d(TAG, "Starting ConnectIQ initialization sequence")
        
        try {
            val ciq = ConnectIQ.getInstance(context, IQConnectType.TETHERED)
            val myApp = IQApp(APP_UUID)

            Log.d(TAG, "Phase 1: Initializing ConnectIQ SDK with UI prompt for bridge setup")
            
            ciq.initialize(context, true, object : ConnectIQListener {
                override fun onSdkReady() {
                    Log.i(TAG, "Phase 1 complete: ConnectIQ SDK ready with bridge established")
                    
                    Handler(Looper.getMainLooper()).postDelayed({
                        Log.d(TAG, "Phase 2: Starting device discovery after bridge setup")
                        performDeviceQuerySequence(context, ciq, myApp, pendingResult)
                    }, BRIDGE_SETUP_DELAY_MS)
                }

                override fun onInitializeError(errStatus: ConnectIQ.IQSdkErrorStatus) {
                    Log.e(TAG, "Phase 1 failed: ConnectIQ SDK initialization error: $errStatus")
                    
                    when (errStatus) {
                        ConnectIQ.IQSdkErrorStatus.GCM_NOT_INSTALLED -> {
                            sendResult(context, false, "Garmin Connect not installed", "Install Garmin Connect Mobile app")
                        }
                        ConnectIQ.IQSdkErrorStatus.GCM_UPGRADE_NEEDED -> {
                            sendResult(context, false, "Garmin Connect needs upgrade", "Update Garmin Connect Mobile app")
                        }
                        ConnectIQ.IQSdkErrorStatus.SERVICE_ERROR -> {
                            sendResult(context, false, "ConnectIQ service error", "Restart Garmin Connect app")
                        }
                        else -> {
                            sendResult(context, false, "SDK Error: $errStatus", "ConnectIQ initialization failed: $errStatus")
                        }
                    }
                    pendingResult.finish()
                }

                override fun onSdkShutDown() {
                    Log.d(TAG, "ConnectIQ SDK shutdown")
                }
            })

        } catch (e: Exception) {
            Log.e(TAG, "Exception during ConnectIQ initialization", e)
            sendResult(context, false, "Initialization Exception: ${e.message}", "Failed to initialize ConnectIQ SDK")
            pendingResult.finish()
        }
    }

    private fun performDeviceQuerySequence(
        context: Context,
        ciq: ConnectIQ,
        myApp: IQApp,
        pendingResult: PendingResult
    ) {
        Log.d(TAG, "Starting device query sequence")
        
        try {
            val knownDevices = ciq.knownDevices
            val connectedDevices = ciq.connectedDevices

            Log.i(TAG, "Device discovery - Known: ${knownDevices.size}, Connected: ${connectedDevices.size}")

            if (knownDevices.isEmpty()) {
                Log.w(TAG, "No known devices found - bridge may not be properly established")
                sendResult(context, false, "No devices found", buildDebugInfo(0, 0, "No devices discovered"))
                pendingResult.finish()
                return
            }

            val targetDevice = selectOptimalDevice(ciq, knownDevices)

            if (targetDevice == null) {
                Log.e(TAG, "No suitable device found for communication")
                sendResult(context, false, "No suitable device", buildDebugInfo(knownDevices.size, connectedDevices.size, "No suitable device"))
                pendingResult.finish()
                return
            }

            val deviceType = if (targetDevice.deviceIdentifier == SIMULATOR_ID) "SIMULATOR" else "REAL_DEVICE"
            val deviceStatus = ciq.getDeviceStatus(targetDevice)
            
            Log.i(TAG, "Selected device: ${targetDevice.friendlyName}, Type: $deviceType, Status: $deviceStatus")

            establishDeviceCommunication(context, ciq, myApp, targetDevice, knownDevices.size, connectedDevices.size, pendingResult)

        } catch (e: Exception) {
            Log.e(TAG, "Exception during device query sequence", e)
            sendResult(context, false, "Query Exception: ${e.message}", "Error during device discovery")
            pendingResult.finish()
        }
    }

    private fun selectOptimalDevice(ciq: ConnectIQ, knownDevices: List<IQDevice>): IQDevice? {
        Log.d(TAG, "Selecting optimal device from ${knownDevices.size} known devices")
        
        val connectedRealDevice = knownDevices
            .filter { it.deviceIdentifier != SIMULATOR_ID }
            .find { ciq.getDeviceStatus(it) == IQDeviceStatus.CONNECTED }
        
        if (connectedRealDevice != null) {
            Log.d(TAG, "Selected connected real device: ${connectedRealDevice.friendlyName}")
            return connectedRealDevice
        }

        val realDevice = knownDevices.find { it.deviceIdentifier != SIMULATOR_ID }
        if (realDevice != null) {
            Log.d(TAG, "Selected real device: ${realDevice.friendlyName}")
            return realDevice
        }

        val simulator = knownDevices.find { it.deviceIdentifier == SIMULATOR_ID }
        if (simulator != null) {
            Log.w(TAG, "Using simulator as fallback device")
            return simulator
        }

        return null
    }

    private fun establishDeviceCommunication(
        context: Context,
        ciq: ConnectIQ,
        myApp: IQApp,
        targetDevice: IQDevice,
        knownDeviceCount: Int,
        connectedDeviceCount: Int,
        pendingResult: PendingResult
    ) {
        Log.d(TAG, "Establishing communication with ${targetDevice.friendlyName}")

        val timeoutHandler = Handler(Looper.getMainLooper())
        val timeoutRunnable = Runnable {
            Log.w(TAG, "Communication timeout after ${QUERY_TIMEOUT_MS}ms")
            sendResult(context, false, "Timeout", buildDebugInfo(knownDeviceCount, connectedDeviceCount, "Communication timeout"))
            pendingResult.finish()
        }
        timeoutHandler.postDelayed(timeoutRunnable, QUERY_TIMEOUT_MS)

        try {
            Log.d(TAG, "Registering for app events from ${targetDevice.friendlyName}")
            
            ciq.registerForAppEvents(targetDevice, myApp, object : IQApplicationEventListener {
                override fun onMessageReceived(
                    device: IQDevice,
                    app: IQApp,
                    message: List<Any>,
                    status: IQMessageStatus
                ) {
                    timeoutHandler.removeCallbacks(timeoutRunnable)
                    Log.i(TAG, "Response received from ${device.friendlyName}, Status: $status, Message: $message")

                    val payload = when {
                        message.isNotEmpty() -> message.joinToString(",")
                        else -> "empty_response"
                    }

                    val debugInfo = buildDebugInfo(knownDeviceCount, connectedDeviceCount, "Communication successful", device, status, message)

                    Log.d(TAG, "Sending success result with payload: $payload")
                    sendResult(context, true, payload, debugInfo)
                    
                    cleanupAndFinish(ciq, context, pendingResult)
                }
            })

            Log.d(TAG, "Sending status query message to ${targetDevice.friendlyName}")
            
            ciq.sendMessage(targetDevice, myApp, "status?", object : ConnectIQ.IQSendMessageListener {
                override fun onMessageStatus(
                    device: IQDevice,
                    app: IQApp,
                    status: IQMessageStatus
                ) {
                    Log.d(TAG, "Send message status: $status to device ${device.friendlyName}")
                    
                    when (status) {
                        IQMessageStatus.SUCCESS -> {
                            Log.d(TAG, "Message sent successfully, waiting for response")
                        }
                        IQMessageStatus.FAILURE_DEVICE_NOT_CONNECTED -> {
                            timeoutHandler.removeCallbacks(timeoutRunnable)
                            Log.e(TAG, "Message send failed: Device not connected")
                            sendResult(context, false, "Device not connected", buildDebugInfo(knownDeviceCount, connectedDeviceCount, "Device not connected"))
                            pendingResult.finish()
                        }
                        else -> {
                            timeoutHandler.removeCallbacks(timeoutRunnable)
                            Log.e(TAG, "Message send failed with status: $status")
                            sendResult(context, false, "Send failed: $status", buildDebugInfo(knownDeviceCount, connectedDeviceCount, "Message send failed: $status"))
                            pendingResult.finish()
                        }
                    }
                }
            })

        } catch (e: Exception) {
            timeoutHandler.removeCallbacks(timeoutRunnable)
            Log.e(TAG, "Exception during communication establishment", e)
            sendResult(context, false, "Communication Exception: ${e.message}", buildDebugInfo(knownDeviceCount, connectedDeviceCount, "Communication error"))
            pendingResult.finish()
        }
    }

    private fun buildDebugInfo(
        knownDeviceCount: Int,
        connectedDeviceCount: Int,
        status: String,
        device: IQDevice? = null,
        messageStatus: IQMessageStatus? = null,
        message: List<Any>? = null
    ): String {
        return buildString {
            appendLine("Status: $status")
            appendLine("Known devices: $knownDeviceCount")
            appendLine("Connected devices: $connectedDeviceCount")
            appendLine("Query timeout: ${QUERY_TIMEOUT_MS}ms")
            appendLine("Bridge delay: ${BRIDGE_SETUP_DELAY_MS}ms")
            
            device?.let {
                val deviceType = if (it.deviceIdentifier == SIMULATOR_ID) "SIMULATOR" else "REAL_DEVICE"
                appendLine("Target device: ${it.friendlyName}")
                appendLine("Device type: $deviceType")
                appendLine("Device ID: ${it.deviceIdentifier}")
            }
            
            messageStatus?.let {
                appendLine("Message status: $it")
            }
            
            message?.let {
                appendLine("Response: $it")
            }
        }
    }

    private fun cleanupAndFinish(ciq: ConnectIQ, context: Context, pendingResult: PendingResult) {
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                Log.d(TAG, "Shutting down ConnectIQ SDK")
                ciq.shutdown(context)
            } catch (e: Exception) {
                Log.w(TAG, "Error shutting down SDK", e)
            }
            pendingResult.finish()
        }, 100)
    }

    private fun sendResult(context: Context, success: Boolean, payload: String, debug: String) {
        val resultIntent = Intent(ACTION_RESULT).apply {
            putExtra(EXTRA_SUCCESS, success)
            putExtra(EXTRA_PAYLOAD, payload)
            putExtra(EXTRA_DEBUG, debug)
            putExtra(EXTRA_DEVICE_COUNT, if (success) 1 else 0)
        }

        Log.d(TAG, "Broadcasting result - Success: $success, Payload: $payload")
        context.sendBroadcast(resultIntent)
    }
}