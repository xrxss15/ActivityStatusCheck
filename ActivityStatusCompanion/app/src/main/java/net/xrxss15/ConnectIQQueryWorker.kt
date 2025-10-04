package net.xrxss15

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.garmin.android.connectiq.IQDevice
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

class ConnectIQQueryWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : Worker(appContext, workerParams) {

    companion object {
        private const val TAG = "ConnectIQWorker"
    }

    private val connectIQService = ConnectIQService.getInstance()
    private val stopLatch = CountDownLatch(1)
    private val running = AtomicBoolean(true)
    private var lastDeviceIds = emptySet<Long>()

    override fun doWork(): Result {
        return try {
            Log.i(TAG, "Worker starting")
            
            if (!initializeSDK()) {
                sendBroadcast("terminating|SDK initialization failed")
                return Result.failure()
            }
            
            Log.i(TAG, "SDK initialized, waiting for device discovery")
            Thread.sleep(1500)
            
            val devices = connectIQService.getConnectedRealDevices()
            Log.i(TAG, "Found ${devices.size} device(s)")
            
            lastDeviceIds = devices.map { it.deviceIdentifier }.toSet()
            sendDeviceListMessage(devices)
            
            connectIQService.setMessageCallback { payload, deviceName, _ ->
                if (running.get()) {
                    Log.i(TAG, "Message from $deviceName: $payload")
                    sendBroadcast("message_received|$deviceName|$payload")
                }
            }
            
            connectIQService.setDeviceChangeCallback {
                if (running.get()) {
                    handleDeviceChange()
                }
            }
            
            Log.i(TAG, "Worker ready - waiting for events")
            
            waitUntilStopped()
            
            Log.i(TAG, "Worker stopped")
            Result.success()
            
        } catch (e: Exception) {
            Log.e(TAG, "Worker error: ${e.message}", e)
            sendBroadcast("terminating|Worker exception: ${e.message}")
            Result.failure()
        } finally {
            running.set(false)
            connectIQService.setMessageCallback(null)
            connectIQService.setDeviceChangeCallback(null)
        }
    }
    
    private fun initializeSDK(): Boolean {
        Log.i(TAG, "Checking permissions")
        if (!connectIQService.hasRequiredPermissions(applicationContext)) {
            Log.e(TAG, "Missing permissions")
            return false
        }
        
        Log.i(TAG, "Initializing ConnectIQ SDK")
        return try {
            val result = connectIQService.initializeForWorker(applicationContext)
            if (result) {
                Log.i(TAG, "SDK initialization successful")
            } else {
                Log.e(TAG, "SDK initialization failed")
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "SDK initialization exception", e)
            false
        }
    }
    
    private fun handleDeviceChange() {
        Log.i(TAG, "Device change detected")
        val currentDevices = connectIQService.getConnectedRealDevices()
        val currentIds = currentDevices.map { it.deviceIdentifier }.toSet()
        
        if (currentIds != lastDeviceIds) {
            Log.i(TAG, "Device list changed: ${currentDevices.size} device(s)")
            lastDeviceIds = currentIds
            sendDeviceListMessage(currentDevices)
            connectIQService.registerListenersForAllDevices()
        }
    }
    
    private fun waitUntilStopped() {
        try {
            stopLatch.await()
        } catch (e: InterruptedException) {
            Log.i(TAG, "Worker interrupted")
        }
    }
    
    private fun sendDeviceListMessage(devices: List<IQDevice>) {
        val parts = mutableListOf("devices", devices.size.toString())
        devices.forEach { device ->
            val name = device.friendlyName ?: "Unknown"
            parts.add(name)
            Log.d(TAG, "Device: $name")
        }
        sendBroadcast(parts.joinToString("|"))
    }
    
    private fun sendBroadcast(message: String) {
        try {
            val intent = Intent(ActivityStatusCheckReceiver.ACTION_MESSAGE).apply {
                putExtra(ActivityStatusCheckReceiver.EXTRA_MESSAGE, message)
            }
            applicationContext.sendBroadcast(intent)
            Log.d(TAG, "Broadcast sent: ${message.take(50)}...")
        } catch (e: Exception) {
            Log.e(TAG, "Broadcast failed", e)
        }
    }
    
    override fun onStopped() {
        super.onStopped()
        Log.i(TAG, "onStopped called")
        running.set(false)
        stopLatch.countDown()
    }
}