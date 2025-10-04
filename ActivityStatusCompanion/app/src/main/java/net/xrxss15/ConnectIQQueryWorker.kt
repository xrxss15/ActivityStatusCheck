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
            log("Worker starting...")
            
            if (!initializeSDK()) {
                sendBroadcastSafe("terminating|SDK initialization failed")
                return Result.failure()
            }
            
            log("SDK initialized, waiting for device discovery...")
            
            // CRITICAL FIX: Wait for device discovery
            // SDK needs time after initialization to discover connected devices
            Thread.sleep(1500)
            
            log("Getting connected devices...")
            val devices = connectIQService.getConnectedRealDevices()
            log("Found ${devices.size} device(s)")
            
            lastDeviceIds = devices.map { it.deviceIdentifier }.toSet()
            sendDeviceListMessage(devices)
            
            log("Registering message callback...")
            connectIQService.setMessageCallback { payload, deviceName, _ ->
                if (running.get()) {
                    log("Message from $deviceName")
                    sendBroadcastSafe("message_received|$deviceName|$payload")
                }
            }
            
            log("Registering device change callback...")
            connectIQService.setDeviceChangeCallback {
                if (running.get()) {
                    handleDeviceChange()
                }
            }
            
            log("Worker ready - waiting for events")
            
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
        log("Checking permissions...")
        if (!connectIQService.hasRequiredPermissions(applicationContext)) {
            log("ERROR: Missing permissions")
            return false
        }
        
        log("Initializing ConnectIQ SDK...")
        return try {
            val result = connectIQService.initializeForWorker(applicationContext)
            if (result) {
                log("SDK initialization successful")
            } else {
                log("SDK initialization failed")
            }
            result
        } catch (e: Exception) {
            log("SDK initialization exception: ${e.message}")
            false
        }
    }
    
    private fun handleDeviceChange() {
        log("Device change detected")
        val currentDevices = connectIQService.getConnectedRealDevices()
        val currentIds = currentDevices.map { it.deviceIdentifier }.toSet()
        
        if (currentIds != lastDeviceIds) {
            log("Device list changed: ${currentDevices.size} device(s)")
            lastDeviceIds = currentIds
            sendDeviceListMessage(currentDevices)
            connectIQService.registerListenersForAllDevices()
        }
    }
    
    private fun waitUntilStopped() {
        try {
            stopLatch.await()
        } catch (e: InterruptedException) {
            // Worker interrupted
        }
    }
    
    private fun sendDeviceListMessage(devices: List<IQDevice>) {
        val parts = mutableListOf("devices", devices.size.toString())
        devices.forEach { device ->
            val name = device.friendlyName ?: "Unknown"
            parts.add(name)
            log("  Device: $name")
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
        stopLatch.countDown()
    }
}