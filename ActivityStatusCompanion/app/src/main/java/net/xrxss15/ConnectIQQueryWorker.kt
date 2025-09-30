package net.xrxss15

import android.content.Context
import android.content.Intent
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.ListenableWorker.Result
import java.text.SimpleDateFormat
import java.util.*

/**
 * BACKGROUND WORKER WITH ENHANCED INTENT ACTION LOGGING
 * 
 * Performs ConnectIQ operations in background with detailed logging
 * of all intent actions sent to Tasker for monitoring and debugging.
 */
class ConnectIQQueryWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {

    private fun ts(): String = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
    
    private fun logInfo(category: String, message: String) {
        val logMsg = "[${ts()}] ℹ️ [WORKER.$category] $message"
        connectIQService.log(logMsg)
        android.util.Log.i("ActStatusWorker", logMsg)
    }
    
    private fun logSuccess(category: String, message: String) {
        val logMsg = "[${ts()}] ✅ [WORKER.$category] $message" 
        connectIQService.log(logMsg)
        android.util.Log.i("ActStatusWorker", logMsg)
    }
    
    private fun logError(category: String, message: String) {
        val logMsg = "[${ts()}] ❌ [WORKER.$category] $message"
        connectIQService.log(logMsg)
        android.util.Log.e("ActStatusWorker", logMsg)
    }
    
    private val connectIQService = ConnectIQService.getInstance()

    override fun doWork(): Result {
        val ctx = applicationContext
        
        try {
            logInfo("STARTUP", "Background worker started by Tasker trigger")
            
            // Initialize ConnectIQ SDK if needed
            logInfo("INIT", "Initializing ConnectIQ SDK for background operation...")
            
            // Get connected devices and report to Tasker immediately
            val devices = connectIQService.getConnectedRealDevices()
            val deviceNames = devices.map { it.friendlyName ?: "Unknown" }
            val deviceListString = deviceNames.joinToString("/")
            
            logSuccess("DISCOVERY", "Device scan completed - Found ${devices.size} device(s)")
            if (devices.isNotEmpty()) {
                logInfo("DISCOVERY", "Device list: $deviceListString")
            }
            
            // Send device list to Tasker with intent logging
            val deviceListIntent = Intent(ActivityStatusCheckReceiver.ACTION_DEVICE_LIST).apply {
                putExtra(ActivityStatusCheckReceiver.EXTRA_DEVICES, deviceListString)
            }
            
            logInfo("INTENT_OUT", "📤 Sending DEVICE_LIST intent to Tasker")
            logInfo("INTENT_OUT", "   Action: ${ActivityStatusCheckReceiver.ACTION_DEVICE_LIST}")
            logInfo("INTENT_OUT", "   Extra devices: '$deviceListString'")
            
            ctx.sendBroadcast(deviceListIntent)
            logSuccess("INTENT_OUT", "Device list intent broadcast completed")
            
            if (devices.isNotEmpty()) {
                logInfo("QUERY_SETUP", "Setting up response callback for immediate Tasker forwarding")
                
                // Register response listener to forward responses immediately to Tasker
                connectIQService.setResponseCallback { response, device, timestamp ->
                    logSuccess("CIQ_RESPONSE", "CIQ app response received from $device")
                    logInfo("CIQ_RESPONSE", "Response payload: '$response' at $timestamp")
                    
                    val responseIntent = Intent(ActivityStatusCheckReceiver.ACTION_CIQ_RESPONSE).apply {
                        putExtra(ActivityStatusCheckReceiver.EXTRA_RESPONSE, response)
                        putExtra(ActivityStatusCheckReceiver.EXTRA_TIMESTAMP, timestamp)
                        putExtra(ActivityStatusCheckReceiver.EXTRA_DEVICE, device)
                    }
                    
                    logInfo("INTENT_OUT", "📤 Sending CIQ_RESPONSE intent to Tasker")
                    logInfo("INTENT_OUT", "   Action: ${ActivityStatusCheckReceiver.ACTION_CIQ_RESPONSE}")
                    logInfo("INTENT_OUT", "   Extra response: '$response'")
                    logInfo("INTENT_OUT", "   Extra timestamp: '$timestamp'")
                    logInfo("INTENT_OUT", "   Extra device: '$device'")
                    
                    ctx.sendBroadcast(responseIntent)
                    logSuccess("INTENT_OUT", "CIQ response intent broadcast completed")
                }
                
                // Query activity status - response will be handled by callback
                logInfo("QUERY", "Sending activity status query to CIQ app...")
                val queryResult = connectIQService.queryActivityStatus(
                    context = ctx,
                    selected = null, // Use first available device
                    showUiIfInitNeeded = false
                )
                
                if (queryResult.success) {
                    logSuccess("QUERY", "Query message sent successfully to CIQ app")
                    logInfo("QUERY", "Waiting for CIQ app response via callback...")
                } else {
                    logError("QUERY", "Failed to send query message to CIQ app")
                }
                
            } else {
                logError("DISCOVERY", "No connected devices found - cannot proceed with query")
            }
            
            logSuccess("COMPLETION", "Background worker completed successfully")
            return Result.success()
            
        } catch (e: Exception) {
            logError("EXCEPTION", "Worker failed with error: ${e.message}")
            logError("EXCEPTION", "Stack trace: ${e.stackTraceToString()}")
            return Result.failure()
        }
    }
}