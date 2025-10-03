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
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ConnectIQ Service - Passive Message Listener
 */
class ConnectIQService private constructor() {

    companion object {
        private const val APP_UUID = "a3682e8a-8c10-4618-9f36-fd52877df567"
        private const val DISCOVERY_DELAY_MS = 500L
        private const val KNOWN_SIMULATOR_ID = 12345L
        private const val WORKER_INIT_TIMEOUT_MS = 8000L
        
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
    
    private val deviceListeners = mutableMapOf<String, (IQDevice, IQDevice.IQDeviceStatus) -> Unit>()
    private val appListeners = mutableMapOf<String, IQApplicationEventListener>()
    
    @Volatile private var logSink: ((String) -> Unit)? = null
    @Volatile private var messageCallback: ((String, String, Long) -> Unit)? = null

    fun registerLogSink(sink: ((String) -> Unit)?) { 
        logSink = sink 
    }
    
    fun setMessageCallback(callback: ((String, String, Long) -> Unit)?) {
        messageCallback = callback
    }

    private fun ts(): String = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())

    fun log(message: String) { 
        logSink?.invoke(message) 
        android.util.Log.i("ConnectIQService", message)
    }

    private fun logInfo(message: String) {
        log("[${ts()}] $message")
    }

    private fun logSuccess(message: String) {
        log("[${ts()}] ✅ $message")
    }

    private fun logError(message: String) {
        log("[${ts()}] ❌ $message")
        android.util.Log.e("ConnectIQService", message)
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
            logInfo("SDK already initialized")
            return true
        }

        logInfo("Initializing ConnectIQ SDK...")
        
        appContext = context.applicationContext
        val ciq = ConnectIQ.getInstance(appContext, ConnectIQ.IQConnectType.WIRELESS)
        connectIQ = ciq
        
        val initLatch = CountDownLatch(1)
        val initSuccess = AtomicBoolean(false)
        
        mainHandler.post {
            try {
                ciq.initialize(appContext, false, object : ConnectIQListener {
                    override fun onSdkReady() {
                        logSuccess("SDK ready")
                        initSuccess.set(true)
                        initialized.set(true)
                        initLatch.countDown()
                    }

                    override fun onInitializeError(status: ConnectIQ.IQSdkErrorStatus) {
                        logError("SDK init failed: $status")
                        initSuccess.set(false)
                        initLatch.countDown()
                    }

                    override fun onSdkShutDown() {
                        logInfo("SDK shut down")
                        initialized.set(false)
                    }
                })
            } catch (e: Exception) {
                logError("SDK init error: ${e.message}")
                initSuccess.set(false)
                initLatch.countDown()
            }
        }
        
        return try {
            val completed = initLatch.await(WORKER_INIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            
            if (!completed) {
                logError("SDK init timeout")
                return false
            }
            
            if (initSuccess.get()) {
                registerListenersForAllDevices()
                true
            } else {
                false
            }
            
        } catch (e: InterruptedException) {
            logError("SDK init interrupted")
            false
        }
    }

    fun getConnectedRealDevices(): List<IQDevice> {
        val ciq = connectIQ ?: return emptyList()
        
        val connected = try { 
            ciq.connectedDevices 
        } catch (e: Exception) {
            logError("Failed to get devices: ${e.message}")
            emptyList<IQDevice>()
        }

        return connected.filter { isRealDevice(it) }
    }

    private fun isRealDevice(d: IQDevice): Boolean {
        return d.deviceIdentifier != KNOWN_SIMULATOR_ID &&
                !d.friendlyName.orEmpty().contains("simulator", ignoreCase = true)
    }

    fun registerListenersForAllDevices() {
        val ciq = connectIQ ?: return
        
        logInfo("Registering listeners...")
        
        try { 
            Thread.sleep(DISCOVERY_DELAY_MS) 
        } catch (_: InterruptedException) {}

        val devices = getConnectedRealDevices()
        
        if (devices.isEmpty()) {
            logError("No devices to register")
            return
        }

        devices.forEach { device ->
            val appKey = "${device.deviceIdentifier}:$APP_UUID"
            
            if (!appListeners.containsKey(appKey)) {
                try {
                    val app = IQApp(APP_UUID)
                    
                    val appListener = IQApplicationEventListener { dev, _, messages, _ ->
                        if (!messages.isNullOrEmpty()) {
                            val payload = messages.joinToString("|") { it.toString() }
                            val timestamp = System.currentTimeMillis()
                            
                            logSuccess("Message from ${dev.friendlyName}")
                            logInfo("Payload: $payload")
                            
                            messageCallback?.invoke(payload, dev.friendlyName ?: "Unknown", timestamp)
                        }
                    }
                    
                    ciq.registerForAppEvents(device, app, appListener)
                    appListeners[appKey] = appListener
                    
                    logSuccess("Listener for ${device.friendlyName}")
                    
                } catch (e: Exception) {
                    logError("Failed to register ${device.friendlyName}: ${e.message}")
                }
            }
            
            val deviceKey = "${device.deviceIdentifier}:${device.friendlyName}"
            if (!deviceListeners.containsKey(deviceKey)) {
                val listener: (IQDevice, IQDevice.IQDeviceStatus) -> Unit = { d, status ->
                    logInfo("${d.friendlyName} status: $status")
                }
                
                try {
                    ciq.registerForDeviceEvents(device) { d, status -> listener(d, status) }
                    deviceListeners[deviceKey] = listener
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }
        
        logSuccess("Listeners active")
    }

    fun refreshListeners() {
        registerListenersForAllDevices()
    }
}