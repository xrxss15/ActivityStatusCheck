package net.xrxss15

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.garmin.android.connectiq.IQDevice
import java.text.SimpleDateFormat
import java.util.*

/**
 * ConnectIQ Query Worker - Continuous Message Listener
 * 
 * Runs indefinitely listening for messages from Garmin devices.
 * Monitors device connection changes and forwards all messages to Tasker.
 */
class ConnectIQQueryWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : Worker(appContext, workerParams) {

    companion object {
        private const val TAG = "ConnectIQWorker"
        private const val DEVICE_CHECK_INTERVAL_MS = 5000L // Check devices every 5 seconds
    }

    private val connectIQService = ConnectIQService.getInstance()
    private var lastDeviceList: List<IQDevice> = emptyList()
    private var isRunning = true

    private fun ts(): String = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
    
    private fun log(message: String) {
        val logMsg = "[${ts()}] $message"
        connectIQService.log(logMsg)
        android.util.Log.i(TAG, logMsg)
    }

    override fun doWork(): Result {
        val ctx = applicationContext
        
        try {
            log("Starting Garmin listener")
            
            // Initialize SDK
            if (!initializeSDK(ctx)) {
                sendMessage(ctx, "terminating|SDK initialization failed")
                return Result.failure()
            }
            
            // Get initial device list
            lastDeviceList = connectIQService.getConnectedRealDevices()
            sendDeviceListMessage(ctx, lastDeviceList)
            
            // Register message callback
            connectIQService.setMessageCallback { payload, deviceName, _ ->
                log("Message from $deviceName: $payload")
                sendMessage(ctx, "message_received|$deviceName|$payload")
            }
            
            // Register listeners
            connectIQService.registerListenersForAllDevices()
            log("Listeners registered - running continuously")
            
            // Run continuously, checking for device changes
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
        log("Initializing ConnectIQ SDK...")
        
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
            log("ERROR: SDK init exception: ${e.message}")
            false
        }
    }
    
    private fun runContinuously(ctx: Context) {
        while (isRunning && !isStopped) {
            try {
                // Check for device changes
                val currentDevices = connectIQService.getConnectedRealDevices()
                
                if (hasDeviceListChanged(currentDevices)) {
                    log("Device list changed - updating")
                    lastDeviceList = currentDevices
                    sendDeviceListMessage(ctx, currentDevices)
                    
                    // Re-register listeners for new device list
                    connectIQService.registerListenersForAllDevices()
                }
                
                // Sleep before next check
                Thread.sleep(DEVICE_CHECK_INTERVAL_MS)
                
            } catch (e: InterruptedException) {
                log("Worker interrupted - stopping")
                isRunning = false
            } catch (e: Exception) {
                log("ERROR in device check: ${e.message}")
            }
        }
        
        log("Worker loop exited")
    }
    
    private fun hasDeviceListChanged(newList: List<IQDevice>): Boolean {
        if (newList.size != lastDeviceList.size) return true
        
        val newIds = newList.map { it.deviceIdentifier }.toSet()
        val oldIds = lastDeviceList.map { it.deviceIdentifier }.toSet()
        
        return newIds != oldIds
    }
    
    private fun sendDeviceListMessage(ctx: Context, devices: List<IQDevice>) {
        // Format: devices|COUNT|NAME1|NAME2|...
        val parts = mutableListOf<String>()
        parts.add("devices")
        parts.add(devices.size.toString())
        devices.forEach { device ->
            parts.add(device.friendlyName ?: "Unknown")
        }
        
        val message = parts.joinToString("|")
        log("Sending device list: $message")
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
        log("Worker stopped by WorkManager")
        isRunning = false
    }
}