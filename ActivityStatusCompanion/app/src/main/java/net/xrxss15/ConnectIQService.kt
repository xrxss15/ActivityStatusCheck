package net.xrxss15

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.garmin.android.connectiq.ConnectIQ
import com.garmin.android.connectiq.ConnectIQ.IQApplicationEventListener
import com.garmin.android.connectiq.ConnectIQ.IQMessageStatus
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice

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
    private val knownDevices = mutableSetOf<IQDevice>()
    private var messageCallback: ((String, String, Long) -> Unit)? = null
    private var deviceChangeCallback: (() -> Unit)? = null

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

    // Called from MainActivity after SDK is initialized
    fun setSdkInstance(sdk: ConnectIQ?) {
        connectIQ = sdk
        log("SDK instance set")
    }

    fun isInitialized(): Boolean = connectIQ != null

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
        knownDevices.clear()
        messageCallback = null
        deviceChangeCallback = null
    }
}