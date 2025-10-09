package net.xrxss15

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.garmin.android.connectiq.ConnectIQ
import com.garmin.android.connectiq.ConnectIQ.ConnectIQListener
import com.garmin.android.connectiq.ConnectIQ.IQApplicationEventListener
import com.garmin.android.connectiq.ConnectIQ.IQSdkErrorStatus
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice

class ConnectIQService private constructor() {

    companion object {
        private const val APP_UUID = "a3682e8a-8c10-4618-9f36-fd52877df567"
        private const val TAG = "GarminActivityListener.Service"
        private const val DISCOVERY_DELAY_MS = 500L
        private const val KNOWN_SIMULATOR_ID = 12345L

        @Volatile
        private var instance: ConnectIQService? = null

        fun getInstance(): ConnectIQService {
            return instance ?: synchronized(this) {
                instance ?: ConnectIQService().also { instance = it }
            }
        }
        
        fun resetInstance() {
            synchronized(this) {
                instance?.let {
                    try {
                        it.appContext?.let { ctx ->
                            it.connectIQ?.shutdown(ctx)
                        }
                    } catch (e: Exception) {
                        android.util.Log.e(TAG, "Error during reset: ${e.message}")
                    }
                    it.appListeners.clear()
                    it.knownDevices.clear()
                    it.messageCallback = null
                    it.deviceChangeCallback = null
                }
                instance = null
            }
        }
    }

    private var connectIQ: ConnectIQ? = null
    private val knownDevices = mutableSetOf<IQDevice>()
    private val appListeners = mutableMapOf<String, IQApplicationEventListener>()
    private var messageCallback: ((String, String, Long) -> Unit)? = null
    private var deviceChangeCallback: (() -> Unit)? = null
    private var appContext: Context? = null

    private fun log(msg: String) = android.util.Log.i(TAG, msg)
    private fun logError(msg: String) = android.util.Log.e(TAG, msg)

    fun initializeSdkIfNeeded(context: Context, onReady: (() -> Unit)? = null) {
        appContext = context.applicationContext
        
        if (isInitialized()) {
            log("SDK already initialized")
            onReady?.invoke()
            return
        }

        log("Initializing SDK")
        
        connectIQ = ConnectIQ.getInstance(appContext, ConnectIQ.IQConnectType.WIRELESS)
        
        connectIQ?.initialize(appContext, false, object : ConnectIQListener {
            override fun onSdkReady() {
                log("SDK initialized successfully")
                
                Thread {
                    Thread.sleep(DISCOVERY_DELAY_MS)
                    refreshAndRegisterDevices()
                    onReady?.invoke()
                }.start()
            }

            override fun onInitializeError(status: IQSdkErrorStatus?) {
                logError("SDK initialization failed: $status")
            }

            override fun onSdkShutDown() {
                log("SDK shutdown")
            }
        })
    }

    fun hasRequiredPermissions(context: Context): Boolean {
        val needs = mutableListOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            needs.add(android.Manifest.permission.BLUETOOTH_SCAN)
            needs.add(android.Manifest.permission.BLUETOOTH_CONNECT)
        }
        return needs.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }

    fun isInitialized(): Boolean = connectIQ != null

    private fun isRealDevice(d: IQDevice): Boolean {
        return d.deviceIdentifier != KNOWN_SIMULATOR_ID &&
                !d.friendlyName.orEmpty().contains("simulator", true)
    }

    fun getConnectedRealDevices(): List<IQDevice> {
        val ciq = connectIQ ?: return emptyList()
        return try {
            val connected = ciq.connectedDevices ?: emptyList()
            connected.filter { isRealDevice(it) }
        } catch (e: Exception) {
            logError("Error getting devices: ${e.message}")
            emptyList()
        }
    }

    fun refreshAndRegisterDevices() {
        val ciq = connectIQ ?: return
        
        val all = try {
            ciq.knownDevices
        } catch (e: Exception) {
            emptyList()
        }
        
        val candidates = all?.filter { it.deviceIdentifier != KNOWN_SIMULATOR_ID } ?: emptyList()
        
        candidates.forEach { device ->
            try {
                ciq.registerForDeviceEvents(device) { dev, status ->
                    log("Device ${dev.friendlyName} status changed: $status")
                    
                    // Trigger callback on connect, disconnect, and unpair
                    when (status) {
                        IQDevice.IQDeviceStatus.CONNECTED,
                        IQDevice.IQDeviceStatus.NOT_CONNECTED,
                        IQDevice.IQDeviceStatus.NOT_PAIRED -> {
                            deviceChangeCallback?.invoke()
                        }
                        else -> {
                            // Do nothing for unknown statuses
                        }
                    }
                }
            } catch (e: Exception) {
                logError("Failed to register device events: ${e.message}")
            }
        }
    }

    fun registerListenersForAllDevices() {
        val devices = getConnectedRealDevices()
        log("Registering ${devices.size} device(s)")
        
        knownDevices.clear()
        knownDevices.addAll(devices)
        
        devices.forEach { device ->
            registerListenerForDevice(device)
        }
        
        sendDeviceListBroadcast(devices)
    }

    private fun registerListenerForDevice(device: IQDevice) {
        val ciq = connectIQ ?: return
        val app = IQApp(APP_UUID)
        val appKey = "${device.deviceIdentifier}:$APP_UUID"
        
        if (appListeners.containsKey(appKey)) {
            return
        }
        
        // Capture device name at registration time - guaranteed correct for this device
        val knownDeviceName = device.friendlyName?.takeIf { it.isNotEmpty() } ?: "Unknown Device"
        
        val appListener = IQApplicationEventListener { dev, _, messages, _ ->
            if (dev == null || messages.isNullOrEmpty()) return@IQApplicationEventListener
            
            messages.forEach { msg ->
                val payload = msg.toString()
                // Use captured device name - always correct because listener is per-device
                val deviceName = knownDeviceName
                val timestamp = System.currentTimeMillis()
                
                log("Message from $deviceName: $payload")
                
                messageCallback?.invoke(payload, deviceName, timestamp)
                sendMessageBroadcast(payload, deviceName)
            }
        }
        
        try {
            ciq.registerForAppEvents(device, app, appListener)
            appListeners[appKey] = appListener
            log("Registered listener for $knownDeviceName")
        } catch (e: Exception) {
            logError("Failed to register app listener: ${e.message}")
        }
    }

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
            
            val intent = Intent(ActivityStatusCheckReceiver.ACTION_EVENT).apply {
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

    private fun sendDeviceListBroadcast(devices: List<IQDevice>) {
        val context = appContext ?: return
        
        val deviceNames = devices.map { it.friendlyName ?: "Unknown" }.joinToString("/")
        
        try {
            val intent = Intent(ActivityStatusCheckReceiver.ACTION_EVENT).apply {
                putExtra("type", "DeviceList")
                putExtra("devices", deviceNames)
            }
            
            context.sendBroadcast(intent)
            
        } catch (e: Exception) {
            logError("Device list broadcast failed: ${e.message}")
        }
    }

    fun setMessageCallback(callback: ((String, String, Long) -> Unit)?) {
        messageCallback = callback
    }

    fun setDeviceChangeCallback(callback: (() -> Unit)?) {
        deviceChangeCallback = callback
    }
}