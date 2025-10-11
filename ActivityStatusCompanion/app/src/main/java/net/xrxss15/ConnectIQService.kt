package net.xrxss15

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.garmin.android.connectiq.ConnectIQ
import com.garmin.android.connectiq.ConnectIQ.ConnectIQListener
import com.garmin.android.connectiq.ConnectIQ.IQApplicationEventListener
import com.garmin.android.connectiq.ConnectIQ.IQSdkErrorStatus
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice

/**
 * Singleton service for managing Garmin ConnectIQ SDK integration.
 *
 * This service handles:
 * - SDK initialization and lifecycle management
 * - Device discovery and registration
 * - App event listener registration for activity messages
 * - Automatic SDK recovery when service binding is lost
 * - Message parsing and broadcast distribution
 *
 * The service maintains a single SDK instance across the application lifecycle
 * and automatically handles reconnection scenarios when the Garmin app unbinds.
 */
class ConnectIQService private constructor() {

    companion object {
        private const val TAG = "ConnectIQService"
        private const val MY_APP_ID = "3cdfe926-d53d-4f94-b6d1-7f1dac32156e"
        private const val DEVICE_STATUS_CHECK_DELAY_MS = 500L
        private const val SDK_REINIT_DELAY_MS = 1000L

        @Volatile
        private var instance: ConnectIQService? = null

        fun getInstance(): ConnectIQService {
            return instance ?: synchronized(this) {
                instance ?: ConnectIQService().also { instance = it }
            }
        }

        fun resetInstance() {
            synchronized(this) {
                instance?.shutdown()
                instance = null
            }
        }
    }

    private var connectIQ: ConnectIQ? = null
    private var isInitialized = false
    private val knownDevices = mutableSetOf<IQDevice>()
    private val handler = Handler(Looper.getMainLooper())
    private var messageCallback: ((String, String, Long) -> Unit)? = null
    private var deviceChangeCallback: (() -> Unit)? = null

    private val connectIQListener = object : ConnectIQListener {
        override fun onSdkReady() {
            log("SDK ready")
        }

        override fun onInitializeError(status: IQSdkErrorStatus) {
            logError("SDK initialization error: $status")
        }

        override fun onSdkShutDown() {
            log("SDK shut down")
            isInitialized = false
        }
    }

    private val deviceEventListener = object : ConnectIQ.IQDeviceEventListener {
        override fun onDeviceStatusChanged(device: IQDevice, status: IQDevice.IQDeviceStatus) {
            log("Device status changed: ${device.friendlyName} -> $status")
            refreshAndRegisterDevices()
        }
    }

    fun initializeSdkIfNeeded(context: Context, onComplete: () -> Unit) {
        if (isInitialized) {
            log("SDK already initialized")
            onComplete()
            return
        }

        try {
            if (connectIQ == null) {
                connectIQ = ConnectIQ.getInstance(context.applicationContext, ConnectIQ.IQConnectType.WIRELESS)
            }

            connectIQ?.initialize(context.applicationContext, false, connectIQListener)
            isInitialized = true
            log("SDK initialization started")
            onComplete()
        } catch (e: Exception) {
            logError("Failed to initialize SDK", e)
            isInitialized = false
        }
    }

    fun isInitialized(): Boolean = isInitialized

    fun setMessageCallback(callback: ((String, String, Long) -> Unit)?) {
        this.messageCallback = callback
    }

    fun setDeviceChangeCallback(callback: (() -> Unit)?) {
        this.deviceChangeCallback = callback
    }

    fun getConnectedRealDevices(context: Context): List<IQDevice> {
        return try {
            connectIQ?.connectedDevices?.filter { device ->
                device.status == IQDevice.IQDeviceStatus.CONNECTED &&
                device.deviceIdentifier != 0L
            } ?: emptyList()
        } catch (e: Exception) {
            logError("Failed to get connected devices", e)
            emptyList()
        }
    }

    fun registerListenersForAllDevices() {
        knownDevices.clear()

        connectIQ?.let { sdk ->
            try {
                sdk.connectedDevices.forEach { device ->
                    if (device.status == IQDevice.IQDeviceStatus.CONNECTED) {
                        registerAppListenerForDevice(sdk, device)
                        knownDevices.add(device)
                        
                        // Register device event listener for each device
                        try {
                            sdk.registerForDeviceEvents(device, deviceEventListener)
                        } catch (e: Exception) {
                            logError("Failed to register device event listener for ${device.friendlyName}", e)
                        }
                    }
                }
            } catch (e: Exception) {
                logError("Failed to register device listeners", e)
            }
        }
    }

    private fun refreshAndRegisterDevices() {
        try {
            handler.postDelayed({
                connectIQ?.let { sdk ->
                    sdk.connectedDevices.forEach { device ->
                        when (device.status) {
                            IQDevice.IQDeviceStatus.CONNECTED -> {
                                if (device !in knownDevices) {
                                    registerAppListenerForDevice(sdk, device)
                                    knownDevices.add(device)
                                }
                                // Notify callback that device list changed
                                deviceChangeCallback?.invoke()
                            }
                            IQDevice.IQDeviceStatus.NOT_CONNECTED -> {
                                if (device in knownDevices) {
                                    knownDevices.remove(device)
                                    deviceChangeCallback?.invoke()
                                }
                            }
                            else -> {}
                        }
                    }
                }
            }, DEVICE_STATUS_CHECK_DELAY_MS)
        } catch (e: Exception) {
            logError("Failed to refresh device registrations", e)
        }
    }

    private fun registerAppListenerForDevice(sdk: ConnectIQ, device: IQDevice) {
        try {
            val app = IQApp(MY_APP_ID)
            sdk.registerForAppEvents(device, app, object : IQApplicationEventListener {
                override fun onMessageReceived(
                    dev: IQDevice,
                    app: IQApp,
                    messages: MutableList<Any>,
                    status: ConnectIQ.IQMessageStatus
                ) {
                    if (status == ConnectIQ.IQMessageStatus.SUCCESS && messages.isNotEmpty()) {
                        val payload = messages[0].toString()
                        val receiveTime = System.currentTimeMillis()
                        log("Message from ${dev.friendlyName}: $payload")
                        messageCallback?.invoke(payload, dev.friendlyName, receiveTime)
                    }
                }
            })
            log("App listener registered for ${device.friendlyName}")
        } catch (e: Exception) {
            logError("Failed to register app listener for ${device.friendlyName}", e)
        }
    }

    private fun testAndRecoverSdk(ctx: Context): Boolean {
        return try {
            val devices = connectIQ?.connectedDevices
            if (devices == null) {
                log("SDK health check failed: null devices, reinitializing...")
                reinitializeSdk(ctx)
                false
            } else {
                log("SDK health check passed")
                true
            }
        } catch (e: Exception) {
            logError("SDK health check failed, reinitializing", e)
            reinitializeSdk(ctx)
            false
        }
    }

    private fun reinitializeSdk(context: Context) {
        try {
            connectIQ?.shutdown(context.applicationContext)
            connectIQ = null
            isInitialized = false
            handler.postDelayed({
                initializeSdkIfNeeded(context) {
                    registerListenersForAllDevices()
                }
            }, SDK_REINIT_DELAY_MS)
        } catch (e: Exception) {
            logError("Failed to reinitialize SDK", e)
        }
    }

    fun shutdown() {
        try {
            knownDevices.clear()
            messageCallback = null
            deviceChangeCallback = null
            handler.removeCallbacksAndMessages(null)
            connectIQ = null
            isInitialized = false
            log("Service shutdown complete")
        } catch (e: Exception) {
            logError("Error during shutdown", e)
        }
    }

    private fun log(message: String) {
        Log.i(TAG, message)
    }

    private fun logError(message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(TAG, message, throwable)
        } else {
            Log.e(TAG, message)
        }
    }
}