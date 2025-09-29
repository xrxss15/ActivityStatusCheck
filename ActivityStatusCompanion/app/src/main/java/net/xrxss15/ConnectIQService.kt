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

class ConnectIQService private constructor() {

    companion object {
        private const val APP_UUID = "7b408c6e-fc9c-4080-bad4-97a3557fc995"
        private const val RESPONSE_TIMEOUT_MS = 15_000L
        private const val DISCOVERY_DELAY_MS = 500L
        private const val KNOWN_SIMULATOR_ID = 12345L

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
    private fun ts(): String = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
    fun log(message: String) { logSink?.invoke(message) }

    fun hasRequiredPermissions(ctx: Context): Boolean {
        val needs = mutableListOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            needs.add(android.Manifest.permission.BLUETOOTH_SCAN)
            needs.add(android.Manifest.permission.BLUETOOTH_CONNECT)
        }
        val ok = needs.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
        log("[${ts()}] [PERMS] ${if (ok) "All required permissions granted" else "Missing: $needs"}")
        return ok
    }

    @Synchronized
    private fun ensureInitialized(context: Context, showUi: Boolean): Boolean {
        if (initialized.get()) {
            log("[${ts()}] [INIT] Already initialized")
            return true
        }
        val onMain = Looper.myLooper() == Looper.getMainLooper()
        if (onMain) {
            if (initInProgress.get()) {
                log("[${ts()}] [INIT] Init requested on main; already in progress (non-blocking)")
                return false
            }
            initInProgress.set(true)
            appContext = context.applicationContext
            val ciq = ConnectIQ.getInstance(appContext, ConnectIQ.IQConnectType.WIRELESS)
            log("[${ts()}] [INIT] (main) getInstance(WIRELESS) -> $ciq")
            connectIQ = ciq
            try {
                ciq.initialize(appContext, showUi, object : ConnectIQListener {
                    override fun onSdkReady() {
                        log("[${ts()}] [INIT] (main) onSdkReady")
                        initialized.set(true)
                        initInProgress.set(false)
                        refreshAndRegisterDevices()
                    }
                    override fun onInitializeError(status: ConnectIQ.IQSdkErrorStatus) {
                        log("[${ts()}] [INIT] (main) onInitializeError=$status")
                        initialized.set(false)
                        initInProgress.set(false)
                    }
                    override fun onSdkShutDown() {
                        log("[${ts()}] [INIT] (main) onSdkShutDown")
                        initialized.set(false)
                    }
                })
            } catch (e: Exception) {
                log("[${ts()}] [INIT] (main) initialize threw: ${e.message}")
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
            log("[${ts()}] [INIT] getInstance(WIRELESS) -> $ciq")
            connectIQ = ciq

            val latch = CountDownLatch(1)
            val ok = AtomicBoolean(false)
            mainHandler.post {
                try {
                    ciq.initialize(appContext, showUi, object : ConnectIQListener {
                        override fun onSdkReady() {
                            log("[${ts()}] [INIT] onSdkReady")
                            ok.set(true); latch.countDown()
                        }
                        override fun onInitializeError(status: ConnectIQ.IQSdkErrorStatus) {
                            log("[${ts()}] [INIT] onInitializeError=$status")
                            ok.set(false); latch.countDown()
                        }
                        override fun onSdkShutDown() {
                            log("[${ts()}] [INIT] onSdkShutDown")
                            initialized.set(false)
                        }
                    })
                } catch (e: Exception) {
                    log("[${ts()}] [INIT] initialize threw: ${e.message}")
                    ok.set(false); latch.countDown()
                }
            }
            latch.await(8, TimeUnit.SECONDS)
            if (!ok.get()) {
                log("[${ts()}] [INIT] Initialization failed")
                return false
            }
            refreshAndRegisterDevices()
            initialized.set(true)
            return true
        } finally {
            initInProgress.set(false)
        }
    }

    fun getConnectedRealDevices(): List<IQDevice> {
        val ciq = connectIQ ?: return emptyList()
        val connected = try { ciq.connectedDevices } catch (e: Exception) {
            log("[${ts()}] [DEVICES] connectedDevices threw: ${e.message}")
            emptyList()
        }
        val real = connected.filter { isRealDevice(it) }
        if (real.isNotEmpty()) {
            val names = real.joinToString(", ") { it.friendlyName ?: "Unnamed" }
            log("[${ts()}] [DEVICES] connected=${connected.size} realConnected=${real.size} -> $names")
        } else {
            log("[${ts()}] [DEVICES] connected=${connected.size} realConnected=0")
        }
        return real
    }

    private fun isRealDevice(d: IQDevice): Boolean {
        return d.deviceIdentifier != KNOWN_SIMULATOR_ID &&
               !d.friendlyName.orEmpty().contains("simulator", true)
    }

    fun refreshAndRegisterDevices() {
        val ciq = connectIQ ?: return
        try { Thread.sleep(DISCOVERY_DELAY_MS) } catch (_: InterruptedException) {}
        val all = try { ciq.knownDevices } catch (e: Exception) {
            log("[${ts()}] [DEVICES] knownDevices threw: ${e.message}")
            emptyList()
        }
        val candidates = all.filter { it.deviceIdentifier != KNOWN_SIMULATOR_ID }
        log("[${ts()}] [DEVICES] known=${all.size} candidates(non-sim)=${candidates.size}")
        candidates.forEach { d ->
            val key = "${d.deviceIdentifier}:${d.friendlyName}"
            if (!deviceListeners.containsKey(key)) {
                val listener: (IQDevice, IQDevice.IQDeviceStatus) -> Unit = { device, status ->
                    log("[${ts()}] [DEVICE-EVENT] ${device.friendlyName} -> $status")
                }
                try {
                    ciq.registerForDeviceEvents(d) { device, status -> listener(device, status) }
                    deviceListeners[key] = listener
                    log("[${ts()}] [DEVICES] Registered device events for ${d.friendlyName}")
                } catch (e: Exception) {
                    log("[${ts()}] [DEVICES] registerForDeviceEvents failed: ${e.message}")
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

        val devices = getConnectedRealDevices()
        val target = selected ?: devices.firstOrNull()
        if (target == null) {
            return QueryResult(false, "", "[ERROR] No connected real device found", 0)
        }
        log("[${ts()}] [QUERY] Target device: ${target.friendlyName} (${target.deviceIdentifier})")

        val installed = AtomicBoolean(false)
        val infoLatch = CountDownLatch(1)
        try {
            ciq.getApplicationInfo(APP_UUID, target, object : ConnectIQ.IQApplicationInfoListener {
                override fun onApplicationInfoReceived(app: IQApp) {
                    log("[${ts()}] [APPINFO] received app=${app.applicationId} on ${target.friendlyName}")
                    installed.set(true); infoLatch.countDown()
                }
                override fun onApplicationNotInstalled(applicationId: String) {
                    log("[${ts()}] [APPINFO] not installed id=$applicationId on ${target.friendlyName}")
                    installed.set(false); infoLatch.countDown()
                }
            })
        } catch (e: Exception) {
            log("[${ts()}] [APPINFO] Exception: ${e.message}")
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
            val rxTime = ts()
            val payload = if (messages.isNullOrEmpty()) "" else messages.joinToString("|") { it.toString() }
            log("[$rxTime] [RX] <- dev=${device.friendlyName} id=${device.deviceIdentifier} app=${iqApp.applicationId} status=$status size=${messages?.size ?: 0} payload=$payload")
            if (!messages.isNullOrEmpty()) {
                responseBuf.append(payload)
                got.set(true); latch.countDown()
            }
        }
        try {
            ciq.registerForAppEvents(target, app, appListener)
            appListeners[appKey] = appListener
            log("[${ts()}] [APP] Registered app listener for ${target.friendlyName}")
        } catch (e: Exception) {
            log("[${ts()}] [APP] registerForAppEvents failed: ${e.message}")
        }

        // Send exactly once (no retry) to eliminate double TX logs
        val txTime = ts()
        log("[$txTime] [TX] -> dev=${target.friendlyName} id=${target.deviceIdentifier} app=$APP_UUID payload=status?")
        try {
            ciq.sendMessage(target, app, listOf("status?"), object : ConnectIQ.IQSendMessageListener {
                override fun onMessageStatus(device: IQDevice, iqApp: IQApp, status: IQMessageStatus) {
                    log("[${ts()}] [TX-ACK] dev=${device.friendlyName} id=${device.deviceIdentifier} app=${iqApp.applicationId} status=$status")
                }
            })
        } catch (e: Exception) {
            log("[${ts()}] [SEND] Exception: ${e.message}")
        }

        latch.await(RESPONSE_TIMEOUT_MS, TimeUnit.MILLISECONDS)

        val debug = buildString {
            appendLine("device=${target.friendlyName} (${target.deviceIdentifier})")
            appendLine("status=${target.status}")
            appendLine("app=$APP_UUID")
            appendLine("sendStatus=<see TX-ACK lines>")
            appendLine("response=${if (got.get()) responseBuf.toString() else ""}")
            appendLine("connectedRealDevices=${devices.size}")
        }

        return if (got.get()) {
            QueryResult(true, responseBuf.toString(), debug, devices.size)
        } else {
            QueryResult(false, "", debug, devices.size)
        }
    }
}