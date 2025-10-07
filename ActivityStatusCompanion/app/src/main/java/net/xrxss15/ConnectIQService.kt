package net.xrxss15

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.garmin.android.connectiq.ConnectIQ
import com.garmin.android.connectiq.ConnectIQ.IQApplicationEventListener
import com.garmin.android.connectiq.ConnectIQ.IQMessageStatus
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice

class ConnectIQService private constructor() {

    companion object {
        private const val APP_UUID = "7b408c6e-fc9c-4080-bad4-97a3557fc995"
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
    }

    private var connectIQ: ConnectIQ? = null
    private val knownDevices = mutableSetOf<IQDevice>()
    
    // CRITICAL: Store listeners to prevent GC!
    private val appListeners = mutableMapOf<String, IQApplicationEventListener>()
    
    private var messageCallback: ((String, String, Long) -> Unit)? = null
    private var deviceChangeCallback: (() -> Unit)? = null

    private fun log(msg: String) = android.util.Log.i(TAG, msg)
    private fun logError(msg: String) = android.util.Log.e(TAG, msg)

    fun hasRequiredPermissions(context: Context): Boolean {
        val needs = mutableListOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            needs.add(android.Manifest.permission.BLUETOOTH_SCAN)
            needs.add(android.Manifest.permission.BLUETOOTH_CONNECT)
        }
        return needs.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }

    fun setSdkInstance(sdk: ConnectIQ?) {
        connectIQ = sdk
        log("SDK instance set")
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
            val real = connected.filter { isRealDevice(it) }
            log("Connected devices: ${connected.size}, real: ${real.size}")
            real
        } catch (e: Exception) {
            logError("Error getting devices: ${e.message}")
            emptyList()
        }
    }

    fun refreshAndRegisterDevices() {
        val ciq = connectIQ ?: return
        
        try {
            Thread.sleep(DISCOVERY_DELAY_MS)
        } catch (_: InterruptedException) {}
        
        val all = try {
            ciq.knownDevices
        } catch (e: Exception) {
            log("knownDevices threw: ${e.message}")
            emptyList()
        }
        
        val candidates = all?.filter { it.deviceIdentifier != KNOWN_SIMULATOR_ID } ?: emptyList()
        log("Known devices: ${all?.size ?: 0}, candidates: ${candidates.size}")
        
        candidates.forEach { device ->
            try {
                ciq.registerForDeviceEvents(device) { dev, status ->
                    log("Device event: ${dev.friendlyName} -> $status")
                    if (status == IQDevice.IQDeviceStatus.CONNECTED) {
                        deviceChangeCallback?.invoke()
                    }
                }
                log("Registered device events for ${device.friendlyName}")
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
        
        deviceChangeCallback?.invoke()
    }

    private fun registerListenerForDevice(device: IQDevice) {
        val ciq = connectIQ ?: return
        val app = IQApp(APP_UUID)
        
        // Key to store listener - prevents GC!
        val appKey = "${device.deviceIdentifier}:$APP_UUID"
        
        // Check if already registered
        if (appListeners.containsKey(appKey)) {
            log("Already registered for ${device.friendlyName}")
            return
        }
        
        // Create and STORE the listener
        val appListener = IQApplicationEventListener { dev, iqApp, messages, status ->
            if (dev == null || messages.isNullOrEmpty()) return@IQApplicationEventListener
            
            messages.forEach { msg ->
                val payload = msg.toString()
                val deviceName = dev.friendlyName ?: "Unknown"
                log("Message from $deviceName: $payload")
                
                // Call the callback!
                messageCallback?.invoke(payload, deviceName, System.currentTimeMillis())
            }
        }
        
        try {
            ciq.registerForAppEvents(device, app, appListener)
            
            // CRITICAL: Store reference to prevent GC!
            appListeners[appKey] = appListener
            
            log("✓ Registered app listener for ${device.friendlyName}")
        } catch (e: Exception) {
            logError("Failed to register app listener: ${e.message}")
        }
    }

    fun setMessageCallback(callback: ((String, String, Long) -> Unit)?) {
        messageCallback = callback
        log("Message callback ${if (callback != null) "set" else "cleared"}")
    }

    fun setDeviceChangeCallback(callback: (() -> Unit)?) {
        deviceChangeCallback = callback
    }

    fun shutdown() {
        // Unregister all app listeners
        appListeners.clear()
        knownDevices.clear()
        messageCallback = null
        deviceChangeCallback = null
    }
}