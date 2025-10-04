package net.xrxss15

import android.content.Context
import android.content.Intent
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.garmin.android.connectiq.IQDevice
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ConnectIQ Query Worker - Pure Event-Driven Listener
 * ZERO CPU usage when idle - only active during actual events
 */
class ConnectIQQueryWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : Worker(appContext, workerParams) {

    private val connectIQService = ConnectIQService.getInstance()
    private val stopLatch = CountDownLatch(1)
    private val running = AtomicBoolean(true)
    private var lastDeviceIds = emptySet<Long>()

    private fun ts(): String = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
    
    private fun log(message: String) {
        sendBroadcastSafe("log|[${ts()}] $message")
    }

    override fun doWork(): Result {
        return try {
            log("Starting listener (event-driven)")
            
            if (!initializeSDK()) {
                sendBroadcastSafe("terminating|SDK initialization failed")
                return Result.failure()
            }
            
            val devices = connectIQService.getConnectedRealDevices()
            lastDeviceIds = devices.map { it.deviceIdentifier }.toSet()
            sendDeviceListMessage(devices)
            
            // Message callback - fires when messages arrive
            connectIQService.setMessageCallback { payload, deviceName, _ ->
                if (running.get()) {
                    log("Message from $deviceName")
                    sendBroadcastSafe("message_received|$deviceName|$payload")
                }
            }
            
            // Device change callback - fires on connect/disconnect
            connectIQService.setDeviceChangeCallback {
                if (running.get()) {
                    handleDeviceChange()
                }
            }
            
            connectIQService.registerListenersForAllDevices()
            log("Listeners registered - waiting for events")
            
            // Pure wait - NO periodic wake-ups, only events
            waitUntilStopped()
            
            log("Worker stopped")
            Result.success()
            
        } catch (e: Exception) {
            log("ERROR: ${e.message}")
            sendBroadcastSafe("terminating|Worker exception: ${e.message}")
            Result.failure()
        } finally {
            running.set(false)
            connectIQService.setMessageCallback(null)
            connectIQService.setDeviceChangeCallback(null)
        }
    }
    
    private fun initializeSDK(): Boolean {
        if (!connectIQService.hasRequiredPermissions(applicationContext)) {
            log("ERROR: Missing permissions")
            return false
        }
        
        return try {
            connectIQService.initializeForWorker(applicationContext)
        } catch (e: Exception) {
            log("ERROR: ${e.message}")
            false
        }
    }
    
    private fun handleDeviceChange() {
        val currentDevices = connectIQService.getConnectedRealDevices()
        val currentIds = currentDevices.map { it.deviceIdentifier }.toSet()
        
        // Only process if device list actually changed
        if (currentIds != lastDeviceIds) {
            log("Device list changed")
            lastDeviceIds = currentIds
            sendDeviceListMessage(currentDevices)
            
            // Re-register listeners (SDK handles duplicate registrations)
            connectIQService.registerListenersForAllDevices()
        }
    }
    
    /**
     * Pure wait - ZERO CPU usage
     * Only wakes when:
     * - onStopped() called (releases latch)
     * - Thread interrupted
     */
    private fun waitUntilStopped() {
        try {
            stopLatch.await() // Infinite wait - no timeout!
        } catch (e: InterruptedException) {
            // Worker interrupted - exit gracefully
        }
    }
    
    private fun sendDeviceListMessage(devices: List<IQDevice>) {
        val parts = mutableListOf("devices", devices.size.toString())
        devices.forEach { device ->
            parts.add(device.friendlyName ?: "Unknown")
        }
        sendBroadcastSafe(parts.joinToString("|"))
    }
    
    private fun sendBroadcastSafe(message: String) {
        try {
            val intent = Intent(ActivityStatusCheckReceiver.ACTION_MESSAGE).apply {
                putExtra(ActivityStatusCheckReceiver.EXTRA_MESSAGE, message)
            }
            applicationContext.sendBroadcast(intent)
        } catch (e: Exception) {
            // Ignore broadcast failures
        }
    }
    
    override fun onStopped() {
        super.onStopped()
        running.set(false)
        stopLatch.countDown() // Wake up waitUntilStopped()
    }
}