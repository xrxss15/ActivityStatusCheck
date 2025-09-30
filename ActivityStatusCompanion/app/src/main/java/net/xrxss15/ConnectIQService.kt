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
 * ConnectIQ Service - Singleton for ConnectIQ SDK Management
 * 
 * This service manages all interactions with the Garmin ConnectIQ SDK:
 * - SDK initialization (both UI and headless modes)
 * - Device discovery and management
 * - CIQ app communication and messaging
 * - Response callback handling
 * 
 * Design Principles:
 * - **Singleton Pattern**: Ensures consistent state across app
 * - **Thread Safety**: Uses atomic variables and proper synchronization
 * - **Battery Efficiency**: Minimal resource usage, no polling
 * - **Dual Mode Support**: Works in both debug (MainActivity) and headless (Worker) modes
 * 
 * Initialization:
 * - For MainActivity: Uses async initialization with UI option
 * - For Worker: Uses synchronous initialization without UI (headless)
 * 
 * Response Handling:
 * - Immediate callback when CIQ app responds (no internal timeout)
 * - Timeout handling is responsibility of caller (Worker uses AlarmManager)
 * 
 * @see MainActivity for debug mode usage
 * @see ConnectIQQueryWorker for headless mode usage
 */
class ConnectIQService private constructor() {

    companion object {
        /**
         * CIQ app UUID to communicate with.
         * This must match the UUID of the ConnectIQ app installed on the device.
         */
        private const val APP_UUID = "7b408c6e-fc9c-4080-bad4-97a3557fc995"
        
        /**
         * Delay before device discovery to allow SDK to stabilize.
         */
        private const val DISCOVERY_DELAY_MS = 500L
        
        /**
         * Known simulator device ID to filter out.
         */
        private const val KNOWN_SIMULATOR_ID = 12345L
        
        /**
         * Timeout for worker initialization (not CIQ response timeout).
         */
        private const val WORKER_INIT_TIMEOUT_MS = 8000L // 8 seconds for SDK init
        
        @Volatile private var INSTANCE: ConnectIQService? = null
        
        /**
         * Gets singleton instance of ConnectIQService.
         * 
         * Thread-safe lazy initialization.
         * 
         * @return Singleton instance
         */
        fun getInstance(): ConnectIQService =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: ConnectIQService().also { INSTANCE = it }
            }
    }

    /**
     * Result data class for query operations.
     * 
     * @property success Whether the query was successful
     * @property payload Response payload or error message
     * @property debug Debug information string
     * @property connectedRealDevices Number of connected real devices
     */
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
    
    // Callback management
    @Volatile private var logSink: ((String) -> Unit)? = null
    @Volatile private var responseCallback: ((String, String, String) -> Unit)? = null

    /**
     * Registers a log sink for receiving log messages.
     * 
     * Used by MainActivity to display logs in UI.
     * 
     * @param sink Log sink function or null to unregister
     */
    fun registerLogSink(sink: ((String) -> Unit)?) { 
        logSink = sink 
    }
    
    /**
     * Sets callback for CIQ app responses.
     * 
     * This callback is invoked IMMEDIATELY when a CIQ app responds.
     * No timeout handling - responses are forwarded as soon as received.
     * 
     * @param callback Response callback (response, deviceName, timestamp) or null to clear
     */
    fun setResponseCallback(callback: ((String, String, String) -> Unit)?) {
        responseCallback = callback
    }

    /**
     * Formats current timestamp for logging.
     * 
     * @return Formatted timestamp string (HH:mm:ss.SSS)
     */
    private fun ts(): String = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())

    /**
     * Logs message to registered log sink.
     * 
     * @param message Message to log
     */
    fun log(message: String) { 
        logSink?.invoke(message) 
        android.util.Log.i("ConnectIQService", message)
    }

    /**
     * Logs informational message with category.
     */
    private fun logInfo(category: String, message: String) {
        val logMsg = "[${ts()}] ℹ️ [SERVICE.$category] $message"
        log(logMsg)
    }

    /**
     * Logs success message with category.
     */
    private fun logSuccess(category: String, message: String) {
        val logMsg = "[${ts()}] ✅ [SERVICE.$category] $message"
        log(logMsg)
    }

    /**
     * Logs error message with category.
     */
    private fun logError(category: String, message: String) {
        val logMsg = "[${ts()}] ❌ [SERVICE.$category] $message"
        log(logMsg)
        android.util.Log.e("ConnectIQService", logMsg)
    }
    
    /**
     * Logs warning message with category.
     */
    private fun logWarning(category: String, message: String) {
        val logMsg = "[${ts()}] ⚠️ [SERVICE.$category] $message"
        log(logMsg)
        android.util.Log.w("ConnectIQService", logMsg)
    }

    /**
     * Checks if all required permissions are granted.
     * 
     * Required permissions:
     * - ACCESS_FINE_LOCATION (all versions)
     * - BLUETOOTH_SCAN (Android 12+)
     * - BLUETOOTH_CONNECT (Android 12+)
     * 
     * @param ctx Context to check permissions against
     * @return true if all required permissions granted, false otherwise
     */
    fun hasRequiredPermissions(ctx: Context): Boolean {
        val needs = mutableListOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            needs.add(android.Manifest.permission.BLUETOOTH_SCAN)
            needs.add(android.Manifest.permission.BLUETOOTH_CONNECT)
        }
        
        return needs.all {
            ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Initializes ConnectIQ SDK for Worker (headless) context.
     * 
     * This is a SYNCHRONOUS initialization designed specifically for WorkManager.
     * It blocks until SDK is ready or timeout occurs.
     * 
     * Unlike UI initialization, this:
     * - Does not show UI dialogs
     * - Blocks until complete
     * - Has timeout protection
     * - Returns immediately with success/failure
     * 
     * Note: This timeout (8s) is for SDK initialization only, NOT for
     * waiting for CIQ app responses (which uses 5-minute AlarmManager timeout).
     * 
     * @param context Application context
     * @return true if initialization successful, false otherwise
     */
    fun initializeForWorker(context: Context): Boolean {
        if (initialized.get()) {
            logInfo("WORKER_INIT", "ConnectIQ already initialized - reusing")
            return true
        }

        logInfo("WORKER_INIT", "Starting synchronous initialization for headless worker")
        
        appContext = context.applicationContext
        val ciq = ConnectIQ.getInstance(appContext, ConnectIQ.IQConnectType.WIRELESS)
        connectIQ = ciq
        
        val initLatch = CountDownLatch(1)
        val initSuccess = AtomicBoolean(false)
        
        // Post initialization to main thread (SDK requirement)
        mainHandler.post {
            try {
                ciq.initialize(appContext, false, object : ConnectIQListener {
                    override fun onSdkReady() {
                        logSuccess("WORKER_INIT", "ConnectIQ SDK ready")
                        initSuccess.set(true)
                        initialized.set(true)
                        initLatch.countDown()
                    }

                    override fun onInitializeError(status: ConnectIQ.IQSdkErrorStatus) {
                        logError("WORKER_INIT", "ConnectIQ initialization failed: $status")
                        initSuccess.set(false)
                        initLatch.countDown()
                    }

                    override fun onSdkShutDown() {
                        initialized.set(false)
                    }
                })
            } catch (e: Exception) {
                logError("WORKER_INIT", "Initialization exception: ${e.message}")
                initSuccess.set(false)
                initLatch.countDown()
            }
        }
        
        return try {
            // Wait for initialization with timeout
            val completed = initLatch.await(WORKER_INIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            
            if (!completed) {
                logError("WORKER_INIT", "Initialization timeout after ${WORKER_INIT_TIMEOUT_MS / 1000}s")
                return false
            }
            
            if (initSuccess.get()) {
                logSuccess("WORKER_INIT", "Initialization completed successfully")
                refreshAndRegisterDevices()
                true
            } else {
                logError("WORKER_INIT", "Initialization failed")
                false
            }
            
        } catch (e: InterruptedException) {
            logError("WORKER_INIT", "Initialization interrupted: ${e.message}")
            false
        }
    }

    /**
     * Gets list of connected real devices (excludes simulators).
     * 
     * Real devices are identified by:
     * - Device ID not matching known simulator ID
     * - Device name not containing "simulator"
     * 
     * @return List of connected real IQDevices
     */
    fun getConnectedRealDevices(): List<IQDevice> {
        val ciq = connectIQ ?: return emptyList()
        
        val connected = try { 
            ciq.connectedDevices 
        } catch (e: Exception) {
            logError("DEVICES", "Failed to get connected devices: ${e.message}")
            emptyList<IQDevice>()
        }

        return connected.filter { isRealDevice(it) }
    }

    /**
     * Checks if device is a real device (not simulator).
     * 
     * @param d Device to check
     * @return true if real device, false if simulator
     */
    private fun isRealDevice(d: IQDevice): Boolean {
        return d.deviceIdentifier != KNOWN_SIMULATOR_ID &&
                !d.friendlyName.orEmpty().contains("simulator", ignoreCase = true)
    }

    /**
     * Refreshes device list and registers for device events.
     * 
     * This method:
     * 1. Waits briefly for SDK to stabilize
     * 2. Gets list of known devices
     * 3. Registers listeners for device status changes
     * 
     * Listeners are registered only once per device to avoid duplicates.
     */
    fun refreshAndRegisterDevices() {
        val ciq = connectIQ ?: return
        
        // Brief delay to allow SDK to stabilize
        try { 
            Thread.sleep(DISCOVERY_DELAY_MS) 
        } catch (_: InterruptedException) {}

        val all = try { 
            ciq.knownDevices 
        } catch (e: Exception) {
            emptyList<IQDevice>()
        }

        // Register device listeners (minimal - just status logging)
        val candidates = all.filter { it.deviceIdentifier != KNOWN_SIMULATOR_ID }
        candidates.forEach { device ->
            val key = "${device.deviceIdentifier}:${device.friendlyName}"
            if (!deviceListeners.containsKey(key)) {
                // Minimal listener for battery efficiency - parameters intentionally unused
                val listener: (IQDevice, IQDevice.IQDeviceStatus) -> Unit = { _, _ -> 
                    // Empty listener body - just for registration
                }

                try {
                    ciq.registerForDeviceEvents(device) { d, status -> listener(d, status) }
                    deviceListeners[key] = listener
                } catch (e: Exception) {
                    // Ignore registration failures
                }
            }
        }
    }

    /**
     * Queries activity status from CIQ app on device.
     * 
     * This method:
     * 1. Validates initialization and permissions
     * 2. Selects target device
     * 3. Verifies CIQ app is installed
     * 4. Registers response listener with DETAILED DEBUG LOGGING
     * 5. Sends query message
     * 
     * Note: This method does NOT wait for the response. Response arrives
     * via the callback registered with setResponseCallback(). The 5-minute
     * timeout is handled by the caller (Worker using AlarmManager).
     * 
     * The parameters context and showUiIfInitNeeded are kept for API compatibility
     * but not used in headless mode operations.
     * 
     * @param context Application context (reserved for future use)
     * @param selected Specific device to query, or null to use first available
     * @param showUiIfInitNeeded Whether to show UI dialogs (reserved for future use)
     * @return QueryResult with success status and details
     */
    @Suppress("UNUSED_PARAMETER")
    fun queryActivityStatus(
        context: Context,
        selected: IQDevice? = null,
        showUiIfInitNeeded: Boolean = false
    ): QueryResult {
        // Validate state
        if (!initialized.get()) {
            return QueryResult(false, "SDK not initialized", "[ERROR] ConnectIQ not initialized", 0)
        }

        val ciq = connectIQ!!
        val devices = getConnectedRealDevices()
        val target = selected ?: devices.firstOrNull()

        if (target == null) {
            return QueryResult(false, "No device", "[ERROR] No connected real device found", 0)
        }

        logInfo("QUERY", "═══════════════════════════════════════")
        logInfo("QUERY", "Starting query to ${target.friendlyName}")
        logInfo("QUERY", "Device ID: ${target.deviceIdentifier}")
        logInfo("QUERY", "Device Status: ${target.status}")
        logInfo("QUERY", "═══════════════════════════════════════")

        // Check if CIQ app is installed (3 second timeout)
        val installed = AtomicBoolean(false)
        val infoLatch = CountDownLatch(1)

        logInfo("APP_CHECK", "Checking if CIQ app is installed...")
        logInfo("APP_CHECK", "App UUID: $APP_UUID")

        try {
            ciq.getApplicationInfo(APP_UUID, target, object : ConnectIQ.IQApplicationInfoListener {
                override fun onApplicationInfoReceived(app: IQApp) {
                    logSuccess("APP_CHECK", "CIQ app FOUND on device")
                    logInfo("APP_CHECK", "App ID: ${app.applicationId}")
                    logInfo("APP_CHECK", "App Display Name: ${app.displayName}")
                    logInfo("APP_CHECK", "App Version: ${app.version()}")
                    installed.set(true)
                    infoLatch.countDown()
                }

                override fun onApplicationNotInstalled(applicationId: String) {
                    logError("APP_CHECK", "CIQ app NOT INSTALLED")
                    logError("APP_CHECK", "Missing app ID: $applicationId")
                    installed.set(false)
                    infoLatch.countDown()
                }
            })
        } catch (e: Exception) {
            logError("APP_CHECK", "Exception checking app: ${e.message}")
            installed.set(false)
            infoLatch.countDown()
        }

        if (!infoLatch.await(3, TimeUnit.SECONDS)) {
            logError("APP_CHECK", "App installation check TIMEOUT")
            return QueryResult(false, "App check timeout", "[ERROR] CIQ app check timeout", devices.size)
        }
        
        if (!installed.get()) {
            logError("APP_CHECK", "CIQ app not found or unavailable")
            return QueryResult(false, "App not installed", "[ERROR] CIQ app not found", devices.size)
        }

        val app = IQApp(APP_UUID)
        val appKey = "${target.deviceIdentifier}:$APP_UUID"

        // Register for immediate response callback with COMPREHENSIVE DEBUGGING
        logInfo("LISTENER", "Registering app event listener...")
        logInfo("LISTENER", "Listener key: $appKey")
        
        val appListener = IQApplicationEventListener { device, _, messages, _ ->
            val rxTime = ts()
            
            logSuccess("RESPONSE", "═══════════════════════════════════════")
            logSuccess("RESPONSE", "🎉 CIQ APP RESPONSE RECEIVED!")
            logInfo("RESPONSE", "From device: ${device.friendlyName} (${device.deviceIdentifier})")
            logInfo("RESPONSE", "Receive time: $rxTime")
            logInfo("RESPONSE", "Message count: ${messages?.size ?: 0}")
            
            if (messages.isNullOrEmpty()) {
                logWarning("RESPONSE", "Messages list is EMPTY or NULL")
                logWarning("RESPONSE", "This is unexpected - CIQ app should send data")
            } else {
                logInfo("RESPONSE", "Processing ${messages.size} message(s)...")
                messages.forEachIndexed { index, msg ->
                    logInfo("RESPONSE", "  Message $index: $msg (type: ${msg?.javaClass?.simpleName})")
                }
                
                val payload = messages.joinToString("|") { it.toString() }
                logSuccess("RESPONSE", "Combined payload: '$payload'")
                
                // Immediately forward response via callback
                responseCallback?.let { callback ->
                    logInfo("RESPONSE", "Invoking response callback...")
                    callback(payload, device.friendlyName ?: "Unknown", rxTime)
                    logSuccess("RESPONSE", "Response callback completed")
                } ?: logWarning("RESPONSE", "No response callback registered!")
            }
            logSuccess("RESPONSE", "═══════════════════════════════════════")
        }

        try {
            ciq.registerForAppEvents(target, app, appListener)
            appListeners[appKey] = appListener
            logSuccess("LISTENER", "App event listener registered successfully")
        } catch (e: Exception) {
            logError("LISTENER", "Failed to register listener: ${e.message}")
            return QueryResult(false, "Listener error", "[ERROR] Failed to register app listener", devices.size)
        }

        // Send query message
        logInfo("MESSAGE", "═══════════════════════════════════════")
        logInfo("MESSAGE", "Preparing to send message to CIQ app...")
        logInfo("MESSAGE", "Target device: ${target.friendlyName}")
        logInfo("MESSAGE", "Device status: ${target.status}")
        logInfo("MESSAGE", "Message payload: ['status?']")
        logInfo("MESSAGE", "═══════════════════════════════════════")
        
        try {
            val messageSent = AtomicBoolean(false)
            val sendLatch = CountDownLatch(1)
            
            ciq.sendMessage(target, app, "status?", object : ConnectIQ.IQSendMessageListener {
                override fun onMessageStatus(device: IQDevice, iqApp: IQApp, status: IQMessageStatus) {
                    val success = status == IQMessageStatus.SUCCESS
                    messageSent.set(success)
                    
                    if (success) {
                        logSuccess("MESSAGE", "✅ Message SEND CONFIRMED by ConnectIQ SDK")
                        logInfo("MESSAGE", "Message successfully transmitted to ${device.friendlyName}")
                        logInfo("MESSAGE", "Now waiting for CIQ app to respond...")
                    } else {
                        logError("MESSAGE", "❌ Message send FAILED")
                        logError("MESSAGE", "Status: $status")
                        logError("MESSAGE", "Device: ${device.friendlyName}")
                    }
                    
                    sendLatch.countDown()
                }
            })
            
            logInfo("MESSAGE", "Message send initiated - waiting for confirmation...")
            
            // Wait for send confirmation
            if (!sendLatch.await(5, TimeUnit.SECONDS)) {
                logError("MESSAGE", "Send confirmation TIMEOUT after 5 seconds")
                logError("MESSAGE", "SDK did not confirm message delivery")
                return QueryResult(false, "Send timeout", "[ERROR] Message send confirmation timeout", devices.size)
            }
            
            if (!messageSent.get()) {
                logError("MESSAGE", "Message send was NOT successful")
                return QueryResult(false, "Send failed", "[ERROR] Failed to send message to CIQ app", devices.size)
            }
            
            logSuccess("MESSAGE", "═══════════════════════════════════════")
            logSuccess("MESSAGE", "Message sent successfully!")
            logInfo("MESSAGE", "Listener is active and waiting for response")
            logInfo("MESSAGE", "Response will arrive via registered callback")
            logSuccess("MESSAGE", "═══════════════════════════════════════")
            
            return QueryResult(true, "Query sent - awaiting response", "", devices.size)
            
        } catch (e: Exception) {
            logError("MESSAGE", "Exception sending message: ${e.message}")
            logError("MESSAGE", "Stack trace: ${e.stackTraceToString()}")
            return QueryResult(false, "Send exception", "[ERROR] Exception: ${e.message}", devices.size)
        }
    }
}