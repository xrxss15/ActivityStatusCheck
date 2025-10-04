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
        private const val TAG = "GarminActivityListener.Worker"
    }

    private val connectIQService = ConnectIQService.getInstance()
    private val stopLatch = CountDownLatch(1)
    private val running = AtomicBoolean(true)
    private var lastDeviceIds = emptySet<Long>()

    override fun doWork(): Result {
        Log.i(TAG, "Worker starting")
        
        return try {
            if (!initializeSDK()) {
                sendBroadcast("terminating|SDK initialization failed")
                return Result.failure()
            }
            
            Thread.sleep(1500)
            
            val devices = connectIQService.getConnectedRealDevices()
            Log.i(TAG, "Found ${devices.size} device(s)")
            
            lastDeviceIds = devices.map { it.deviceIdentifier }.toSet()
            sendDeviceListMessage(devices)
            
            connectIQService.setMessageCallback { payload, deviceName, _ ->
                if (running.get()) {
                    sendBroadcast("message_received|$deviceName|$payload")
                }
            }
            
            connectIQService.setDeviceChangeCallback {
                if (running.get()) {
                    handleDeviceChange()
                }
            }
            
            Log.i(TAG, "Worker ready, waiting for events")
            waitUntilStopped()
            
            Result.success()
            
        } catch (e: Exception) {
            Log.e(TAG, "Worker error", e)
            sendBroadcast("terminating|Worker exception: ${e.message}")
            Result.failure()
        } finally {
            running.set(false)
            connectIQService.setMessageCallback(null)
            connectIQService.setDeviceChangeCallback(null)
        }
    }
    
    private fun initializeSDK(): Boolean {
        if (!connectIQService.hasRequiredPermissions(applicationContext)) {
            Log.e(TAG, "Missing permissions")
            return false
        }
        
        return connectIQService.initializeForWorker(applicationContext)
    }
    
    private fun handleDeviceChange() {
        val currentDevices = connectIQService.getConnectedRealDevices()
        val currentIds = currentDevices.map { it.deviceIdentifier }.toSet()
        
        if (currentIds != lastDeviceIds) {
            lastDeviceIds = currentIds
            sendDeviceListMessage(currentDevices)
            connectIQService.registerListenersForAllDevices()
        }
    }
    
    private fun waitUntilStopped() {
        try {
            stopLatch.await()
        } catch (e: InterruptedException) {
            // Interrupted
        }
    }
    
    private fun sendDeviceListMessage(devices: List<IQDevice>) {
        val parts = mutableListOf("devices", devices.size.toString())
        devices.forEach { device ->
            parts.add(device.friendlyName ?: "Unknown")
        }
        sendBroadcast(parts.joinToString("|"))
    }
    
    private fun sendBroadcast(message: String) {
        try {
            // Implicit broadcast for both MainActivity and Tasker
            val intent = Intent(ActivityStatusCheckReceiver.ACTION_MESSAGE).apply {
                putExtra(ActivityStatusCheckReceiver.EXTRA_MESSAGE, message)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            applicationContext.sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Broadcast failed", e)
        }
    }
    
    override fun onStopped() {
        super.onStopped()
        running.set(false)
        stopLatch.countDown()
    }
}