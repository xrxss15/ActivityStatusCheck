package net.xrxss15

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
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
    private var initError: ConnectIQ.IQSdkErrorStatus? = null

    private fun log(msg: String) {
        android.util.Log.i(TAG, msg)
    }

    private fun logError(msg: String) {
        android.util.Log.e(TAG, msg)
    }

    fun hasRequiredPermissions(context: Context): Boolean {
        val needs = mutableListOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            needs.add(android.Manifest.permission.BLUETOOTH_SCAN)
            needs.add(android.Manifest.permission.BLUETOOTH_CONNECT)
        }
        val result = needs.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        log("Permission check: $result")
        return result
    }

    fun initializeForWorker(context: Context): Boolean {
        if (initialized.get()) {
            log("Already initialized, returning true")
            return true
        }

        log("========================================")
        log("Starting ConnectIQ SDK initialization")
        log("Thread: ${Thread.currentThread().name}")
        log("========================================")

        try {
            log("Creating ConnectIQ instance...")
            connectIQ = ConnectIQ.getInstance(context.applicationContext, ConnectIQ.IQConnectType.WIRELESS)
            log("✓ ConnectIQ instance created")
            
            val initLatch = CountDownLatch(1)
            initError = null
            
            log("Calling initialize()...")
            connectIQ?.initialize(context.applicationContext, false, object : ConnectIQListener {
                override fun onSdkReady() {
                    log("✓✓✓ onSdkReady() callback received!")
                    initialized.set(true)
                    initLatch.countDown()
                }

                override fun onInitializeError(status: ConnectIQ.IQSdkErrorStatus?) {
                    logError("✗✗✗ onInitializeError() callback received!")
                    logError("Error status: $status")
                    when (status) {
                        ConnectIQ.IQSdkErrorStatus.GCM_NOT_INSTALLED -> 
                            logError("  → Garmin Connect Mobile not installed")
                        ConnectIQ.IQSdkErrorStatus.GCM_UPGRADE_NEEDED -> 
                            logError("  → Garmin Connect Mobile needs upgrade")
                        ConnectIQ.IQSdkErrorStatus.SERVICE_ERROR -> 
                            logError("  → Service error")
                        else -> logError("  → Unknown error: $status")
                    }
                    initError = status
                    initialized.set(false)
                    initLatch.countDown()
                }

                override fun onSdkShutDown() {
                    log("onSdkShutDown() callback received")
                    initialized.set(false)
                }
            })
            log("✓ initialize() called, waiting for callback...")

            // Wait up to 15 seconds for SDK to initialize
            var elapsed = 0
            while (initLatch.count > 0 && elapsed < 15) {
                log("Waiting... ${elapsed}s elapsed")
                val completed = initLatch.await(1, TimeUnit.SECONDS)
                if (completed) {
                    log("✓ Latch released after ${elapsed + 1}s")
                    break
                }
                elapsed++
            }
            
            if (initLatch.count > 0) {
                logError("✗ SDK initialization TIMEOUT after 15 seconds")
                logError("  No callback received from ConnectIQ SDK")
                return false
            }
            
            log("========================================")
            log("SDK initialization completed")
            log("Result: ${initialized.get()}")
            if (initError != null) {
                logError("Error: $initError")
            }
            log("========================================")
            
            return initialized.get()

        } catch (e: Exception) {
            logError("Exception during initialization: ${e.message}")
            e.printStackTrace()
            return false
        }
    }

    fun getConnectedRealDevices(): List<IQDevice> {
        val ciq = connectIQ
        if (ciq == null) {
            log("getConnectedRealDevices: connectIQ is null")
            return emptyList()
        }
        
        return try {
            val devices = ciq.connectedDevices
            if (devices == null) {
                log("getConnectedRealDevices: connectedDevices is null")
                return emptyList()
            }
            
            val filtered = devices.filter { device ->
                device.status == IQDevice.IQDeviceStatus.CONNECTED &&
                device.deviceIdentifier > 0
            }
            log("getConnectedRealDevices: ${filtered.size} connected devices")
            filtered
        } catch (e: Exception) {
            logError("Error getting devices: ${e.message}")
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
            
            log("✓ Registered listener for ${device.friendlyName}")
        } catch (e: Exception) {
            logError("Failed to register listener: ${e.message}")
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
            logError("Shutdown error: ${e.message}")
        }
    }
}