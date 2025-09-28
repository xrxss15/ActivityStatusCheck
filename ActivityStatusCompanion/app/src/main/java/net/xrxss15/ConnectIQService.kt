package net.xrxss15

import android.content.Context
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

class ConnectIQService private constructor() {
    
    companion object {
        @Volatile
        private var INSTANCE: ConnectIQService? = null
        
        fun getInstance(): ConnectIQService {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ConnectIQService().also { INSTANCE = it }
            }
        }
        
        // CRITICAL: Updated UUID for new CIQ app
        private const val APP_UUID = "7b408c6e-fc9c-4080-bad4-97a3557fc995"
        private const val SIMULATOR_ID: Long = 12345L
        
        // CRITICAL: Increased delays based on our extensive testing
        private const val BRIDGE_DELAY_UI = 3000L
        private const val BRIDGE_DELAY_NO_UI = 2000L
        private const val QUERY_TIMEOUT_MS = 15000L
        private const val RETRY_DELAY_MS = 2000L
        private const val MAX_RETRIES = 2
    }

    // CRITICAL: Each operation needs its own ConnectIQ instance to avoid state conflicts
    private var currentOperation: String? = null
    
    interface StatusQueryCallback {
        fun onSuccess(payload: String, debug: String)
        fun onFailure(error: String, debug: String)
        fun onLog(tag: String, message: String, level: LogLevel = LogLevel.DEBUG)
    }
    
    enum class LogLevel { DEBUG, INFO, WARN, ERROR }
    
    fun hasRequiredPermissions(context: Context): Boolean {
        val required = mutableListOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            required.add(android.Manifest.permission.BLUETOOTH_SCAN)
            required.add(android.Manifest.permission.BLUETOOTH_CONNECT)
        }
        val missing = required.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            Log.w("ConnectIQService", "Missing permissions: ${missing.joinToString()}")
        }
        return missing.isEmpty()
    }
    
    // CRITICAL: Added synchronized operation tracking to prevent concurrent access issues
    @Synchronized
    fun queryActivityStatus(
        context: Context, 
        tag: String,
        showUi: Boolean = true,
        callback: StatusQueryCallback
    ) {
        if (currentOperation != null) {
            callback.onFailure("Operation in progress", "Another query is already running: $currentOperation")
            return
        }
        
        currentOperation = "$tag-query"
        callback.onLog(tag, "[SERVICE] Starting activity status query (showUi=$showUi, op=$currentOperation)", LogLevel.INFO)
        
        if (!hasRequiredPermissions(context)) {
            callback.onLog(tag, "[SERVICE] Missing required permissions", LogLevel.ERROR)
            callback.onFailure("Missing permissions", "Location/Bluetooth permissions required")
            currentOperation = null
            return
        }
        
        // CRITICAL: Try showUi=false first for background, with automatic fallback
        if (!showUi) {
            tryBackgroundFirst(context, tag, callback)
        } else {
            initializeWithUi(context, tag, callback)
        }
    }
    
    private fun tryBackgroundFirst(context: Context, tag: String, callback: StatusQueryCallback) {
        callback.onLog(tag, "[SERVICE] Attempting background initialization first", LogLevel.INFO)
        
        initializeConnectIQ(context, tag, false, callback) { ciqInstance ->
            Handler(Looper.getMainLooper()).postDelayed({
                performDeviceQuery(context, tag, ciqInstance, callback, retriesLeft = 1, fallbackToUi = true)
            }, BRIDGE_DELAY_NO_UI)
        }
    }
    
    private fun initializeWithUi(context: Context, tag: String, callback: StatusQueryCallback) {
        callback.onLog(tag, "[SERVICE] Initializing with UI enabled", LogLevel.INFO)
        
        initializeConnectIQ(context, tag, true, callback) { ciqInstance ->
            Handler(Looper.getMainLooper()).postDelayed({
                performDeviceQuery(context, tag, ciqInstance, callback, retriesLeft = MAX_RETRIES, fallbackToUi = false)
            }, BRIDGE_DELAY_UI)
        }
    }
    
    private fun initializeConnectIQ(
        context: Context,
        tag: String,
        showUi: Boolean,
        callback: StatusQueryCallback,
        onReady: (ConnectIQ) -> Unit
    ) {
        callback.onLog(tag, "[SERVICE] Initializing ConnectIQ SDK (showUi=$showUi)", LogLevel.DEBUG)
        
        // CRITICAL: Create fresh instance for each operation
        val ciq = logCiqCall(tag, callback, "getInstance") {
            ConnectIQ.getInstance(context, IQConnectType.TETHERED)
        } ?: run {
            callback.onLog(tag, "[SERVICE] Failed to get ConnectIQ instance", LogLevel.ERROR)
            callback.onFailure("SDK init failed", "Could not get ConnectIQ instance")
            currentOperation = null
            return
        }
        
        logCiqCall(tag, callback, "initialize(showUi=$showUi)") {
            ciq.initialize(context, showUi, object : ConnectIQListener {
                override fun onSdkReady() {
                    callback.onLog(tag, "[SERVICE] ConnectIQ SDK ready (showUi=$showUi)", LogLevel.INFO)
                    onReady(ciq)
                }
                
                override fun onInitializeError(err: ConnectIQ.IQSdkErrorStatus) {
                    callback.onLog(tag, "[SERVICE] ConnectIQ init error: $err (showUi=$showUi)", LogLevel.ERROR)
                    
                    // CRITICAL: Handle specific error cases with appropriate fallback
                    when (err) {
                        ConnectIQ.IQSdkErrorStatus.GCM_NOT_INSTALLED -> {
                            callback.onFailure("Garmin Connect not installed", "Install Garmin Connect Mobile app")
                        }
                        ConnectIQ.IQSdkErrorStatus.GCM_UPGRADE_NEEDED -> {
                            callback.onFailure("Garmin Connect needs upgrade", "Update Garmin Connect Mobile app")
                        }
                        ConnectIQ.IQSdkErrorStatus.SERVICE_ERROR -> {
                            if (!showUi) {
                                // CRITICAL: Fallback to UI mode for service errors
                                callback.onLog(tag, "[SERVICE] Service error in background mode, trying UI fallback", LogLevel.WARN)
                                initializeWithUi(context, tag, callback)
                                return
                            }
                            callback.onFailure("ConnectIQ service error", "Restart Garmin Connect app")
                        }
                        else -> {
                            callback.onFailure("SDK init error: $err", "ConnectIQ initialization failed")
                        }
                    }
                    currentOperation = null
                }
                
                override fun onSdkShutDown() {
                    callback.onLog(tag, "[SERVICE] ConnectIQ SDK shutdown (showUi=$showUi)", LogLevel.DEBUG)
                }
            })
        }
    }
    
    private fun performDeviceQuery(
        context: Context,
        tag: String,
        ciqInstance: ConnectIQ,
        callback: StatusQueryCallback,
        retriesLeft: Int,
        fallbackToUi: Boolean
    ) {
        callback.onLog(tag, "[SERVICE] Starting device discovery (retries=$retriesLeft, fallback=$fallbackToUi)", LogLevel.DEBUG)
        dumpDevices(tag, ciqInstance, callback)
        
        val known = logCiqCall(tag, callback, "knownDevices") { 
            ciqInstance.knownDevices 
        } ?: emptyList()
        
        val connected = logCiqCall(tag, callback, "connectedDevices") { 
            ciqInstance.connectedDevices 
        } ?: emptyList()
        
        callback.onLog(tag, "[SERVICE] Device discovery result: Known=${known.size}, Connected=${connected.size}", LogLevel.INFO)
        
        if (known.isEmpty()) {
            callback.onLog(tag, "[SERVICE] No known devices found", LogLevel.WARN)
            
            if (retriesLeft > 0) {
                callback.onLog(tag, "[SERVICE] Retrying device discovery after delay (${RETRY_DELAY_MS}ms)", LogLevel.INFO)
                Handler(Looper.getMainLooper()).postDelayed({
                    performDeviceQuery(context, tag, ciqInstance, callback, retriesLeft - 1, fallbackToUi)
                }, RETRY_DELAY_MS)
                return
            } else if (fallbackToUi) {
                callback.onLog(tag, "[SERVICE] Background discovery failed, falling back to UI mode", LogLevel.WARN)
                cleanup(context, ciqInstance)
                initializeWithUi(context, tag, callback)
                return
            } else {
                callback.onLog(tag, "[SERVICE] No devices found after all retries", LogLevel.ERROR)
                callback.onFailure("No devices found", buildDeviceDebugInfo(known, connected, "No devices after retries"))
                cleanup(context, ciqInstance)
                currentOperation = null
                return
            }
        }
        
        // CRITICAL: Pre-register for ALL known devices before selection
        preRegisterAllDevices(tag, ciqInstance, callback, known)
        
        val targetDevice = selectBestDevice(tag, ciqInstance, callback, known)
        if (targetDevice == null) {
            callback.onLog(tag, "[SERVICE] No suitable device could be selected", LogLevel.ERROR)
            callback.onFailure("No suitable device", buildDeviceDebugInfo(known, connected, "Selection failed"))
            cleanup(context, ciqInstance)
            currentOperation = null
            return
        }
        
        sendStatusQuery(context, tag, ciqInstance, targetDevice, callback)
    }
    
    private fun preRegisterAllDevices(
        tag: String,
        ciq: ConnectIQ,
        callback: StatusQueryCallback,
        devices: List<IQDevice>
    ) {
        callback.onLog(tag, "[SERVICE] Pre-registering for app events on ${devices.size} devices", LogLevel.DEBUG)
        
        val app = IQApp(APP_UUID)
        devices.forEach { device ->
            try {
                logCiqCall(tag, callback, "preRegisterForAppEvents(${device.friendlyName})") {
                    ciq.registerForAppEvents(device, app) { dev, _, msg, status ->
                        callback.onLog(tag, "[EVENT] Pre-registered event from ${dev.friendlyName}: $msg (status=$status)", LogLevel.INFO)
                    }
                }
                callback.onLog(tag, "[SERVICE] Pre-registered for ${device.friendlyName}", LogLevel.DEBUG)
            } catch (e: Exception) {
                callback.onLog(tag, "[SERVICE] Pre-registration failed for ${device.friendlyName}: ${e.message}", LogLevel.WARN)
            }
        }
    }
    
    private fun selectBestDevice(
        tag: String,
        ciq: ConnectIQ,
        callback: StatusQueryCallback,
        known: List<IQDevice>
    ): IQDevice? {
        callback.onLog(tag, "[SERVICE] Selecting best device from ${known.size} candidates", LogLevel.DEBUG)
        
        // CRITICAL: Prefer connected real device first
        known.filter { it.deviceIdentifier != SIMULATOR_ID }.forEach { device ->
            val status = logCiqCall(tag, callback, "getDeviceStatus(${device.friendlyName})") {
                ciq.getDeviceStatus(device)
            }
            if (status == IQDevice.IQDeviceStatus.CONNECTED) {
                callback.onLog(tag, "[SERVICE] Selected connected real device: ${device.friendlyName}", LogLevel.INFO)
                return device
            }
        }
        
        // CRITICAL: Any real device as second choice
        val realDevice = known.find { it.deviceIdentifier != SIMULATOR_ID }
        if (realDevice != null) {
            callback.onLog(tag, "[SERVICE] Selected real device: ${realDevice.friendlyName}", LogLevel.INFO)
            return realDevice
        }
        
        // CRITICAL: Simulator only as last resort
        val simulator = known.find { it.deviceIdentifier == SIMULATOR_ID }
        if (simulator != null) {
            callback.onLog(tag, "[SERVICE] Using simulator as fallback: ${simulator.friendlyName}", LogLevel.WARN)
            return simulator
        }
        
        callback.onLog(tag, "[SERVICE] No suitable device found", LogLevel.ERROR)
        return null
    }
    
    private fun sendStatusQuery(
        context: Context,
        tag: String,
        ciq: ConnectIQ,
        device: IQDevice,
        callback: StatusQueryCallback
    ) {
        callback.onLog(tag, "[SERVICE] Sending status query to ${device.friendlyName}", LogLevel.INFO)
        
        val app = IQApp(APP_UUID)
        val timeoutHandler = Handler(Looper.getMainLooper())
        val timeoutRunnable = Runnable {
            callback.onLog(tag, "[SERVICE] Query timeout after ${QUERY_TIMEOUT_MS}ms", LogLevel.ERROR)
            callback.onFailure("Query timeout", "No response within ${QUERY_TIMEOUT_MS}ms from ${device.friendlyName}")
            cleanup(context, ciq)
            currentOperation = null
        }
        timeoutHandler.postDelayed(timeoutRunnable, QUERY_TIMEOUT_MS)
        
        // CRITICAL: Register for response BEFORE sending message
        logCiqCall(tag, callback, "registerForAppEvents(${device.friendlyName})") {
            ciq.registerForAppEvents(device, app, object : IQApplicationEventListener {
                override fun onMessageReceived(
                    dev: IQDevice,
                    app: IQApp,
                    message: List<Any>,
                    status: IQMessageStatus
                ) {
                    timeoutHandler.removeCallbacks(timeoutRunnable)
                    callback.onLog(tag, "[EVENT] Response received from ${dev.friendlyName}: status=$status, message=$message", LogLevel.INFO)
                    
                    val payload = if (message.isNotEmpty()) message.joinToString(",") else "empty"
                    val debug = buildResponseDebugInfo(dev, status, message)
                    
                    callback.onSuccess(payload, debug)
                    cleanup(context, ciq)
                    currentOperation = null
                }
            })
        }
        
        // CRITICAL: Send query message after registration
        logCiqCall(tag, callback, "sendMessage(${device.friendlyName})") {
            ciq.sendMessage(device, app, "status?", object : ConnectIQ.IQSendMessageListener {
                override fun onMessageStatus(dev: IQDevice, app: IQApp, status: IQMessageStatus) {
                    callback.onLog(tag, "[SERVICE] Send message status: $status to ${dev.friendlyName}", LogLevel.DEBUG)
                    
                    if (status != IQMessageStatus.SUCCESS) {
                        timeoutHandler.removeCallbacks(timeoutRunnable)
                        callback.onLog(tag, "[SERVICE] Message send failed with status: $status", LogLevel.ERROR)
                        callback.onFailure("Send failed: $status", "Message send failed to ${dev.friendlyName}")
                        cleanup(context, ciq)
                        currentOperation = null
                    } else {
                        callback.onLog(tag, "[SERVICE] Message sent successfully, waiting for response", LogLevel.DEBUG)
                    }
                }
            })
        }
    }
    
    // CRITICAL: Comprehensive API call logging with central tag
    private inline fun <T> logCiqCall(
        tag: String,
        callback: StatusQueryCallback,
        operation: String,
        block: () -> T
    ): T? {
        val start = System.currentTimeMillis()
        val thread = Thread.currentThread().name
        
        return try {
            callback.onLog(tag, "[CIQ-API] $operation BEGIN (thread=$thread, op=$currentOperation)", LogLevel.DEBUG)
            val result = block()
            val duration = System.currentTimeMillis() - start
            callback.onLog(tag, "[CIQ-API] $operation SUCCESS in ${duration}ms -> $result", LogLevel.DEBUG)
            result
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - start
            callback.onLog(tag, "[CIQ-API] $operation ERROR in ${duration}ms: ${e.javaClass.simpleName}", LogLevel.ERROR)
            callback.onLog(tag, "[CIQ-API] $operation EXCEPTION: ${e.message}", LogLevel.ERROR)
            null
        }
    }
    
    private fun dumpDevices(tag: String, ciq: ConnectIQ, callback: StatusQueryCallback) {
        val known = logCiqCall(tag, callback, "knownDevices") { ciq.knownDevices } ?: emptyList()
        val connected = logCiqCall(tag, callback, "connectedDevices") { ciq.connectedDevices } ?: emptyList()
        
        callback.onLog(tag, "[CIQ-API] Device dump: known=${known.size}, connected=${connected.size}", LogLevel.INFO)
        
        known.forEachIndexed { idx, device ->
            val status = logCiqCall(tag, callback, "getDeviceStatus(${device.friendlyName})") {
                ciq.getDeviceStatus(device)
            }
            val deviceType = if (device.deviceIdentifier == SIMULATOR_ID) "SIMULATOR" else "REAL"
            callback.onLog(tag, "[CIQ-API] device[$idx]: ${device.friendlyName} ($deviceType, id=${device.deviceIdentifier}) -> status=$status", LogLevel.INFO)
        }
    }
    
    private fun buildDeviceDebugInfo(known: List<IQDevice>, connected: List<IQDevice>, status: String): String {
        return buildString {
            appendLine("Status: $status")
            appendLine("Known devices: ${known.size}")
            appendLine("Connected devices: ${connected.size}")
            appendLine("App UUID: $APP_UUID")
            appendLine("Operation: $currentOperation")
            if (known.isNotEmpty()) {
                appendLine("Device details:")
                known.forEach { d ->
                    val type = if (d.deviceIdentifier == SIMULATOR_ID) "SIMULATOR" else "REAL"
                    appendLine("  - ${d.friendlyName} ($type, ${d.deviceIdentifier})")
                }
            }
        }
    }
    
    private fun buildResponseDebugInfo(device: IQDevice, status: IQMessageStatus, message: List<Any>): String {
        return buildString {
            appendLine("Device: ${device.friendlyName}")
            appendLine("Message status: $status")
            appendLine("Response: $message")
            appendLine("App UUID: $APP_UUID")
            appendLine("Operation: $currentOperation")
        }
    }
    
    private fun cleanup(context: Context, ciq: ConnectIQ) {
        Handler(Looper.getMainLooper()).postDelayed({
            try {
                ciq.shutdown(context)
            } catch (e: Exception) {
                Log.w("ConnectIQService", "Cleanup error: ${e.message}")
            }
        }, 100)
    }
}