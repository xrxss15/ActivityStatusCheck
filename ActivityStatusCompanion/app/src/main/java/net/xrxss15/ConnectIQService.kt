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

        private const val RESPONSE_TIMEOUT_MS: Long = 15_000 // keep at 15s
        private const val TRANSIENT_RETRY_DELAY_MS: Long = 1_200
        private const val DISCOVERY_REFRESH_DELAY_MS: Long = 500

        // Exclude anything that looks like a simulator
        private const val KNOWN_SIMULATOR_ID = 12345L

        @Volatile
        private var INSTANCE: ConnectIQService? = null

        fun getInstance(): ConnectIQService {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ConnectIQService().also { INSTANCE = it }
            }
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
    private val mainHandler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null

    private val deviceListeners = mutableMapOf<String, IQDeviceEventListener>()
    private val appListeners = mutableMapOf<String, IQApplicationEventListener>()

    @Volatile
    private var logSink: ((String) -> Unit)? = null

    fun registerLogSink(sink: ((String) -> Unit)?) {
        logSink = sink
    }

    fun hasRequiredPermissions(ctx: Context): Boolean {
        val needs = mutableListOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 31) {
            needs.add(android.Manifest.permission.BLUETOOTH_SCAN)
            needs.add(android.Manifest.permission.BLUETOOTH_CONNECT)
        }
        val missing = needs.any {
            ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing) {
            log("[PERMS] Missing required permissions: $needs")
        } else {
            log("[PERMS] All required permissions granted")
        }
        return !missing
    }

    @Synchronized
    fun ensureInitialized(context: Context, showUi: Boolean): Boolean {
        if (initialized.get()) {
            log("[INIT] Already initialized")
            return true
        }
        try {
            appContext = context.applicationContext
            val ciq = ConnectIQ.getInstance(context, ConnectIQ.IQConnectType.WIRELESS)
            log("[INIT] getInstance(WIRELESS) -> $ciq")
            connectIQ = ciq

            val initLatch = CountDownLatch(1)
            val initOk = AtomicBoolean(false)
            mainHandler.post {
                try {
                    ciq.initialize(context, showUi, object : ConnectIQListener {
                        override fun onSdkReady() {
                            log("[INIT] onSdkReady")
                            initOk.set(true)
                            initLatch.countDown()
                        }

                        override fun onInitializeError(status: ConnectIQ.IQSdkErrorStatus) {
                            log("[INIT] onInitializeError=$status")
                            initOk.set(false)
                            initLatch.countDown()
                        }

                        override fun onSdkShutDown() {
                            log("[INIT] onSdkShutDown")
                        }
                    })
                } catch (e: Exception) {
                    log("[INIT] initialize threw: ${e.message}")
                    initOk.set(false)
                    initLatch.countDown()
                }
            }
            initLatch.await(8, TimeUnit.SECONDS)
            if (!initOk.get()) {
                log("[INIT] Initialization failed")
                return false
            }

            refreshAndRegisterDevices(context)
            initialized.set(true)
            return true
        } catch (e: Exception) {
            log("[INIT] Exception: ${e.message}")
            return false
        }
    }

    @Synchronized
    fun shutdown(context: Context) {
        try {
            connectIQ?.shutdown(context)
            log("[SHUTDOWN] CIQ shutdown requested")
        } catch (e: Exception) {
            log("[SHUTDOWN] Exception: ${e.message}")
        } finally {
            initialized.set(false)
            connectIQ = null
            deviceListeners.clear()
            appListeners.clear()
        }
    }

    fun getConnectedRealDevices(context: Context): List<IQDevice> {
        val ciq = connectIQ ?: return emptyList()
        val connected = try {
            ciq.connectedDevices
        } catch (e: Exception) {
            log("[DEVICES] connectedDevices threw: ${e.message}")
            emptyList<IQDevice>()
        }
        val realConnected = connected.filter { isRealConnected(it) }
        log("[DEVICES] connected=${connected.size} realConnected=${realConnected.size}")
        return realConnected
    }

    private fun isRealConnected(d: IQDevice): Boolean {
        return d.status == IQDevice.IQDeviceStatus.CONNECTED &&
            d.deviceIdentifier != KNOWN_SIMULATOR_ID &&
            !d.friendlyName.orEmpty().contains("simulator", ignoreCase = true)
    }

    fun refreshAndRegisterDevices(context: Context) {
        val ciq = connectIQ ?: return
        try {
            Thread.sleep(DISCOVERY_REFRESH_DELAY_MS)
        } catch (_: InterruptedException) {}
        val allKnown = try {
            ciq.knownDevices
        } catch (e: Exception) {
            log("[DEVICES] knownDevices threw: ${e.message}")
            emptyList<IQDevice>()
        }
        val candidates = allKnown.filter { it.deviceIdentifier != KNOWN_SIMULATOR_ID }
        log("[DEVICES] known=${allKnown.size} candidates(non-sim)=${candidates.size}")
        candidates.forEach { d ->
            val key = deviceKey(d)
            if (!deviceListeners.containsKey(key)) {
                val listener = IQDeviceEventListener { device, status ->
                    log("[DEVICE-EVENT] ${device.friendlyName} -> $status")
                }
                try {
                    ciq.registerForDeviceEvents(d, listener)
                    deviceListeners[key] = listener
                    log("[DEVICES] Registered device events for ${d.friendlyName}")
                } catch (e: Exception) {
                    log("[DEVICES] registerForDeviceEvents failed: ${e.message}")
                }
            }
        }
    }

    private fun deviceKey(d: IQDevice) = "${d.deviceIdentifier}:${d.friendlyName}"

    fun queryActivityStatus(
        context: Context,
        selected: IQDevice? = null,
        showUiIfInitNeeded: Boolean = false
    ): QueryResult {
        if (!hasRequiredPermissions(context)) {
            return QueryResult(false, "", "[ERROR] Missing required permissions", 0)
        }
        if (!ensureInitialized(context, showUiIfInitNeeded)) {
            return QueryResult(false, "", "[ERROR] ConnectIQ not initialized", 0)
        }
        val ciq = connectIQ!!
        refreshAndRegisterDevices(context)

        val devices = getConnectedRealDevices(context)
        val target = selected ?: devices.firstOrNull()
        if (target == null) {
            return QueryResult(false, "", "[ERROR] No connected real device found", 0)
        }

        // Verify app presence using the documented signature
        val appInstalled = AtomicBoolean(false)
        val infoLatch = CountDownLatch(1)
        try {
            ciq.getApplicationInfo(APP_UUID, target, object : ConnectIQ.IQApplicationInfoListener {
                override fun onApplicationInfoReceived(app: IQApp) {
                    log("[APPINFO] received app=${app.applicationId} status=${app.status} on ${target.friendlyName}")
                    // Treat an info callback as presence; BLE real device should report installed
                    appInstalled.set(true)
                    infoLatch.countDown()
                }

                override fun onApplicationNotInstalled(applicationId: String) {
                    log("[APPINFO] not installed id=$applicationId on ${target.friendlyName}")
                    appInstalled.set(false)
                    infoLatch.countDown()
                }
            })
        } catch (e: Exception) {
            log("[APPINFO] Exception: ${e.message}")
            appInstalled.set(false)
            infoLatch.countDown()
        }
        infoLatch.await(3, TimeUnit.SECONDS)
        if (!appInstalled.get()) {
            return QueryResult(
                false, "", "[ERROR] CIQ app not installed or unknown", devices.size
            )
        }

        // Register for app events before sending (4-arg listener signature)
        val app = IQApp(APP_UUID)
        val appKey = "${target.deviceIdentifier}:$APP_UUID"
        val responseBuf = StringBuilder()
        val gotResponse = AtomicBoolean(false)
        val latch = CountDownLatch(1)

        val appListener = IQApplicationEventListener { device, iqApp, messages, status ->
            log("[APP-EVENT] from=${device.friendlyName} app=${iqApp.applicationId} status=$status size=${messages?.size ?: 0}")
            if (messages != null && messages.isNotEmpty()) {
                responseBuf.append(messages.joinToString(",") { it.toString() })
                gotResponse.set(true)
                latch.countDown()
            }
        }
        try {
            if (!appListeners.containsKey(appKey)) {
                ciq.registerForAppEvents(target, app, appListener)
                appListeners[appKey] = appListener
                log("[APP] Registered app listener for ${target.friendlyName}")
            } else {
                log("[APP] Listener already registered for ${target.friendlyName}")
            }
        } catch (e: Exception) {
            log("[APP] registerForAppEvents failed: ${e.message}")
        }

        // Prepare outbound
        val outbound: List<Any> = listOf("status?")
        var lastSendStatus: IQMessageStatus? = null

        fun sendOnce(): IQMessageStatus? {
            var local: IQMessageStatus? = null
            try {
                ciq.sendMessage(target, app, outbound, object : ConnectIQ.IQSendMessageListener {
                    override fun onMessageStatus(
                        device: IQDevice,
                        iqApp: IQApp,
                        status: IQMessageStatus
                    ) {
                        local = status
                        log("[SEND] status=$status to ${device.friendlyName} app=${iqApp.applicationId}")
                    }
                })
            } catch (e: Exception) {
                log("[SEND] Exception: ${e.message}")
            }
            // tiny tick for async callback to land
            try { Thread.sleep(120) } catch (_: InterruptedException) {}
            return local
        }

        // First try
        lastSendStatus = sendOnce()
        // One retry on non-SUCCESS
        if (lastSendStatus != IQMessageStatus.SUCCESS) {
            try { Thread.sleep(TRANSIENT_RETRY_DELAY_MS) } catch (_: InterruptedException) {}
            lastSendStatus = sendOnce()
        }

        // Await response window (15s)
        latch.await(RESPONSE_TIMEOUT_MS, TimeUnit.MILLISECONDS)

        val debug = buildString {
            appendLine("device=${target.friendlyName} (${target.deviceIdentifier})")
            appendLine("status=${target.status}")
            appendLine("app=$APP_UUID")
            appendLine("sendStatus=${lastSendStatus ?: "null"}")
            appendLine("response=${if (gotResponse.get()) responseBuf.toString() else "<none>"}")
            appendLine("connectedRealDevices=${devices.size}")
        }

        return if (gotResponse.get()) {
            QueryResult(true, responseBuf.toString(), debug, devices.size)
        } else {
            QueryResult(false, "", debug, devices.size)
        }
    }

    private fun log(msg: String) {
        Log.d(TAG, msg)
        logSink?.invoke(msg)
    }
}