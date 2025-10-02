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
 * Manages all interactions with the Garmin ConnectIQ SDK for both debug and headless modes.
 */
class ConnectIQService private constructor() {

    companion object {
        // Updated CIQ app UUID
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

    fun registerLogSink(sink: ((String) -> Unit)?) { 
        logSink = sink 
    }
    
    fun setResponseCallback(callback: ((String, String, String) -> Unit)?) {
        responseCallback = callback
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
    
    private fun logWarning(message: String) {
        log("[${ts()}] ⚠️ $message")
        android.util.Log.w("ConnectIQService", message)
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
                refreshAndRegisterDevices()
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

    fun refreshAndRegisterDevices() {
        val ciq = connectIQ ?: return
        
        try { 
            Thread.sleep(DISCOVERY_DELAY_MS) 
        } catch (_: InterruptedException) {}

        val all = try { 
            ciq.knownDevices 
        } catch (e: Exception) {
            emptyList<IQDevice>()
        }

        val candidates = all.filter { it.deviceIdentifier != KNOWN_SIMULATOR_ID }
        candidates.forEach { device ->
            val key = "${device.deviceIdentifier}:${device.friendlyName}"
            if (!deviceListeners.containsKey(key)) {
                val listener: (IQDevice, IQDevice.IQDeviceStatus) -> Unit = { _, _ -> }

                try {
                    ciq.registerForDeviceEvents(device) { d, status -> listener(d, status) }
                    deviceListeners[key] = listener
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun queryActivityStatus(
        context: Context,
        selected: IQDevice? = null,
        showUiIfInitNeeded: Boolean = false
    ): QueryResult {
        if (!initialized.get()) {
            return QueryResult(false, "SDK not initialized", "", 0)
        }

        val ciq = connectIQ!!
        val devices = getConnectedRealDevices()
        val target = selected ?: devices.firstOrNull()

        if (target == null) {
            return QueryResult(false, "No device found", "", 0)
        }

        logInfo("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        logInfo("Query: ${target.friendlyName}")
        logInfo("Status: ${target.status}")

        // Check if CIQ app is installed
        val installed = AtomicBoolean(false)
        val infoLatch = CountDownLatch(1)

        logInfo("Checking CIQ app...")

        try {
            ciq.getApplicationInfo(APP_UUID, target, object : ConnectIQ.IQApplicationInfoListener {
                override fun onApplicationInfoReceived(app: IQApp) {
                    logSuccess("CIQ app found: ${app.displayName} v${app.version()}")
                    installed.set(true)
                    infoLatch.countDown()
                }

                override fun onApplicationNotInstalled(applicationId: String) {
                    logError("CIQ app not installed")
                    installed.set(false)
                    infoLatch.countDown()
                }
            })
        } catch (e: Exception) {
            logError("App check failed: ${e.message}")
            installed.set(false)
            infoLatch.countDown()
        }

        if (!infoLatch.await(3, TimeUnit.SECONDS)) {
            logError("App check timeout")
            return QueryResult(false, "App check timeout", "", devices.size)
        }
        
        if (!installed.get()) {
            return QueryResult(false, "App not found", "", devices.size)
        }

        val app = IQApp(APP_UUID)
        val appKey = "${target.deviceIdentifier}:$APP_UUID"

        // Register listener
        logInfo("Registering listener...")
        
        val appListener = IQApplicationEventListener { device, _, messages, _ ->
            val rxTime = ts()
            
            logSuccess("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            logSuccess("Response from ${device.friendlyName}")
            
            if (messages.isNullOrEmpty()) {
                logWarning("Empty response")
            } else {
                val payload = messages.joinToString("|") { it.toString() }
                logInfo("Data: $payload")
                
                responseCallback?.let { callback ->
                    callback(payload, device.friendlyName ?: "Unknown", rxTime)
                }
            }
            logSuccess("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        }

        try {
            ciq.registerForAppEvents(target, app, appListener)
            appListeners[appKey] = appListener
            logSuccess("Listener registered")
        } catch (e: Exception) {
            logError("Listener failed: ${e.message}")
            return QueryResult(false, "Listener error", "", devices.size)
        }

        // Send message
        logInfo("Sending message...")
        
        try {
            val messageSent = AtomicBoolean(false)
            val sendLatch = CountDownLatch(1)
            
            ciq.sendMessage(target, app, listOf("status?"), object : ConnectIQ.IQSendMessageListener {
                override fun onMessageStatus(device: IQDevice, iqApp: IQApp, status: IQMessageStatus) {
                    val success = status == IQMessageStatus.SUCCESS
                    messageSent.set(success)
                    
                    if (success) {
                        logSuccess("Message sent to ${device.friendlyName}")
                        logInfo("Waiting for response...")
                    } else {
                        logError("Send failed: $status")
                    }
                    
                    sendLatch.countDown()
                }
            })
            
            if (!sendLatch.await(5, TimeUnit.SECONDS)) {
                logError("Send timeout")
                return QueryResult(false, "Send timeout", "", devices.size)
            }
            
            if (!messageSent.get()) {
                return QueryResult(false, "Send failed", "", devices.size)
            }
            
            logInfo("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            
            return QueryResult(true, "Query sent", "", devices.size)
            
        } catch (e: Exception) {
            logError("Send error: ${e.message}")
            return QueryResult(false, "Send error", "", devices.size)
        }
    }
}