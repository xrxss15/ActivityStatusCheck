package net.xrxss15

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.garmin.android.connectiq.ConnectIQ
import com.garmin.android.connectiq.ConnectIQ.ConnectIQListener
import com.garmin.android.connectiq.ConnectIQ.IQApplicationEventListener
import com.garmin.android.connectiq.ConnectIQ.IQSdkErrorStatus
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice

/**
 * Singleton service for managing Garmin ConnectIQ SDK integration.
 * 
 * This service handles:
 * - SDK initialization and lifecycle management
 * - Device discovery and registration
 * - App event listener registration for activity messages
 * - Automatic SDK recovery when service binding is lost
 * - Message parsing and broadcast distribution
 * 
 * The service maintains a single SDK instance across the app lifecycle
 * and provides thread-safe access to all ConnectIQ operations.
 */
class ConnectIQService private constructor() {

    companion object {
        // UUID of the Garmin Connect IQ app to listen to
        private const val APP_UUID = "a3682e8a-8c10-4618-9f36-fd52877df567"
        private const val TAG = "GarminActivityListener.Service"
        
        // Delay to allow device discovery after SDK initialization
        private const val DISCOVERY_DELAY_MS = 500L
        
        // Known simulator device ID to filter out during testing
        private const val KNOWN_SIMULATOR_ID = 12345L

        @Volatile
        private var instance: ConnectIQService? = null

        /**
         * Get the singleton instance of ConnectIQService.
         * Thread-safe lazy initialization.
         */
        fun getInstance(): ConnectIQService {
            return instance ?: synchronized(this) {
                instance ?: ConnectIQService().also { instance = it }
            }
        }
        
        /**
         * Reset the singleton instance, shutting down the SDK properly.
         * Called when app is being completely terminated.
         * 
         * This method:
         * - Sets sdkReady flag to false
         * - Shuts down the ConnectIQ SDK
         * - Clears all listeners and device registrations
         * - Nullifies the singleton instance
         */
        fun resetInstance() {
            synchronized(this) {
                instance?.let {
                    it.sdkReady = false
                    
                    try {
                        // Shutdown SDK BEFORE setting to null
                        val sdk = it.connectIQ
                        val ctx = it.appContext
                        if (sdk != null && ctx != null) {
                            sdk.shutdown(ctx)
                        }
                    } catch (e: Exception) {
                        android.util.Log.e(TAG, "Error during shutdown: ${e.message}")
                    }
                    
                    it.connectIQ = null
                    it.appListeners.clear()
                    it.knownDevices.clear()
                    it.messageCallback = null
                    it.deviceChangeCallback = null
                    it.isReinitializing = false
                    it.appContext = null
                }
                instance = null
            }
        }
    }

    // ConnectIQ SDK instance
    private var connectIQ: ConnectIQ? = null
    
    // Set of currently known/registered devices
    private val knownDevices = mutableSetOf<IQDevice>()
    
    // Map of app event listeners keyed by "deviceId:appUuid"
    private val appListeners = mutableMapOf<String, IQApplicationEventListener>()
    
    // Callback invoked when a message is received from a device
    private var messageCallback: ((String, String, Long) -> Unit)? = null
    
    // Callback invoked when device connection status changes
    private var deviceChangeCallback: (() -> Unit)? = null
    
    // Application context for SDK operations
    private var appContext: Context? = null
    
    // Handler for posting operations on the main thread
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // Flag indicating SDK is fully initialized and ready for use
    @Volatile
    private var sdkReady = false
    
    // Flag to prevent concurrent SDK reinitialization attempts
    @Volatile
    private var isReinitializing = false

    private fun log(msg: String) = android.util.Log.i(TAG, msg)
    private fun logError(msg: String) = android.util.Log.e(TAG, msg)

    /**
     * Initialize the ConnectIQ SDK if not already initialized.
     * 
     * This method:
     * - Checks if SDK is already ready
     * - Creates a new ConnectIQ instance
     * - Registers SDK lifecycle callbacks
     * - Invokes onReady callback when SDK is ready
     * 
     * The SDK initialization is asynchronous. The onReady callback
     * is invoked on the main thread after SDK reports ready status
     * and devices have been discovered.
     * 
     * @param context Application or activity context
     * @param onReady Optional callback invoked when SDK is ready
     */
    fun initializeSdkIfNeeded(context: Context, onReady: (() -> Unit)? = null) {
        appContext = context.applicationContext
        
        if (sdkReady) {
            log("SDK already initialized and ready")
            onReady?.invoke()
            return
        }

        log("Initializing SDK")
        
        connectIQ = ConnectIQ.getInstance(appContext, ConnectIQ.IQConnectType.WIRELESS)
        
        connectIQ?.initialize(appContext, false, object : ConnectIQListener {
            override fun onSdkReady() {
                log("SDK initialized successfully")
                sdkReady = true
                
                // Wait for device discovery before invoking callback
                mainHandler.postDelayed({
                    refreshAndRegisterDevices()
                    onReady?.invoke()
                }, DISCOVERY_DELAY_MS)
            }

            override fun onInitializeError(status: IQSdkErrorStatus?) {
                logError("SDK initialization failed: $status")
                sdkReady = false
            }

            override fun onSdkShutDown() {
                log("SDK shutdown")
                sdkReady = false
            }
        })
    }

    /**
     * Test if SDK is functioning and attempt recovery if not.
     * 
     * This method is called when SDK operations throw exceptions.
     * It tests SDK health by attempting to get connected devices.
     * If the test fails with "SDK not initialized", it starts
     * SdkInitActivity to reinitialize the SDK in the background.
     * 
     * @param context Application context
     * @return true if SDK is healthy, false if recovery was initiated
     */
    private fun testAndRecoverSdk(context: Context): Boolean {
        if (isReinitializing) return false
        
        val sdk = connectIQ ?: return false
        
        try {
            // Test SDK health
            val test = sdk.connectedDevices
            return true
        } catch (e: Exception) {
            if (e.message?.contains("SDK not initialized") == true) {
                synchronized(this) {
                    if (isReinitializing) return false
                    isReinitializing = true
                }
                
                log("SDK service binding lost, starting SdkInitActivity for recovery")
                
                connectIQ = null
                sdkReady = false
                
                try {
                    // Start invisible activity to reinitialize SDK
                    val intent = Intent(context, SdkInitActivity::class.java).apply {
                        action = SdkInitActivity.ACTION_INIT_SDK
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    
                    // Reset reinitialization flag after delay
                    mainHandler.postDelayed({
                        synchronized(this) {
                            isReinitializing = false
                        }
                    }, 5000)
                    
                } catch (e: Exception) {
                    logError("Failed to start SdkInitActivity: ${e.message}")
                    synchronized(this) {
                        isReinitializing = false
                    }
                }
                
                return false
            }
            return false
        }
    }

    /**
     * Check if all required permissions are granted.
     * 
     * Required permissions:
     * - ACCESS_FINE_LOCATION (all versions)
     * - BLUETOOTH_SCAN (Android 12+)
     * - BLUETOOTH_CONNECT (Android 12+)
     * 
     * @param context Application context
     * @return true if all permissions are granted
     */
    fun hasRequiredPermissions(context: Context): Boolean {
        val needs = mutableListOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            needs.add(android.Manifest.permission.BLUETOOTH_SCAN)
            needs.add(android.Manifest.permission.BLUETOOTH_CONNECT)
        }
        return needs.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }

    /**
     * Check if SDK is initialized and ready for operations.
     * 
     * @return true if SDK is ready
     */
    fun isInitialized(): Boolean = sdkReady

    /**
     * Check if a device is a real physical device (not simulator).
     * 
     * @param device Device to check
     * @return true if device is real, false if simulator
     */
    private fun isRealDevice(device: IQDevice): Boolean {
        return device.deviceIdentifier != KNOWN_SIMULATOR_ID &&
                !device.friendlyName.orEmpty().contains("simulator", true)
    }

    /**
     * Get list of connected real devices (excludes simulators).
     * 
     * If SDK operation fails with "SDK not initialized", automatic
     * recovery is attempted via testAndRecoverSdk().
     * 
     * @param context Optional context for SDK recovery
     * @return List of connected real devices, empty if none or error
     */
    fun getConnectedRealDevices(context: Context? = null): List<IQDevice> {
        val sdk = connectIQ ?: return emptyList()
        return try {
            val connected = sdk.connectedDevices ?: emptyList()
            connected.filter { isRealDevice(it) }
        } catch (e: Exception) {
            logError("Error getting devices: ${e.message}")
            
            if (context != null && e.message?.contains("SDK not initialized") == true) {
                testAndRecoverSdk(context)
            }
            
            emptyList()
        }
    }

    /**
     * Refresh known devices and register for device status events.
     * 
     * This method:
     * - Gets all known devices from SDK
     * - Filters out simulator devices
     * - Registers device event listeners for each device
     * 
     * Device events trigger deviceChangeCallback when:
     * - Device connects (CONNECTED)
     * - Device disconnects (NOT_CONNECTED)
     * - Device is unpaired (NOT_PAIRED)
     * 
     * On CONNECTED event, SDK health is tested and recovery
     * is attempted if needed.
     */
    fun refreshAndRegisterDevices() {
        val sdk = connectIQ ?: return
        val ctx = appContext
        
        val all = try {
            sdk.knownDevices
        } catch (e: Exception) {
            emptyList()
        }
        
        val candidates = all?.filter { it.deviceIdentifier != KNOWN_SIMULATOR_ID } ?: emptyList()
        
        candidates.forEach { device ->
            try {
                sdk.registerForDeviceEvents(device) { dev, status ->
                    log("Device ${dev.friendlyName} status changed: $status")
                    
                    when (status) {
                        IQDevice.IQDeviceStatus.CONNECTED -> {
                            // Test SDK health on connect, attempt recovery if needed
                            if (ctx != null && !testAndRecoverSdk(ctx)) {
                                log("SDK recovery initiated after CONNECTED event")
                            } else {
                                deviceChangeCallback?.invoke()
                            }
                        }
                        IQDevice.IQDeviceStatus.NOT_CONNECTED,
                        IQDevice.IQDeviceStatus.NOT_PAIRED -> {
                            deviceChangeCallback?.invoke()
                        }
                        else -> {}
                    }
                }
            } catch (e: Exception) {
                logError("Failed to register device events: ${e.message}")
            }
        }
    }

    /**
     * Register app event listeners for all connected real devices.
     * 
     * This method:
     * - Gets all connected real devices
     * - Updates the knownDevices set
     * - Registers IQApplicationEventListener for each device
     * 
     * This is called by the Worker after SDK initialization to
     * start listening for activity messages from the Garmin app.
     */
    fun registerListenersForAllDevices() {
        val devices = getConnectedRealDevices()
        log("Registering ${devices.size} device(s)")
        
        knownDevices.clear()
        knownDevices.addAll(devices)
        
        devices.forEach { device ->
            registerListenerForDevice(device)
        }
    }

    /**
     * Register app event listener for a specific device.
     * 
     * The listener receives messages from the Garmin app identified
     * by APP_UUID. Messages are parsed and forwarded via:
     * - messageCallback (to Worker for notification updates)
     * - sendMessageBroadcast (to MainActivity and Tasker)
     * 
     * @param device Device to register listener for
     */
    private fun registerListenerForDevice(device: IQDevice) {
        val sdk = connectIQ ?: return
        val app = IQApp(APP_UUID)
        val appKey = "${device.deviceIdentifier}:$APP_UUID"
        
        // Skip if already registered
        if (appListeners.containsKey(appKey)) {
            return
        }
        
        val knownDeviceName = device.friendlyName?.takeIf { it.isNotEmpty() } ?: "Unknown Device"
        
        val appListener = IQApplicationEventListener { dev, _, messages, _ ->
            if (dev == null || messages.isNullOrEmpty()) return@IQApplicationEventListener
            
            messages.forEach { msg ->
                val payload = msg.toString()
                val deviceName = knownDeviceName
                val timestamp = System.currentTimeMillis()
                
                log("Message from $deviceName: $payload")
                
                // Notify Worker via callback
                messageCallback?.invoke(payload, deviceName, timestamp)
                
                // Broadcast to MainActivity and Tasker
                sendMessageBroadcast(payload, deviceName)
            }
        }
        
        try {
            sdk.registerForAppEvents(device, app, appListener)
            appListeners[appKey] = appListener
            log("Registered listener for $knownDeviceName")
        } catch (e: Exception) {
            logError("Failed to register app listener: ${e.message}")
        }
    }

    /**
     * Parse and broadcast an activity message.
     * 
     * Message format: "EVENT_TYPE|timestamp|activity|duration"
     * 
     * Supported event types:
     * - STARTED or ACTIVITY_STARTED: Activity started on device
     * - STOPPED or ACTIVITY_STOPPED: Activity stopped on device
     * 
     * Broadcast action: net.xrxss15.GARMIN_ACTIVITY_LISTENER_EVENT
     * Broadcast extras:
     * - type: "Started" or "Stopped"
     * - device: Device name
     * - time: Activity start time (Unix timestamp in seconds)
     * - activity: Activity type (e.g., "running", "cycling")
     * - duration: Duration in seconds (0 for started events)
     * 
     * @param payload Message payload from Garmin app
     * @param deviceName Name of the device that sent the message
     */
    private fun sendMessageBroadcast(payload: String, deviceName: String) {
        val context = appContext ?: return
        
        val parts = payload.split("|")
        if (parts.size < 4) {
            logError("Invalid payload format: $payload")
            return
        }
        
        val eventType = when (parts[0]) {
            "STARTED", "ACTIVITY_STARTED" -> "Started"
            "STOPPED", "ACTIVITY_STOPPED" -> "Stopped"
            else -> {
                logError("Unknown event type: ${parts[0]}")
                return
            }
        }
        
        try {
            val time = parts[1].toLong()
            val activity = parts[2]
            val duration = parts[3].toInt()
            
            val intent = Intent("net.xrxss15.GARMIN_ACTIVITY_LISTENER_EVENT").apply {
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                putExtra("type", eventType)
                putExtra("device", deviceName)
                putExtra("time", time)
                putExtra("activity", activity)
                putExtra("duration", duration)
            }
            
            context.sendBroadcast(intent)
            log("Broadcast sent: $eventType from $deviceName")
            
        } catch (e: Exception) {
            logError("Failed to parse message: ${e.message}")
        }
    }

    /**
     * Broadcast device list to MainActivity and Tasker.
     * 
     * This is sent when device connection status changes (devices
     * connect or disconnect).
     * 
     * Broadcast action: net.xrxss15.GARMIN_ACTIVITY_LISTENER_EVENT
     * Broadcast extras:
     * - type: "DeviceList"
     * - devices: Device names separated by "/" (e.g., "fenix 7S/Edge 530")
     * 
     * @param devices List of currently connected devices
     */
    private fun sendDeviceListBroadcast(devices: List<IQDevice>) {
        val context = appContext ?: return
        
        val deviceNames = devices.map { it.friendlyName ?: "Unknown" }.joinToString("/")
        
        try {
            val intent = Intent("net.xrxss15.GARMIN_ACTIVITY_LISTENER_EVENT").apply {
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                putExtra("type", "DeviceList")
                putExtra("devices", deviceNames)
            }
            
            context.sendBroadcast(intent)
            
        } catch (e: Exception) {
            logError("Device list broadcast failed: ${e.message}")
        }
    }

    /**
     * Set callback for message events.
     * Called by Worker to update notification with latest message.
     * 
     * @param callback Callback function (payload, deviceName, timestamp)
     */
    fun setMessageCallback(callback: ((String, String, Long) -> Unit)?) {
        messageCallback = callback
    }

    /**
     * Set callback for device change events.
     * Called by Worker to update notification when devices connect/disconnect.
     * 
     * @param callback Callback function invoked on device status change
     */
    fun setDeviceChangeCallback(callback: (() -> Unit)?) {
        deviceChangeCallback = callback
    }
}