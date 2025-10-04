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
import com.garmin.android.connectiq.ConnectIQ.IQDeviceEventListener
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ConnectIQService private constructor() {

    companion object {
        private const val TAG = "GarminActivityListener.Service"
        private const val APP_UUID = "a3682e8a-8c10-4618-9f36-fd52877df567"
        private const val DISCOVERY_DELAY_MS = 500L
        private const val KNOWN_SIMULATOR_ID = 12345L
        private const val INIT_TIMEOUT_MS = 8000L
        
        @Volatile private var INSTANCE: ConnectIQService? = null
        
        fun getInstance(): ConnectIQService =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: ConnectIQService().also { INSTANCE = it }
            }
    }

    private var connectIQ: ConnectIQ? = null
    private val initialized = AtomicBoolean(false)
    private var appContext: Context? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    
    private val deviceListeners = ConcurrentHashMap<String, IQDeviceEventListener>()
    private val appListeners = ConcurrentHashMap<String, IQApplicationEventListener>()
    
    @Volatile private var messageCallback: ((String, String, Long) -> Unit)? = null
    @Volatile private var deviceChangeCallback: (() -> Unit)? = null

    fun setMessageCallback(callback: ((String, String, Long) -> Unit)?) {
        messageCallback = callback
    }
    
    fun setDeviceChangeCallback(callback: (() -> Unit)?) {
        deviceChangeCallback = callback
    }

    fun hasRequiredPermissions(ctx: Context): Boolean {
        val needs = mutableListOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            needs.add(android.Manifest.permission.BLUETOOTH_SCAN)
            needs.add(android.Manifest.permission.BLUETOOTH_CONNECT)
        }
        
        val result = needs.all {
            ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
        }
        Log.d(TAG, "Permissions check: $result")
        return result
    }

    fun initializeForWorker(context: Context): Boolean {
        if (initialized.get()) {
            Log.d(TAG, "SDK already initialized")
            return true
        }

        Log.i(TAG, "Initializing SDK...")
        appContext = context.applicationContext
        val ciq = ConnectIQ.getInstance(appContext, ConnectIQ.IQConnectType.WIRELESS)
        connectIQ = ciq
        
        val initLatch = CountDownLatch(1)
        val initSuccess = AtomicBoolean(false)
        
        mainHandler.post {
            try {
                Log.d(TAG, "Calling SDK initialize on main thread")
                ciq.initialize(appContext, false, object : ConnectIQListener {
                    override fun onSdkReady() {
                        Log.i(TAG, "SDK ready")
                        initSuccess.set(true)
                        initialized.set(true)
                        initLatch.countDown()
                    }

                    override fun onInitializeError(status: ConnectIQ.IQSdkErrorStatus) {
                        Log.e(TAG, "SDK init error: $status")
                        initSuccess.set(false)
                        initLatch.countDown()
                    }

                    override fun onSdkShutDown() {
                        Log.i(TAG, "SDK shut down")
                        initialized.set(false)
                    }
                })
            } catch (e: Exception) {
                Log.e(TAG, "SDK init exception", e)
                initSuccess.set(false)
                initLatch.countDown()
            }
        }
        
        Log.d(TAG, "Waiting for SDK initialization...")
        return try {
            val completed = initLatch.await(INIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!completed) {
                Log.e(TAG, "SDK init timeout")
                return false
            }
            
            if (initSuccess.get()) {
                Log.i(TAG, "SDK initialized successfully")
                registerListenersForAllDevices()
                true
            } else {
                Log.e(TAG, "SDK initialization failed")
                false
            }
        } catch (e: InterruptedException) {
            Log.e(TAG, "SDK init interrupted", e)
            false
        }
    }

    fun getConnectedRealDevices(): List<IQDevice> {
        val ciq = connectIQ
        if (ciq == null) {
            Log.w(TAG, "SDK not initialized - cannot get devices")
            return emptyList()
        }
        
        return try {
            val devices = ciq.connectedDevices
            val realDevices = devices?.filter { isRealDevice(it) } ?: emptyList()
            Log.d(TAG, "Found ${realDevices.size} real device(s)")
            realDevices
        } catch (e: Exception) {
            Log.e(TAG, "Error getting devices", e)
            emptyList()
        }
    }

    private fun isRealDevice(d: IQDevice): Boolean {
        return d.deviceIdentifier != KNOWN_SIMULATOR_ID &&
                !d.friendlyName.orEmpty().contains("simulator", ignoreCase = true)
    }

    fun registerListenersForAllDevices() {
        val ciq = connectIQ
        if (ciq == null) {
            Log.w(TAG, "Cannot register listeners - SDK not initialized")
            return
        }
        
        Log.d(TAG, "Waiting for device discovery...")
        try { 
            Thread.sleep(DISCOVERY_DELAY_MS) 
        } catch (_: InterruptedException) {
            return
        }

        val devices = getConnectedRealDevices()
        if (devices.isEmpty()) {
            Log.w(TAG, "No devices to register")
            return
        }
        
        Log.i(TAG, "Registering listeners for ${devices.size} device(s)")
        devices.forEach { device ->
            Log.d(TAG, "Registering: ${device.friendlyName}")
            registerDeviceListeners(ciq, device)
        }
        Log.i(TAG, "All listeners registered")
    }
    
    private fun registerDeviceListeners(ciq: ConnectIQ, device: IQDevice) {
        val appKey = "${device.deviceIdentifier}:$APP_UUID"
        if (!appListeners.containsKey(appKey)) {
            try {
                val app = IQApp(APP_UUID)
                
                val appListener = IQApplicationEventListener { dev, _, messages, _ ->
                    if (!messages.isNullOrEmpty()) {
                        val payload = messages.joinToString("|") { it.toString() }
                        val timestamp = System.currentTimeMillis()
                        val deviceName = dev.friendlyName?.takeIf { it.isNotBlank() } ?: "Unknown"
                        
                        Log.d(TAG, "Message from $deviceName: $payload")
                        messageCallback?.invoke(payload, deviceName, timestamp)
                    }
                }
                
                ciq.registerForAppEvents(device, app, appListener)
                appListeners[appKey] = appListener
                Log.d(TAG, "App listener registered for ${device.friendlyName}")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register app listener", e)
            }
        }
        
        val deviceKey = "${device.deviceIdentifier}"
        if (!deviceListeners.containsKey(deviceKey)) {
            val listener = IQDeviceEventListener { _, status ->
                Log.d(TAG, "Device status: $status")
                when (status) {
                    IQDevice.IQDeviceStatus.CONNECTED,
                    IQDevice.IQDeviceStatus.NOT_CONNECTED -> {
                        Log.i(TAG, "Device connection state changed")
                        deviceChangeCallback?.invoke()
                    }
                    else -> {}
                }
            }
            
            try {
                ciq.registerForDeviceEvents(device, listener)
                deviceListeners[deviceKey] = listener
                Log.d(TAG, "Device listener registered for ${device.friendlyName}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register device listener", e)
            }
        }
    }
}