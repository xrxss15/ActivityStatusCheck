package net.xrxss15

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.garmin.android.connectiq.ConnectIQ
import com.garmin.android.connectiq.ConnectIQ.ConnectIQListener
import com.garmin.android.connectiq.ConnectIQ.IQApplicationEventListener
import com.garmin.android.connectiq.ConnectIQ.IQMessageStatus
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * CONNECTIQ SERVICE FOR TASKER INTEGRATION WITH ENHANCED LOGGING
 * 
 * Manages ConnectIQ SDK initialization, device discovery, and CIQ app communication.
 * Optimized for immediate response forwarding without timeout handling.
 * 
 * KEY DESIGN PRINCIPLES:
 * - Singleton pattern for consistent state management
 * - Immediate response callbacks for Tasker integration  
 * - No artificial timeouts - processes responses as they arrive
 * - Robust error handling and enhanced categorized logging
 * - Background-friendly initialization
 * 
 * TASKER INTEGRATION:
 * - Responds to trigger intents from Tasker
 * - Reports connected devices in format "device1/device2/device3"
 * - Forwards CIQ app responses immediately via callbacks
 * - All operations logged with clear categories for debugging
 */
class ConnectIQService private constructor() {

    companion object {
        private const val APP_UUID = "7b408c6e-fc9c-4080-bad4-97a3557fc995"
        private const val DISCOVERY_DELAY_MS = 500L
        private const val KNOWN_SIMULATOR_ID = 12345L
        
        @Volatile private var INSTANCE: ConnectIQService? = null
        
        fun getInstance(): ConnectIQService =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: ConnectIQService().also { INSTANCE = it }
            }
    }

    data class QueryResult(
        val success: Boolean,
        val payload: String,
        val debug: String,
        val connectedRealDevices: Int
    )

    // ConnectIQ SDK state
    private var connectIQ: ConnectIQ? = null
    private val initialized = AtomicBoolean(false)
    private val initInProgress = AtomicBoolean(false)
    private var appContext: Context? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // Event listener management
    private val deviceListeners = mutableMapOf<String, (IQDevice, IQDevice.IQDeviceStatus) -> Unit>()
    private val appListeners = mutableMapOf<String, IQApplicationEventListener>()
    
    // Tasker integration callbacks
    @Volatile private var logSink: ((String) -> Unit)? = null
    @Volatile private var responseCallback: ((String, String, String) -> Unit)? = null

    fun registerLogSink(sink: ((String) -> Unit)?) { 
        logSink = sink 
        logInfo("LOGGING", "Log sink ${if (sink != null) "registered" else "unregistered"}")
    }
    
    /**
     * Sets callback for immediate response forwarding to Tasker
     * Callback parameters: (response, deviceName, timestamp)
     */
    fun setResponseCallback(callback: ((String, String, String) -> Unit)?) {
        responseCallback = callback
        logInfo("TASKER_CALLBACK", "Response callback ${if (callback != null) "registered" else "unregistered"}")
    }

    // Enhanced logging methods with clear categories and formatting
    private fun ts(): String = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())

    fun log(message: String) { 
        logSink?.invoke(message) 
        android.util.Log.i("ConnectIQService", message)
    }

    private fun logInfo(category: String, message: String) {
        val logMsg = "[${ts()}] ℹ️ [SERVICE.$category] $message"
        log(logMsg)
    }

    private fun logSuccess(category: String, message: String) {
        val logMsg = "[${ts()}] ✅ [SERVICE.$category] $message"
        log(logMsg)
    }

    private fun logWarning(category: String, message: String) {
        val logMsg = "[${ts()}] ⚠️ [SERVICE.$category] $message"
        log(logMsg)
        android.util.Log.w("ConnectIQService", logMsg)
    }

    private fun logError(category: String, message: String) {
        val logMsg = "[${ts()}] ❌ [SERVICE.$category] $message"
        log(logMsg)
        android.util.Log.e("ConnectIQService", logMsg)
    }

    private fun logDebug(category: String, message: String) {
        val logMsg = "[${ts()}] 🔍 [SERVICE.$category] $message"
        log(logMsg)
    }

    fun hasRequiredPermissions(ctx: Context): Boolean {
        val needs = mutableListOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            needs.add(android.Manifest.permission.BLUETOOTH_SCAN)
            needs.add(android.Manifest.permission.BLUETOOTH_CONNECT)
        }
        
        val granted = needs.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
        val missing = needs.filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }
        
        if (granted) {
            logSuccess("PERMISSIONS", "All required permissions granted")
        } else {
            logError("PERMISSIONS", "Missing permissions: ${missing.joinToString(", ")}")
        }
        
        return granted
    }

    @Synchronized
    private fun ensureInitialized(context: Context, showUi: Boolean): Boolean {
        if (initialized.get()) {
            logInfo("INIT", "ConnectIQ already initialized - skipping")
            return true
        }

        val onMain = Looper.myLooper() == Looper.getMainLooper()
        
        if (onMain) {
            if (initInProgress.get()) {
                logWarning("INIT", "Initialization already in progress on main thread - returning false")
                return false
            }

            logInfo("INIT", "Starting ConnectIQ initialization on main thread")
            initInProgress.set(true)
            appContext = context.applicationContext
            
            val ciq = ConnectIQ.getInstance(appContext, ConnectIQ.IQConnectType.WIRELESS)
            logDebug("INIT", "ConnectIQ.getInstance(WIRELESS) -> $ciq")
            connectIQ = ciq

            try {
                ciq.initialize(appContext, showUi, object : ConnectIQListener {
                    override fun onSdkReady() {
                        logSuccess("INIT", "ConnectIQ SDK ready")
                        initialized.set(true)
                        initInProgress.set(false)
                        refreshAndRegisterDevices()
                    }

                    override fun onInitializeError(status: ConnectIQ.IQSdkErrorStatus) {
                        logError("INIT", "ConnectIQ initialization error: $status")
                        initialized.set(false)
                        initInProgress.set(false)
                    }

                    override fun onSdkShutDown() {
                        logWarning("INIT", "ConnectIQ SDK shut down")
                        initialized.set(false)
                    }
                })
            } catch (e: Exception) {
                logError("INIT", "Initialize threw exception: ${e.message}")
                initInProgress.set(false)
            }

            return false // Main thread init is async
        }

        // Background thread initialization with timeout
        if (initInProgress.get()) {
            logInfo("INIT", "Waiting for existing initialization to complete...")
            repeat(40) {
                if (initialized.get()) {
                    logSuccess("INIT", "Initialization completed while waiting")
                    return true
                }
                Thread.sleep(100)
            }
            logWarning("INIT", "Timeout waiting for initialization")
            return initialized.get()
        }

        logInfo("INIT", "Starting ConnectIQ initialization on background thread")
        initInProgress.set(true)
        
        try {
            appContext = context.applicationContext
            val ciq = ConnectIQ.getInstance(appContext, ConnectIQ.IQConnectType.WIRELESS)
            logDebug("INIT", "ConnectIQ.getInstance(WIRELESS) -> $ciq")
            connectIQ = ciq

            val latch = CountDownLatch(1)
            val ok = AtomicBoolean(false)

            mainHandler.post {
                try {
                    ciq.initialize(appContext, showUi, object : ConnectIQListener {
                        override fun onSdkReady() {
                            logSuccess("INIT", "ConnectIQ SDK ready (background)")
                            ok.set(true)
                            latch.countDown()
                        }

                        override fun onInitializeError(status: ConnectIQ.IQSdkErrorStatus) {
                            logError("INIT", "ConnectIQ initialization error (background): $status")
                            ok.set(false)
                            latch.countDown()
                        }

                        override fun onSdkShutDown() {
                            logWarning("INIT", "ConnectIQ SDK shut down (background)")
                            initialized.set(false)
                        }
                    })
                } catch (e: Exception) {
                    logError("INIT", "Background initialize threw exception: ${e.message}")
                    ok.set(false)
                    latch.countDown()
                }
            }

            val completed = latch.await(8, TimeUnit.SECONDS)
            if (!completed) {
                logError("INIT", "Background initialization timeout after 8 seconds")
                return false
            }
            
            if (!ok.get()) {
                logError("INIT", "Background initialization failed")
                return false
            }

            refreshAndRegisterDevices()
            initialized.set(true)
            logSuccess("INIT", "Background initialization completed successfully")
            return true
            
        } finally {
            initInProgress.set(false)
        }
    }

    fun getConnectedRealDevices(): List<IQDevice> {
        val ciq = connectIQ ?: run {
            logWarning("DEVICES", "ConnectIQ not initialized - returning empty device list")
            return emptyList()
        }
        
        val connected = try { 
            ciq.connectedDevices 
        } catch (e: Exception) {
            logError("DEVICES", "Failed to get connected devices: ${e.message}")
            emptyList<IQDevice>()
        }

        val real = connected.filter { isRealDevice(it) }
        
        if (real.isNotEmpty()) {
            val names = real.joinToString(", ") { it.friendlyName ?: "Unnamed" }
            logSuccess("DEVICES", "Found ${real.size} real connected device(s): $names")
            real.forEach { device ->
                logDebug("DEVICE_DETAIL", "  • ${device.friendlyName} (ID: ${device.deviceIdentifier}, Status: ${device.status})")
            }
        } else {
            if (connected.isNotEmpty()) {
                logWarning("DEVICES", "Found ${connected.size} connected device(s) but none are real devices (all simulators)")
            } else {
                logWarning("DEVICES", "No connected devices found")
            }
        }

        return real
    }

    private fun isRealDevice(d: IQDevice): Boolean {
        val isReal = d.deviceIdentifier != KNOWN_SIMULATOR_ID &&
                !d.friendlyName.orEmpty().contains("simulator", true)
        
        if (!isReal) {
            logDebug("DEVICE_FILTER", "Filtered out simulator device: ${d.friendlyName} (${d.deviceIdentifier})")
        }
        
        return isReal
    }

    fun refreshAndRegisterDevices() {
        val ciq = connectIQ ?: run {
            logWarning("DEVICE_REFRESH", "ConnectIQ not initialized - cannot refresh devices")
            return
        }
        
        logInfo("DEVICE_REFRESH", "Starting device discovery and registration...")
        
        try { 
            Thread.sleep(DISCOVERY_DELAY_MS) 
        } catch (_: InterruptedException) {
            logWarning("DEVICE_REFRESH", "Discovery delay interrupted")
        }

        val all = try { 
            ciq.knownDevices 
        } catch (e: Exception) {
            logError("DEVICE_REFRESH", "Failed to get known devices: ${e.message}")
            emptyList<IQDevice>()
        }

        val candidates = all.filter { it.deviceIdentifier != KNOWN_SIMULATOR_ID }
        logInfo("DEVICE_REFRESH", "Found ${all.size} known devices, ${candidates.size} are non-simulator candidates")

        candidates.forEach { device ->
            val key = "${device.deviceIdentifier}:${device.friendlyName}"
            if (!deviceListeners.containsKey(key)) {
                val listener: (IQDevice, IQDevice.IQDeviceStatus) -> Unit = { d, status ->
                    logInfo("DEVICE_EVENT", "${d.friendlyName} status changed to: $status")
                }

                try {
                    ciq.registerForDeviceEvents(device) { d, status -> listener(d, status) }
                    deviceListeners[key] = listener
                    logSuccess("DEVICE_REGISTRATION", "Registered events for ${device.friendlyName}")
                } catch (e: Exception) {
                    logError("DEVICE_REGISTRATION", "Failed to register events for ${device.friendlyName}: ${e.message}")
                }
            } else {
                logDebug("DEVICE_REGISTRATION", "Already registered events for ${device.friendlyName}")
            }
        }
    }

    /**
     * TASKER INTEGRATION: Query activity status with immediate response forwarding
     * 
     * No timeout handling - responses are forwarded immediately via callback
     * when received from CIQ app. This ensures Tasker gets responses as fast
     * as possible without artificial delays.
     */
    fun queryActivityStatus(
        context: Context,
        selected: IQDevice? = null,
        showUiIfInitNeeded: Boolean = false
    ): QueryResult {
        val ctx = context.applicationContext
        
        logInfo("QUERY_START", "═══════════════════════════════════════")
        logInfo("QUERY_START", "Starting activity status query...")
        logInfo("QUERY_START", "═══════════════════════════════════════")
        
        if (!hasRequiredPermissions(ctx)) {
            logError("QUERY_PERMISSIONS", "Cannot proceed - missing required permissions")
            return QueryResult(false, "", "[ERROR] Missing required permissions", 0)
        }

        if (!ensureInitialized(ctx, showUiIfInitNeeded)) {
            logError("QUERY_INIT", "Cannot proceed - ConnectIQ not initialized")
            return QueryResult(false, "", "[ERROR] ConnectIQ not initialized", 0)
        }

        val ciq = connectIQ!!
        refreshAndRegisterDevices()
        val devices = getConnectedRealDevices()
        val target = selected ?: devices.firstOrNull()

        if (target == null) {
            logError("QUERY_TARGET", "No connected real device found for query")
            return QueryResult(false, "", "[ERROR] No connected real device found", 0)
        }

        logInfo("QUERY_TARGET", "Using target device: ${target.friendlyName} (${target.deviceIdentifier})")

        // Check if CIQ app is installed
        logInfo("APP_CHECK", "Verifying CIQ app installation...")
        val installed = AtomicBoolean(false)
        val infoLatch = CountDownLatch(1)

        try {
            ciq.getApplicationInfo(APP_UUID, target, object : ConnectIQ.IQApplicationInfoListener {
                override fun onApplicationInfoReceived(app: IQApp) {
                    logSuccess("APP_CHECK", "CIQ app found: ${app.applicationId} on ${target.friendlyName}")
                    installed.set(true)
                    infoLatch.countDown()
                }

                override fun onApplicationNotInstalled(applicationId: String) {
                    logError("APP_CHECK", "CIQ app not installed: $applicationId on ${target.friendlyName}")
                    installed.set(false)
                    infoLatch.countDown()
                }
            })
        } catch (e: Exception) {
            logError("APP_CHECK", "Exception checking app installation: ${e.message}")
            installed.set(false)
            infoLatch.countDown()
        }

        val appCheckCompleted = infoLatch.await(3, TimeUnit.SECONDS)
        if (!appCheckCompleted) {
            logError("APP_CHECK", "Timeout checking app installation")
            return QueryResult(false, "", "[ERROR] App installation check timeout", devices.size)
        }
        
        if (!installed.get()) {
            logError("APP_CHECK", "CIQ app not installed or unavailable")
            return QueryResult(false, "", "[ERROR] CIQ app not installed or unknown", devices.size)
        }

        val app = IQApp(APP_UUID)
        val appKey = "${target.deviceIdentifier}:$APP_UUID"

        // Register for immediate response forwarding to Tasker
        logInfo("RESPONSE_SETUP", "Setting up immediate response callback for Tasker")
        val appListener = IQApplicationEventListener { device, iqApp, messages, status ->
            val rxTime = ts()
            val payload = if (messages.isNullOrEmpty()) "" else messages.joinToString("|") { it.toString() }
            
            logSuccess("CIQ_RESPONSE", "Response received from ${device.friendlyName}")
            logInfo("CIQ_RESPONSE", "Device: ${device.friendlyName} (${device.deviceIdentifier})")
            logInfo("CIQ_RESPONSE", "App: ${iqApp.applicationId}")
            logInfo("CIQ_RESPONSE", "Status: $status")
            logInfo("CIQ_RESPONSE", "Message count: ${messages?.size ?: 0}")
            logInfo("CIQ_RESPONSE", "Payload: '$payload'")
            logInfo("CIQ_RESPONSE", "Timestamp: $rxTime")
            
            if (!messages.isNullOrEmpty()) {
                // IMMEDIATELY forward response to Tasker via callback
                responseCallback?.let { callback ->
                    logInfo("TASKER_FORWARD", "📤 Forwarding response to Tasker via callback")
                    logInfo("TASKER_FORWARD", "Response: '$payload'")
                    logInfo("TASKER_FORWARD", "Device: '${device.friendlyName ?: "Unknown"}'")
                    logInfo("TASKER_FORWARD", "Timestamp: '$rxTime'")
                    callback(payload, device.friendlyName ?: "Unknown", rxTime)
                    logSuccess("TASKER_FORWARD", "Response forwarded to Tasker successfully")
                } ?: run {
                    logWarning("TASKER_FORWARD", "No response callback registered - cannot forward to Tasker")
                }
            }
        }

        try {
            ciq.registerForAppEvents(target, app, appListener)
            appListeners[appKey] = appListener
            logSuccess("APP_LISTENER", "Registered app event listener for ${target.friendlyName}")
        } catch (e: Exception) {
            logError("APP_LISTENER", "Failed to register app listener: ${e.message}")
        }

        // Send query to CIQ app
        val txTime = ts()
        logInfo("MESSAGE_SEND", "📤 Sending query message to CIQ app...")
        logInfo("MESSAGE_SEND", "Target: ${target.friendlyName} (${target.deviceIdentifier})")
        logInfo("MESSAGE_SEND", "App UUID: $APP_UUID")
        logInfo("MESSAGE_SEND", "Payload: 'status?'")
        logInfo("MESSAGE_SEND", "Timestamp: $txTime")

        try {
            ciq.sendMessage(target, app, listOf("status?"), object : ConnectIQ.IQSendMessageListener {
                override fun onMessageStatus(device: IQDevice, iqApp: IQApp, status: IQMessageStatus) {
                    when (status) {
                        IQMessageStatus.SUCCESS -> {
                            logSuccess("MESSAGE_ACK", "Message sent successfully to ${device.friendlyName}")
                        }
                        IQMessageStatus.FAILURE_UNKNOWN -> {
                            logError("MESSAGE_ACK", "Unknown failure sending to ${device.friendlyName}")
                        }
                        IQMessageStatus.FAILURE_INVALID_DEVICE -> {
                            logError("MESSAGE_ACK", "Invalid device: ${device.friendlyName}")
                        }
                        IQMessageStatus.FAILURE_DEVICE_NOT_CONNECTED -> {
                            logError("MESSAGE_ACK", "Device not connected: ${device.friendlyName}")
                        }
                        IQMessageStatus.FAILURE_MESSAGE_TOO_LARGE -> {
                            logError("MESSAGE_ACK", "Message too large for ${device.friendlyName}")
                        }
                        else -> {
                            logWarning("MESSAGE_ACK", "Message status: $status for ${device.friendlyName}")
                        }
                    }
                }
            })
        } catch (e: Exception) {
            logError("MESSAGE_SEND", "Exception sending message: ${e.message}")
        }

        // Return immediately - responses will be handled by callback
        val debug = buildString {
            appendLine("target_device=${target.friendlyName} (${target.deviceIdentifier})")
            appendLine("device_status=${target.status}")
            appendLine("app_uuid=$APP_UUID")
            appendLine("query_message=status?")
            appendLine("send_timestamp=$txTime")
            appendLine("awaiting_response_via_callback=true")
            appendLine("total_connected_devices=${devices.size}")
            appendLine("callback_registered=${responseCallback != null}")
        }

        logSuccess("QUERY_COMPLETE", "Query message sent successfully")
        logInfo("QUERY_COMPLETE", "Waiting for CIQ app response via callback...")
        logInfo("QUERY_COMPLETE", "═══════════════════════════════════════")

        return QueryResult(true, "Query sent - awaiting response via callback", debug, devices.size)
    }
}