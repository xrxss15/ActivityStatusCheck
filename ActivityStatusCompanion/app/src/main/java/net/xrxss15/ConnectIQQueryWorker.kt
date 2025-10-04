package net.xrxss15

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.garmin.android.connectiq.IQDevice
import java.text.SimpleDateFormat
import java.util.*

/**
 * ConnectIQ Query Worker - Continuous Message Listener
 */
class ConnectIQQueryWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : Worker(appContext, workerParams) {

    companion object {
        private const val DEVICE_CHECK_INTERVAL_MS = 5000L
    }

    private val connectIQService = ConnectIQService.getInstance()
    private var lastDeviceList: List<IQDevice> = emptyList()
    private var isRunning = true

    private fun ts(): String = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
    
    private fun log(message: String) {
        val logMsg = "[${ts()}] $message"
        connectIQService.log(logMsg)
        // No logcat output
    }

    override fun doWork(): Result {
        val ctx = applicationContext
        
        try {
            log("Starting listener")
            
            if (!initializeSDK(ctx)) {
                sendMessage(ctx, "terminating|SDK initialization failed")
                return Result.failure()
            }
            
            lastDeviceList = connectIQService.getConnectedRealDevices()
            sendDeviceListMessage(ctx, lastDeviceList)
            
            connectIQService.setMessageCallback { payload, deviceName, _ ->
                log("Message from $deviceName: $payload")
                sendMessage(ctx, "message_received|$deviceName|$payload")
            }
            
            connectIQService.registerListenersForAllDevices()
            log("Listeners registered")
            
            runContinuously(ctx)
            
            log("Worker stopped")
            return Result.success()
            
        } catch (e: Exception) {
            log("ERROR: ${e.message}")
            sendMessage(ctx, "terminating|Worker exception: ${e.message}")
            return Result.failure()
        }
    }
    
    private fun initializeSDK(ctx: Context): Boolean {
        log("Initializing SDK...")
        
        if (!connectIQService.hasRequiredPermissions(ctx)) {
            log("ERROR: Missing permissions")
            return false
        }
        
        return try {
            val result = connectIQService.initializeForWorker(ctx)
            if (result) {
                log("SDK ready")
            } else {
                log("ERROR: SDK init failed")
            }
            result
        } catch (e: Exception) {
            log("ERROR: SDK exception: ${e.message}")
            false
        }
    }
    
    private fun runContinuously(ctx: Context) {
        while (isRunning && !isStopped) {
            try {
                val currentDevices = connectIQService.getConnectedRealDevices()
                
                if (hasDeviceListChanged(currentDevices)) {
                    log("Device list changed")
                    lastDeviceList = currentDevices
                    sendDeviceListMessage(ctx, currentDevices)
                    connectIQService.registerListenersForAllDevices()
                }
                
                Thread.sleep(DEVICE_CHECK_INTERVAL_MS)
                
            } catch (e: InterruptedException) {
                log("Worker interrupted")
                isRunning = false
            } catch (e: Exception) {
                log("ERROR: ${e.message}")
            }
        }
    }
    
    private fun hasDeviceListChanged(newList: List<IQDevice>): Boolean {
        if (newList.size != lastDeviceList.size) return true
        
        val newIds = newList.map { it.deviceIdentifier }.toSet()
        val oldIds = lastDeviceList.map { it.deviceIdentifier }.toSet()
        
        return newIds != oldIds
    }
    
    private fun sendDeviceListMessage(ctx: Context, devices: List<IQDevice>) {
        val parts = mutableListOf<String>()
        parts.add("devices")
        parts.add(devices.size.toString())
        devices.forEach { device ->
            parts.add(device.friendlyName ?: "Unknown")
        }
        
        val message = parts.joinToString("|")
        log("Device list: $message")
        sendMessage(ctx, message)
    }
    
    private fun sendMessage(ctx: Context, message: String) {
        val intent = android.content.Intent(ActivityStatusCheckReceiver.ACTION_MESSAGE).apply {
            putExtra(ActivityStatusCheckReceiver.EXTRA_MESSAGE, message)
        }
        ctx.sendBroadcast(intent)
    }
    
    override fun onStopped() {
        super.onStopped()
        log("Worker stopped")
        isRunning = false
    }
}