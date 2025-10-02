package net.xrxss15

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Background Worker for Headless ConnectIQ Operations - Passive Listener
 */
class ConnectIQQueryWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : Worker(appContext, workerParams) {

    companion object {
        private const val LISTEN_TIMEOUT_MS = 5 * 60 * 1000L
        private const val TIMEOUT_ACTION = "net.xrxss15.TIMEOUT_ACTION"
        private const val TAG = "ActStatusWorker"
    }

    private val connectIQService = ConnectIQService.getInstance()
    private val startTime = System.currentTimeMillis()
    
    private val messageReceived = AtomicBoolean(false)
    private val receivedMessage = AtomicReference<String>()
    private val receivedDevice = AtomicReference<String>()
    private val messageLatch = CountDownLatch(1)
    
    private var timeoutPendingIntent: PendingIntent? = null
    private var timeoutReceiver: BroadcastReceiver? = null

    private fun ts(): String = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
    
    private fun logInfo(category: String, message: String) {
        val logMsg = "[${ts()}] ℹ️ [WORKER.$category] $message"
        connectIQService.log(logMsg)
        android.util.Log.i(TAG, logMsg)
    }
    
    private fun logSuccess(category: String, message: String) {
        val logMsg = "[${ts()}] ✅ [WORKER.$category] $message" 
        connectIQService.log(logMsg)
        android.util.Log.i(TAG, logMsg)
    }
    
    private fun logError(category: String, message: String) {
        val logMsg = "[${ts()}] ❌ [WORKER.$category] $message"
        connectIQService.log(logMsg)
        android.util.Log.e(TAG, logMsg)
    }
    
    private fun logWarning(category: String, message: String) {
        val logMsg = "[${ts()}] ⚠️ [WORKER.$category] $message"
        connectIQService.log(logMsg)
        android.util.Log.w(TAG, logMsg)
    }

    override fun doWork(): Result {
        val ctx = applicationContext
        
        try {
            logInfo("STARTUP", "HEADLESS WORKER - PASSIVE MODE")
            logInfo("STARTUP", "Listening for CIQ messages (5 min timeout)")
            
            if (!initializeConnectIQ(ctx)) {
                return Result.success()
            }
            
            val devices = discoverDevices(ctx)
            if (devices == null) {
                return Result.success()
            }
            
            if (devices.isEmpty()) {
                reportNoDevicesAndTerminateApp(ctx)
                return Result.success()
            }
            
            reportDevicesFound(ctx, devices)
            
            if (!registerPassiveListeners(ctx)) {
                return Result.success()
            }
            
            val messageResult = waitForMessageWithTimeout(ctx)
            
            reportFinalResultAndTerminateApp(ctx, messageResult, devices)
            
            logSuccess("COMPLETION", "Worker completed")
            return Result.success()
            
        } catch (e: Exception) {
            logError("EXCEPTION", "Worker failed: ${e.message}")
            reportErrorAndTerminateApp(ctx, "Worker exception: ${e.message}")
            return Result.failure()
        }
    }
    
    private fun initializeConnectIQ(ctx: Context): Boolean {
        logInfo("INIT", "Initializing SDK...")
        
        if (!connectIQService.hasRequiredPermissions(ctx)) {
            logError("INIT", "Missing permissions")
            reportErrorAndTerminateApp(ctx, "Missing permissions")
            return false
        }
        
        try {
            val initResult = connectIQService.initializeForWorker(ctx)
            if (!initResult) {
                logError("INIT", "SDK init failed")
                reportErrorAndTerminateApp(ctx, "SDK init failed")
                return false
            }
            
            logSuccess("INIT", "SDK ready")
            return true
            
        } catch (e: Exception) {
            logError("INIT", "Init exception: ${e.message}")
            reportErrorAndTerminateApp(ctx, "Init error: ${e.message}")
            return false
        }
    }
    
    private fun discoverDevices(ctx: Context): List<com.garmin.android.connectiq.IQDevice>? {
        logInfo("DISCOVERY", "Discovering devices...")
        
        try {
            val devices = connectIQService.getConnectedRealDevices()
            logSuccess("DISCOVERY", "Found ${devices.size} device(s)")
            
            devices.forEach { device ->
                logInfo("DISCOVERY", "  • ${device.friendlyName}")
            }
            
            return devices
            
        } catch (e: Exception) {
            logError("DISCOVERY", "Discovery failed: ${e.message}")
            reportErrorAndTerminateApp(ctx, "Discovery error: ${e.message}")
            return null
        }
    }
    
    private fun reportNoDevicesAndTerminateApp(ctx: Context) {
        logWarning("NO_DEVICES", "No devices - terminating")
        
        val payload = JSONObject().apply {
            put("stage", ActivityStatusCheckReceiver.STAGE_NO_DEVICES)
            put("timestamp", ts())
            put("device_count", 0)
            put("devices", JSONArray())
            put("message", "No connected devices")
            put("terminated", true)
            put("headless_mode", true)
        }
        
        sendResponseAndTerminateApp(ctx, ActivityStatusCheckReceiver.STAGE_NO_DEVICES, false, payload.toString())
    }
    
    private fun reportDevicesFound(ctx: Context, devices: List<com.garmin.android.connectiq.IQDevice>) {
        logSuccess("DEVICES", "Reporting ${devices.size} device(s)")
        
        val devicesArray = JSONArray()
        devices.forEach { device ->
            devicesArray.put(JSONObject().apply {
                put("name", device.friendlyName ?: "Unknown")
                put("id", device.deviceIdentifier.toString())
                put("status", device.status.toString())
            })
        }
        
        val payload = JSONObject().apply {
            put("stage", ActivityStatusCheckReceiver.STAGE_DEVICES_FOUND)
            put("timestamp", ts())
            put("device_count", devices.size)
            put("devices", devicesArray)
            put("message", "Found ${devices.size} device(s)")
            put("terminated", false)
            put("headless_mode", true)
        }
        
        sendResponse(ctx, ActivityStatusCheckReceiver.STAGE_DEVICES_FOUND, true, payload.toString(), false)
    }
    
    private fun registerPassiveListeners(ctx: Context): Boolean {
        logInfo("LISTENERS", "Registering listeners...")
        
        try {
            connectIQService.setMessageCallback { payload, deviceName, _ ->
                if (!messageReceived.getAndSet(true)) {
                    logSuccess("MESSAGE", "Received from $deviceName")
                    receivedMessage.set(payload)
                    receivedDevice.set(deviceName)
                    messageLatch.countDown()
                    cancelTimeoutAlarm(ctx)
                }
            }
            
            connectIQService.registerListenersForAllDevices()
            
            logSuccess("LISTENERS", "Listeners registered")
            
            val payload = JSONObject().apply {
                put("stage", "listeners_registered")
                put("timestamp", ts())
                put("message", "Listening for messages")
                put("terminated", false)
                put("headless_mode", true)
            }
            
            sendResponse(ctx, "listeners_registered", true, payload.toString(), false)
            return true
            
        } catch (e: Exception) {
            logError("LISTENERS", "Registration failed: ${e.message}")
            reportErrorAndTerminateApp(ctx, "Listener error: ${e.message}")
            return false
        }
    }
    
    private data class MessageResult(
        val success: Boolean,
        val type: String,
        val message: String = "",
        val deviceName: String = "",
        val error: String = ""
    )
    
    private fun waitForMessageWithTimeout(ctx: Context): MessageResult {
        logInfo("WAIT", "Waiting for messages (5 min)...")
        
        setupAlarmManagerTimeout(ctx)
        
        val waitStartTime = System.currentTimeMillis()
        
        try {
            messageLatch.await(LISTEN_TIMEOUT_MS + 5000, TimeUnit.MILLISECONDS)
            
            val waitDuration = System.currentTimeMillis() - waitStartTime
            logInfo("WAIT", "Wait completed after ${waitDuration / 1000}s")
            
            if (messageReceived.get()) {
                val msg = receivedMessage.get() ?: ""
                val dev = receivedDevice.get() ?: "Unknown"
                logSuccess("WAIT", "Message received from $dev")
                cancelTimeoutAlarm(ctx)
                return MessageResult(true, "message", msg, dev)
            } else {
                logWarning("WAIT", "Timeout - no messages")
                return MessageResult(false, "timeout", error = "No messages within 5 minutes")
            }
            
        } catch (e: Exception) {
            logError("WAIT", "Wait failed: ${e.message}")
            cancelTimeoutAlarm(ctx)
            return MessageResult(false, "error", error = "Wait error: ${e.message}")
        } finally {
            cleanupTimeout(ctx)
        }
    }
    
    private fun setupAlarmManagerTimeout(ctx: Context) {
        try {
            val alarmManager = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            
            timeoutReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == TIMEOUT_ACTION) {
                        logWarning("TIMEOUT", "Alarm fired")
                        if (!messageReceived.getAndSet(true)) {
                            messageLatch.countDown()
                        }
                    }
                }
            }
            
            val filter = IntentFilter(TIMEOUT_ACTION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.registerReceiver(timeoutReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                ctx.registerReceiver(timeoutReceiver, filter)
            }
            
            val timeoutIntent = Intent(TIMEOUT_ACTION).apply {
                setPackage(ctx.packageName)
            }
            
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            
            timeoutPendingIntent = PendingIntent.getBroadcast(ctx, 12345, timeoutIntent, flags)
            
            val triggerTime = SystemClock.elapsedRealtime() + LISTEN_TIMEOUT_MS
            alarmManager.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerTime, timeoutPendingIntent!!)
            
            logSuccess("TIMEOUT", "Alarm set for 5 min")
            
        } catch (e: Exception) {
            logError("TIMEOUT", "Alarm setup failed: ${e.message}")
        }
    }
    
    private fun cancelTimeoutAlarm(ctx: Context) {
        try {
            timeoutPendingIntent?.let { pendingIntent ->
                val alarmManager = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                alarmManager.cancel(pendingIntent)
            }
        } catch (e: Exception) {
            // Ignore
        }
    }
    
    private fun cleanupTimeout(ctx: Context) {
        try {
            cancelTimeoutAlarm(ctx)
            timeoutReceiver?.let { receiver ->
                try {
                    ctx.unregisterReceiver(receiver)
                } catch (e: IllegalArgumentException) {
                    // Already unregistered
                }
                timeoutReceiver = null
            }
            timeoutPendingIntent = null
        } catch (e: Exception) {
            // Ignore
        }
    }
    
    private fun reportFinalResultAndTerminateApp(ctx: Context, result: MessageResult, devices: List<com.garmin.android.connectiq.IQDevice>) {
        when (result.type) {
            "message" -> reportMessageReceivedAndTerminateApp(ctx, result, devices)
            "timeout" -> reportTimeoutAndTerminateApp(ctx, devices)
            "error" -> reportErrorAndTerminateApp(ctx, result.error)
        }
    }
    
    private fun reportMessageReceivedAndTerminateApp(ctx: Context, result: MessageResult, devices: List<com.garmin.android.connectiq.IQDevice>) {
        logSuccess("FINAL", "Message received - terminating")
        
        val devicesArray = JSONArray()
        devices.forEach { device ->
            devicesArray.put(JSONObject().apply {
                put("name", device.friendlyName ?: "Unknown")
                put("id", device.deviceIdentifier.toString())
            })
        }
        
        val parts = result.message.split("|")
        
        val payload = JSONObject().apply {
            put("stage", ActivityStatusCheckReceiver.STAGE_RESPONSE_RECEIVED)
            put("timestamp", ts())
            put("device_count", devices.size)
            put("devices", devicesArray)
            put("responding_device", result.deviceName)
            put("message", "Message received")
            put("raw_message", result.message)
            put("terminated", true)
            put("headless_mode", true)
            
            if (parts.size >= 4) {
                put("event", parts[0])
                put("event_timestamp", parts[1])
                put("activity", parts[2])
                put("retry_count", parts[3])
            }
            
            put("total_runtime_ms", System.currentTimeMillis() - startTime)
        }
        
        sendResponseAndTerminateApp(ctx, ActivityStatusCheckReceiver.STAGE_RESPONSE_RECEIVED, true, payload.toString())
    }
    
    private fun reportTimeoutAndTerminateApp(ctx: Context, devices: List<com.garmin.android.connectiq.IQDevice>) {
        logWarning("TIMEOUT", "Timeout - terminating")
        
        val devicesArray = JSONArray()
        devices.forEach { device ->
            devicesArray.put(JSONObject().apply {
                put("name", device.friendlyName ?: "Unknown")
                put("id", device.deviceIdentifier.toString())
            })
        }
        
        val payload = JSONObject().apply {
            put("stage", ActivityStatusCheckReceiver.STAGE_TIMEOUT)
            put("timestamp", ts())
            put("device_count", devices.size)
            put("devices", devicesArray)
            put("timeout_seconds", LISTEN_TIMEOUT_MS / 1000)
            put("message", "No messages within 5 minutes")
            put("terminated", true)
            put("headless_mode", true)
            put("total_runtime_ms", System.currentTimeMillis() - startTime)
        }
        
        sendResponseAndTerminateApp(ctx, ActivityStatusCheckReceiver.STAGE_TIMEOUT, false, payload.toString())
    }
    
    private fun reportErrorAndTerminateApp(ctx: Context, error: String) {
        logError("ERROR", "Error: $error")
        
        val payload = JSONObject().apply {
            put("stage", ActivityStatusCheckReceiver.STAGE_ERROR)
            put("timestamp", ts())
            put("error", error)
            put("message", "Worker error")
            put("terminated", true)
            put("headless_mode", true)
            put("total_runtime_ms", System.currentTimeMillis() - startTime)
        }
        
        sendResponseAndTerminateApp(ctx, ActivityStatusCheckReceiver.STAGE_ERROR, false, payload.toString())
    }
    
    private fun sendResponse(ctx: Context, stage: String, success: Boolean, payload: String, terminated: Boolean) {
        val responseIntent = Intent(ActivityStatusCheckReceiver.ACTION_RESPONSE).apply {
            putExtra(ActivityStatusCheckReceiver.EXTRA_STAGE, stage)
            putExtra(ActivityStatusCheckReceiver.EXTRA_SUCCESS, success)
            putExtra(ActivityStatusCheckReceiver.EXTRA_PAYLOAD, payload)
            putExtra(ActivityStatusCheckReceiver.EXTRA_TIMESTAMP, ts())
            putExtra(ActivityStatusCheckReceiver.EXTRA_TERMINATED, terminated)
            putExtra(ActivityStatusCheckReceiver.EXTRA_HEADLESS, true)
        }
        
        ctx.sendBroadcast(responseIntent)
    }
    
    private fun sendResponseAndTerminateApp(ctx: Context, stage: String, success: Boolean, payload: String) {
        logWarning("TERMINATION", "Terminating app")
        
        sendResponse(ctx, stage, success, payload, true)
        
        try {
            Thread.sleep(100)
        } catch (e: InterruptedException) {
            // Ignore
        }
        
        System.exit(0)
    }
}