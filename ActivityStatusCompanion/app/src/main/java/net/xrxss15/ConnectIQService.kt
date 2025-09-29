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
import com.garmin.android.connectiq.ConnectIQ.IQMessageStatus
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ConnectIQService private constructor() {

    companion object {
        private const val TAG = "ActStatus"
        private const val APP_UUID = "7b408c6e-fc9c-4080-bad4-97a3557fc995"

        private const val RESPONSE_TIMEOUT_MS = 15_000L
        private const val SEND_RETRY_DELAY_MS = 1_200L
        private const val DISCOVERY_DELAY_MS  = 500L
        private const val KNOWN_SIMULATOR_ID  = 12345L

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

    private var connectIQ: ConnectIQ? = null
    private val initialized = AtomicBoolean(false)
    private val initInProgress = AtomicBoolean(false)
    private var appContext: Context? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private val deviceListeners = mutableMapOf<String, (IQDevice, IQDevice.IQDeviceStatus) -> Unit>()
    private val appListeners = mutableMapOf<String, IQApplicationEventListener>()

    @Volatile private var logSink: ((String) -> Unit)? = null
    fun registerLogSink(sink: ((String) -> Unit)?) { logSink = sink } 
    fun log(message: String) { Log.d(TAG, message); logSink?.invoke(message) } 

    fun hasRequiredPermissions(ctx: Context): Boolean {
        val needs = mutableListOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            needs.add(android.Manifest.permission.BLUETOOTH_SCAN)
            needs.add(android.Manifest.permission.BLUETOOTH_CONNECT)
        }
        val ok = needs.all {
            ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
        }
        log(if (ok) "[PERMS] All required permissions granted" else "[PERMS] Missing required permissions: $needs") 
        return ok 
    }

    @Synchronized
    private fun ensureInitialized(context: Context, showUi: Boolean): Boolean {
        if (initialized.get()) {
            log("[INIT] Already initialized") 
            return true 
        }

        val onMain = Looper.myLooper() == Looper.getMainLooper()
        if (onMain) {
            if (initInProgress.get()) {
                log("[INIT] Init requested on main; already in progress (non-blocking)") 
                return false 
            }
            initInProgress.set(true)
            appContext = context.applicationContext
            val ciq = ConnectIQ.getInstance(appContext, ConnectIQ.IQConnectType.WIRELESS)
            log("[INIT] (main) getInstance(WIRELESS) -> $ciq") 
            connectIQ = ciq
            try {
                ciq.initialize(appContext, showUi, object : ConnectIQListener {
                    override fun onSdkReady() {
                        log("[INIT] (main) onSdkReady") 
                        initialized.set(true)
                        initInProgress.set(false)
                        refreshAndRegisterDevices()
                    }
                    override fun onInitializeError(status: ConnectIQ.IQSdkErrorStatus) {
                        log("[INIT] (main) onInitializeError=$status") 
                        initialized.set(false)
                        initInProgress.set(false)
                    }
                    override fun onSdkShutDown() {
                        log("[INIT] (main) onSdkShutDown") 
                        initialized.set(false)
                    }
                })
            } catch (e: Exception) {
                log("[INIT] (main) initialize threw: ${e.message}") 
                initInProgress.set(false)
            }
            return false 
        }

        if (initInProgress.get()) {
            repeat(40) {
                if (initialized.get()) return true
                Thread.sleep(100)
            }
            return initialized.get()
        }

        initInProgress.set(true)
        try {
            appContext = context.applicationContext
            val ciq = ConnectIQ.getInstance(appContext, ConnectIQ.IQConnectType.WIRELESS)
            log("[INIT] getInstance(WIRELESS) -> $ciq") 
            connectIQ = ciq

            val latch = CountDownLatch(1)
            val ok = AtomicBoolean(false)

            mainHandler.post {
                try {
                    ciq.initialize(appContext, showUi, object : ConnectIQListener {
                        override fun onSdkReady() {
                            log("[INIT] onSdkReady") 
                            ok.set(true); latch.countDown()
                        }
                        override fun onInitializeError(status: ConnectIQ.IQSdkErrorStatus) {
                            log("[INIT] onInitializeError=$status") 
                            ok.set(false); latch.countDown()
                        }
                        override fun onSdkShutDown() {
                            log("[INIT] onSdkShutDown") 
                            initialized.set(false)
                        }
                    })
                } catch (e: Exception) {
                    log("[INIT] initialize threw: ${e.message}") 
                    ok.set(false); latch.countDown()
                }
            }

            latch.await(8, TimeUnit.SECONDS)
            if (!ok.get()) {
                log("[INIT] Initialization failed") 
                return false 
            }
            refreshAndRegisterDevices()
            initialized.set(true)
            return true 
        } finally {
            initInProgress.set(false)
        }
    }

    fun getConnectedRealDevices(context: Context): List<IQDevice> {
        val ciq = connectIQ ?: return emptyList() 
        val connected = try { ciq.connectedDevices } catch (e: Exception) {
            log("[DEVICES] connectedDevices threw: ${e.message}") 
            emptyList()
        }
        // Treat connectedDevices as authoritative; only exclude likely simulators
        val real = connected.filter { isRealDevice(it) } 
        if (real.isNotEmpty()) {
            val names = real.joinToString(", ") { it.friendlyName ?: "Unnamed" }
            log("[DEVICES] connected=${connected.size} realConnected=${real.size} -> $names") 
        } else {
            log("[DEVICES] connected=${connected.size} realConnected=0") 
        }
        return real 
    }

    private fun isRealDevice(d: IQDevice): Boolean {
        // Do not gate on d.status; connectedDevices already implies connectivity
        return d.deviceIdentifier != KNOWN_SIMULATOR_ID &&
               !d.friendlyName.orEmpty().contains("simulator", true) 
    }

    fun refreshAndRegisterDevices() {
        val ciq = connectIQ ?: return 
        try { Thread.sleep(DISCOVERY_DELAY_MS) } catch (_: InterruptedException) {} 
        val all = try { ciq.knownDevices } catch (e: Exception) {
            log("[DEVICES] knownDevices threw: ${e.message}") 
            emptyList()
        }
        val candidates = all.filter { it.deviceIdentifier != KNOWN_SIMULATOR_ID }
        log("[DEVICES] known=${all.size} candidates(non-sim)=${candidates.size}") 
        candidates.forEach { d ->
            val key = "${d.deviceIdentifier}:${d.friendlyName}"
            if (!deviceListeners.containsKey(key)) {
                val listener: (IQDevice, IQDevice.IQDeviceStatus) -> Unit = { device, status ->
                    log("[DEVICE-EVENT] ${device.friendlyName} -> $status") 
                }
                try {
                    ciq.registerForDeviceEvents(d) { device, status -> listener(device, status) }
                    deviceListeners[key] = listener
                    log("[DEVICES] Registered device events for ${d.friendlyName}") 
                } catch (e: Exception) {
                    log("[DEVICES] registerForDeviceEvents failed: ${e.message}") 
                }
            }
        }
    }

    fun queryActivityStatus(
        context: Context,
        selected: IQDevice? = null,
        showUiIfInitNeeded: Boolean = false
    ): QueryResult {
        val ctx = context.applicationContext
        if (!hasRequiredPermissions(ctx)) {
            return QueryResult(false, "", "[ERROR] Missing required permissions", 0) 
        }
        if (!ensureInitialized(ctx, showUiIfInitNeeded)) {
            return QueryResult(false, "", "[ERROR] ConnectIQ not initialized", 0) 
        }
        val ciq = connectIQ!!

        refreshAndRegisterDevices()

        val devices = getConnectedRealDevices(ctx)
        val target = selected ?: devices.firstOrNull()
        if (target == null) {
            return QueryResult(false, "", "[ERROR] No connected real device found", 0) 
        }
        log("[QUERY] Target device: ${target.friendlyName} (${target.deviceIdentifier})") 

        val installed = AtomicBoolean(false)
        val infoLatch = CountDownLatch(1)
        try {
            ciq.getApplicationInfo(APP_UUID, target, object : ConnectIQ.IQApplicationInfoListener {
                override fun onApplicationInfoReceived(app: IQApp) {
                    log("[APPINFO] received app=${app.applicationId} on ${target.friendlyName}") 
                    installed.set(true); infoLatch.countDown()
                }
                override fun onApplicationNotInstalled(applicationId: String) {
                    log("[APPINFO] not installed id=$applicationId on ${target.friendlyName}") 
                    installed.set(false); infoLatch.countDown()
                }
            })
        } catch (e: Exception) {
            log("[APPINFO] Exception: ${e.message}") 
            installed.set(false); infoLatch.countDown()
        }
        infoLatch.await(3, TimeUnit.SECONDS)
        if (!installed.get()) {
            return QueryResult(false, "", "[ERROR] CIQ app not installed or unknown", devices.size) 
        }

        val app = IQApp(APP_UUID)
        val appKey = "${target.deviceIdentifier}:$APP_UUID"
        val responseBuf = StringBuilder()
        val got = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        val appListener = IQApplicationEventListener { device, iqApp, messages, status ->
            log("[APP-EVENT] from=${device.friendlyName} app=${iqApp.applicationId} status=$status size=${messages?.size ?: 0}") 
            if (!messages.isNullOrEmpty()) {
                responseBuf.append(messages.joinToString(",") { it.toString() })
                got.set(true); latch.countDown()
            }
        }
        try {
            if (!appListeners.containsKey(appKey)) {
                ciq.registerForAppEvents(target, app, appListener)
                appListeners[appKey] = appListener
                log("[APP] Registered app listener for ${target.friendlyName}") 
            }
        } catch (e: Exception) {
            log("[APP] registerForAppEvents failed: ${e.message}") 
        }

        val outbound: List<Any> = listOf("status?")
        fun sendOnce(): IQMessageStatus? {
            var local: IQMessageStatus? = null
            try {
                ciq.sendMessage(target, app, outbound, object : ConnectIQ.IQSendMessageListener {
                    override fun onMessageStatus(device: IQDevice, iqApp: IQApp, status: IQMessageStatus) {
                        local = status
                        log("[SEND] status=$status to ${device.friendlyName} app=${iqApp.applicationId}") 
                    }
                })
            } catch (e: Exception) {
                log("[SEND] Exception: ${e.message}") 
            }
            try { Thread.sleep(120) } catch (_: InterruptedException) {}
            return local
        }

        var sendStatus = sendOnce()
        if (sendStatus != IQMessageStatus.SUCCESS) {
            try { Thread.sleep(SEND_RETRY_DELAY_MS) } catch (_: InterruptedException) {}
            sendStatus = sendOnce()
        }

        latch.await(RESPONSE_TIMEOUT_MS, TimeUnit.MILLISECONDS)

        val debug = buildString {
            appendLine("device=${target.friendlyName} (${target.deviceIdentifier})")
            appendLine("status=${target.status}")
            appendLine("app=$APP_UUID")
            appendLine("sendStatus=${sendStatus ?: "null"}")
            appendLine("response=${if (got.get()) responseBuf.toString() else "<none>"}")
            appendLine("connectedRealDevices=${devices.size}")
        }

        return if (got.get()) {
            QueryResult(true, responseBuf.toString(), debug, devices.size) 
        } else {
            QueryResult(false, "", debug, devices.size) 
        }
    }
}