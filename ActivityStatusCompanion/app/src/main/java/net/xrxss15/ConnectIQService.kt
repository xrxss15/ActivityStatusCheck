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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ConnectIQService private constructor() {

    companion object {
        private const val APP_UUID = "7b408c6e-fc9c-4080-bad4-97a3557fc995"
        private const val TAG = "GarminActivityListener.Service"

        @Volatile
        private var instance: ConnectIQService? = null

        fun getInstance(): ConnectIQService {
            return instance ?: synchronized(this) {
                instance ?: ConnectIQService().also { instance = it }
            }
        }
    }

    private var connectIQ: ConnectIQ? = null
    private val initialized = AtomicBoolean(false)
    private val knownDevices = mutableSetOf<IQDevice>()
    private var messageCallback: ((String, String, Long) -> Unit)? = null
    private var deviceChangeCallback: (() -> Unit)? = null
    private val mainHandler = Handler(Looper.getMainLooper())

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

    fun isInitialized(): Boolean = initialized.get()

    fun initializeForWorker(context: Context): Boolean {
        if (initialized.get()) {
            log("Already initialized")
            return true
        }

        log("Init SDK")

        try {
            val appContext = context.applicationContext
            val ciq = ConnectIQ.getInstance(appContext, ConnectIQ.IQConnectType.WIRELESS)
            connectIQ = ciq
            
            val latch = CountDownLatch(1)
            val ok = AtomicBoolean(false)
            
            // Use Handler to post to Main thread - THIS IS THE KEY!
            mainHandler.post {
                try {
                    ciq.initialize(appContext, false, object : ConnectIQListener {
                        override fun onSdkReady() {
                            log("✓ SDK Ready")
                            ok.set(true)
                            latch.countDown()
                        }

                        override fun onInitializeError(status: ConnectIQ.IQSdkErrorStatus?) {
                            logError("✗ Init error: $status")
                            ok.set(false)
                            latch.countDown()
                        }

                        override fun onSdkShutDown() {
                            log("SDK shutdown")
                            initialized.set(false)
                        }
                    })
                } catch (e: Exception) {
                    logError("Exception: ${e.message}")
                    ok.set(false)
                    latch.countDown()
                }
            }
            
            // Wait for init to complete
            latch.await(15, TimeUnit.SECONDS)
            
            if (!ok.get()) {
                logError("Init failed or timeout")
                return false
            }
            
            initialized.set(true)
            log("✓ Init complete")
            return true

        } catch (e: Exception) {
            logError("Exception: ${e.message}")
            e.printStackTrace()
            return false
        }
    }

    fun getConnectedRealDevices(): List<IQDevice> {
        val ciq = connectIQ ?: return emptyList()
        return try {
            ciq.connectedDevices?.filter {
                it.status == IQDevice.IQDeviceStatus.CONNECTED && it.deviceIdentifier > 0
            } ?: emptyList()
        } catch (e: Exception) {
            logError("Error getting devices: ${e.message}")
            emptyList()
        }
    }

    fun registerListenersForAllDevices() {
        val devices = getConnectedRealDevices()
        log("Registering ${devices.size} device(s)")
        knownDevices.clear()
        knownDevices.addAll(devices)
        devices.forEach { registerListenerForDevice(it) }
        deviceChangeCallback?.invoke()
    }

    private fun registerListenerForDevice(device: IQDevice) {
        val ciq = connectIQ ?: return
        val app = IQApp(APP_UUID)
        try {
            ciq.registerForAppEvents(device, app, object : IQApplicationEventListener {
                override fun onMessageReceived(
                    device: IQDevice?, app: IQApp?, messages: MutableList<Any>?, status: IQMessageStatus?
                ) {
                    if (device == null || messages.isNullOrEmpty()) return
                    messages.forEach { msg ->
                        val payload = msg.toString()
                        val deviceName = device.friendlyName ?: "Unknown"
                        log("Message from $deviceName: $payload")
                        messageCallback?.invoke(payload, deviceName, System.currentTimeMillis())
                    }
                }
            })
            log("✓ Listener for ${device.friendlyName}")
        } catch (e: Exception) {
            logError("Failed listener: ${e.message}")
        }
    }

    fun setMessageCallback(callback: ((String, String, Long) -> Unit)?) {
        messageCallback = callback
    }

    fun setDeviceChangeCallback(callback: (() -> Unit)?) {
        deviceChangeCallback = callback
    }

    fun shutdown() {
        try {
            connectIQ?.shutdown(null)
            initialized.set(false)
            knownDevices.clear()
            messageCallback = null
            deviceChangeCallback = null
        } catch (e: Exception) {
            logError("Shutdown error: ${e.message}")
        }
    }
}