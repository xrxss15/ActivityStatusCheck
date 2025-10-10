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
        // Maximum SDK recovery attempts before giving up
        private const val MAX_RECOVERY_ATTEMPTS = 3
        // Recovery cooldown period
        private const val RECOVERY_COOLDOWN_MS = 10_000L

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
         */
        fun resetInstance() {
            synchronized(this) {
                instance?.let {
                    it.sdkReady = false
                    try {
                        val sdk = it.connectIQ
                        val ctx = it.appContext
                        if (sdk != null && ctx != null) {
                            sdk.shutdown(ctx)
                        }
                    } catch (e: Exception) {
                        android.util.Log.e(TAG, "Error during shutdown: ${e.message}")
                    }
                    it.connectIQ = null
                    synchronized(it.appListeners) {
                        it.appListeners.clear()
                    }
                    synchronized(it.knownDevices) {
                        it.knownDevices.clear()
                    }
                    synchronized(it.callbackLock) {
                        it.messageCallback = null
                        it.deviceChangeCallback = null
                    }
                    it.isReinitializing = false
                    it.recoveryAttempts = 0
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
    // Lock for callback synchronization
    private val callbackLock = Any()
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
    // Recovery attempt counter
    @Volatile
    private var recoveryAttempts = 0
    // Last recovery timestamp
    @Volatile
    private var lastRecoveryTime = 0L

    private fun log(msg: String) = android.util.Log.i(TAG, msg)
    private fun logError(msg: String) = android.util.Log.e(TAG, msg)

    /**
     * Initialize the ConnectIQ SDK if not already initialized.
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
                isReinitializing = false
                recoveryAttempts = 0
                // Wait for device discovery before invoking callback
                mainHandler.postDelayed({
                    refreshAndRegisterDevices()
                    onReady?.invoke()
                }, DISCOVERY_DELAY_MS)
            }

            override fun onInitializeError(status: IQSdkErrorStatus?) {
                logError("SDK initialization failed: $status")
                sdkReady = false
                isReinitializing = false
            }

            override fun onSdkShutDown() {
                log("SDK shutdown")
                sdkReady = false
            }
        })
    }

    /**
     * Test if SDK is functioning and attempt recovery if not.
     */
    fun testAndRecoverSdk(context: Context): Boolean {
        if (isReinitializing) {
            log("SDK recovery already in progress")
            return false
        }

        val sdk = connectIQ
        if (sdk == null) {
            log("SDK is null, recovery needed")
            startSdkRecovery(context)
            return false
        }

        try {
            // Test SDK health by attempting to get connected devices
            val test = sdk.connectedDevices
            log("SDK health check passed (${test?.size ?: 0} devices)")
            return true
        } catch (e: Exception) {
            if (e.message?.contains("SDK not initialized") == true) {
                log("SDK health check failed: ${e.message}")
                startSdkRecovery(context)
                return false
            }
            logError("SDK health check error: ${e.message}")
            return false
        }
    }

    /**
     * Start SDK recovery process by launching SdkInitActivity.
     */
    private fun startSdkRecovery(context: Context) {
        synchronized(this) {
            if (isReinitializing) return
            
            // Check recovery cooldown
            val now = System.currentTimeMillis()
            if (now - lastRecoveryTime < RECOVERY_COOLDOWN_MS) {
                log("Recovery cooldown active, skipping")
                return
            }
            
            // Check max recovery attempts
            if (recoveryAttempts >= MAX_RECOVERY_ATTEMPTS) {
                logError("Max recovery attempts reached, giving up")
                return
            }
            
            isReinitializing = true
            recoveryAttempts++
            lastRecoveryTime = now
        }

        log("Starting SDK recovery (attempt $recoveryAttempts/$MAX_RECOVERY_ATTEMPTS)")
        connectIQ = null
        sdkReady = false

        val ctx = appContext ?: context.applicationContext
        
        try {
            val intent = Intent(ctx, SdkInitActivity::class.java).apply {
                action = SdkInitActivity.ACTION_INIT_SDK
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)

            // Reset reinitialization flag after delay
            mainHandler.postDelayed({
                synchronized(this) {
                    isReinitializing = false
                }
                // After recovery completes, re-register listeners
                if (sdkReady) {
                    log("SDK recovery completed, re-registering listeners")
                    registerListenersForAllDevices()
                    synchronized(callbackLock) {
                        deviceChangeCallback?.invoke()
                    }
                }
            }, 5000)
        } catch (e: Exception) {
            logError("Failed to start SdkInitActivity: ${e.message}")
            synchronized(this) {
                isReinitializing = false
            }
        }
    }

    /**
     * Check if all required permissions are granted.
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
     */
    fun isInitialized(): Boolean = sdkReady

    /**
     * Check if a device is a real physical device (not simulator).
     */
    private fun isRealDevice(device: IQDevice): Boolean {
        return device.deviceIdentifier != KNOWN_SIMULATOR_ID &&
                !device.friendlyName.orEmpty().contains("simulator", true)
    }

    /**
     * Get list of connected real devices (excludes simulators).
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
     */
    fun refreshAndRegisterDevices() {
        val sdk = connectIQ ?: return
        val ctx = appContext

        val all = try {
            sdk.knownDevices
        } catch (e: Exception) {
            emptyList<IQDevice>()
        }

        val candidates = all?.filter { it.deviceIdentifier != KNOWN_SIMULATOR_ID } ?: emptyList()
        candidates.forEach { device ->
            try {
                sdk.registerForDeviceEvents(device) { dev, status ->
                    log("Device ${dev.friendlyName} status changed: $status")
                    when (status) {
                        IQDevice.IQDeviceStatus.CONNECTED -> {
                            // Test SDK health on connect
                            log("Device CONNECTED event - checking SDK health")
                            if (ctx != null) {
                                val healthy = testAndRecoverSdk(ctx)
                                if (healthy) {
                                    log("SDK health check passed after CONNECTED")
                                    synchronized(callbackLock) {
                                        deviceChangeCallback?.invoke()
                                    }
                                } else {
                                    log("SDK recovery initiated after CONNECTED event")
                                }
                            } else {
                                synchronized(callbackLock) {
                                    deviceChangeCallback?.invoke()
                                }
                            }
                        }
                        IQDevice.IQDeviceStatus.NOT_CONNECTED,
                        IQDevice.IQDeviceStatus.NOT_PAIRED -> {
                            synchronized(callbackLock) {
                                deviceChangeCallback?.invoke()
                            }
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
     */
    fun registerListenersForAllDevices() {
        val devices = getConnectedRealDevices()
        log("Registering ${devices.size} device(s)")
        synchronized(knownDevices) {
            knownDevices.clear()
            knownDevices.addAll(devices)
        }

        devices.forEach { device ->
            registerListenerForDevice(device)
        }
    }

    /**
     * Register app event listener for a specific device.
     */
    private fun registerListenerForDevice(device: IQDevice) {
        val sdk = connectIQ ?: return
        val app = IQApp(APP_UUID)
        val appKey = "${device.deviceIdentifier}:$APP_UUID"

        // Skip if already registered
        synchronized(appListeners) {
            if (appListeners.containsKey(appKey)) {
                return
            }
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
                synchronized(callbackLock) {
                    messageCallback?.invoke(payload, deviceName, timestamp)
                }

                // Broadcast to MainActivity and Tasker
                sendMessageBroadcast(payload, deviceName)
            }
        }

        try {
            sdk.registerForAppEvents(device, app, appListener)
            synchronized(appListeners) {
                appListeners[appKey] = appListener
            }
            log("Registered listener for $knownDeviceName")
        } catch (e: Exception) {
            logError("Failed to register app listener: ${e.message}")
        }
    }

    /**
     * Parse and broadcast an activity message.
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
     * Set callback for message events.
     */
    fun setMessageCallback(callback: ((String, String, Long) -> Unit)?) {
        synchronized(callbackLock) {
            messageCallback = callback
        }
    }

    /**
     * Set callback for device change events.
     */
    fun setDeviceChangeCallback(callback: (() -> Unit)?) {
        synchronized(callbackLock) {
            deviceChangeCallback = callback
        }
    }
}