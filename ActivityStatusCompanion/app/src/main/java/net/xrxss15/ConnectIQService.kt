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
    private val handler = Handler(Looper.getMainLooper())
    private val initialized = AtomicBoolean(false)
    private val knownDevices = mutableSetOf<IQDevice>()
    private var messageCallback: ((String, String, Long) -> Unit)? = null
    private var deviceChangeCallback: (() -> Unit)? = null

    private fun log(msg: String) {
        android.util.Log.i(TAG, msg)
    }

    private fun ts(): String = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())

    fun hasRequiredPermissions(context: Context): Boolean {
        val needs = mutableListOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            needs.add(android.Manifest.permission.BLUETOOTH_SCAN)
            needs.add(android.Manifest.permission.BLUETOOTH_CONNECT)
        }
        return needs.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    }

    fun initializeForWorker(context: Context): Boolean {
        if (initialized.get()) {
            log("Already initialized")
            return true
        }

        try {
            connectIQ = ConnectIQ.getInstance(context.applicationContext, ConnectIQ.IQConnectType.WIRELESS)
            
            connectIQ?.initialize(context.applicationContext, false, object : ConnectIQListener {
                override fun onSdkReady() {
                    log("SDK ready")
                    initialized.set(true)
                    registerListenersForAllDevices()
                }

                override fun onInitializeError(status: ConnectIQ.IQSdkErrorStatus?) {
                    log("SDK init error: $status")
                    initialized.set(false)
                }

                override fun onSdkShutDown() {
                    log("SDK shutdown")
                    initialized.set(false)
                }
            })

            val latch = CountDownLatch(1)
            handler.postDelayed({ latch.countDown() }, 2000)
            latch.await(2500, TimeUnit.MILLISECONDS)

            return initialized.get()

        } catch (e: Exception) {
            log("Init exception: ${e.message}")
            return false
        }
    }

    fun getConnectedRealDevices(): List<IQDevice> {
        val ciq = connectIQ ?: return emptyList()
        return try {
            ciq.connectedDevices?.filter { device ->
                device.status == IQDevice.IQDeviceStatus.CONNECTED &&
                device.deviceIdentifier > 0
            } ?: emptyList()
        } catch (e: Exception) {
            log("Error getting devices: ${e.message}")
            emptyList()
        }
    }

    fun registerListenersForAllDevices() {
        val devices = getConnectedRealDevices()
        log("Registering listeners for ${devices.size} device(s)")
        
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

        try {
            ciq.registerForAppEvents(device, app, object : IQApplicationEventListener {
                override fun onMessageReceived(
                    device: IQDevice?,
                    app: IQApp?,
                    messages: MutableList<Any>?,
                    status: IQMessageStatus?
                ) {
                    if (device == null || messages.isNullOrEmpty()) return

                    messages.forEach { msg ->
                        val payload = msg.toString()
                        val deviceName = device.friendlyName ?: "Unknown"
                        val timestamp = System.currentTimeMillis()
                        
                        log("Message from ${deviceName}: $payload")
                        messageCallback?.invoke(payload, deviceName, timestamp)
                    }
                }
            })
            
            log("Registered listener for ${device.friendlyName}")
        } catch (e: Exception) {
            log("Failed to register listener: ${e.message}")
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
            log("Shutting down SDK")
            connectIQ?.shutdown(null)
            initialized.set(false)
            knownDevices.clear()
            messageCallback = null
            deviceChangeCallback = null
        } catch (e: Exception) {
            log("Shutdown error: ${e.message}")
        }
    }
}