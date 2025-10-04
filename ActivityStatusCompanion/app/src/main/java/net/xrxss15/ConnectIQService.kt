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
        return needs.all {
            ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun initializeForWorker(context: Context): Boolean {
        if (initialized.get()) {
            return true
        }

        Log.i(TAG, "Initializing SDK")
        appContext = context.applicationContext
        val ciq = ConnectIQ.getInstance(appContext, ConnectIQ.IQConnectType.WIRELESS)
        connectIQ = ciq
        
        val initLatch = CountDownLatch(1)
        val initSuccess = AtomicBoolean(false)
        
        mainHandler.post {
            try {
                ciq.initialize(appContext, false, object : ConnectIQListener {
                    override fun onSdkReady() {
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
                        initialized.set(false)
                    }
                })
            } catch (e: Exception) {
                Log.e(TAG, "SDK init exception", e)
                initSuccess.set(false)
                initLatch.countDown()
            }
        }
        
        return try {
            val completed = initLatch.await(INIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (completed && initSuccess.get()) {
                registerListenersForAllDevices()
                true
            } else {
                false
            }
        } catch (e: InterruptedException) {
            false
        }
    }

    fun getConnectedRealDevices(): List<IQDevice> {
        val ciq = connectIQ ?: return emptyList()
        
        return try {
            val devices = ciq.connectedDevices
            devices?.filter { isRealDevice(it) } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun isRealDevice(d: IQDevice): Boolean {
        return d.deviceIdentifier != KNOWN_SIMULATOR_ID &&
                !d.friendlyName.orEmpty().contains("simulator", ignoreCase = true)
    }

    fun registerListenersForAllDevices() {
        val ciq = connectIQ ?: return
        
        try { 
            Thread.sleep(DISCOVERY_DELAY_MS) 
        } catch (_: InterruptedException) {
            return
        }

        val devices = getConnectedRealDevices()
        if (devices.isEmpty()) return

        devices.forEach { device ->
            registerDeviceListeners(ciq, device)
        }
    }
    
    private fun registerDeviceListeners(ciq: ConnectIQ, device: IQDevice) {
        val capturedDeviceName = device.friendlyName?.takeIf { it.isNotBlank() } ?: "Unknown Device"
        val deviceId = device.deviceIdentifier
        
        val appKey = "${deviceId}:$APP_UUID"
        if (!appListeners.containsKey(appKey)) {
            try {
                val app = IQApp(APP_UUID)
                
                val appListener = IQApplicationEventListener { dev, _, messages, _ ->
                    if (!messages.isNullOrEmpty()) {
                        val payload = messages.joinToString("|") { it.toString() }
                        val timestamp = System.currentTimeMillis()
                        val deviceName = dev.friendlyName?.takeIf { it.isNotBlank() } ?: capturedDeviceName
                        
                        messageCallback?.invoke(payload, deviceName, timestamp)
                    }
                }
                
                ciq.registerForAppEvents(device, app, appListener)
                appListeners[appKey] = appListener
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register app listener", e)
            }
        }
        
        val deviceKey = "$deviceId"
        if (!deviceListeners.containsKey(deviceKey)) {
            val listener = IQDeviceEventListener { _, status ->
                when (status) {
                    IQDevice.IQDeviceStatus.CONNECTED,
                    IQDevice.IQDeviceStatus.NOT_CONNECTED -> {
                        deviceChangeCallback?.invoke()
                    }
                    else -> {}
                }
            }
            
            try {
                ciq.registerForDeviceEvents(device, listener)
                deviceListeners[deviceKey] = listener
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register device listener", e)
            }
        }
    }
}