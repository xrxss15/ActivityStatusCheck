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
import kotlin.system.exitProcess

/**
 * Background Worker for Headless ConnectIQ Operations
 * 
 * This worker implements the complete headless workflow for querying ConnectIQ devices:
 * 
 * 1. Initialize ConnectIQ SDK (report errors and terminate if failed)
 * 2. Discover connected devices (report results, terminate if empty)
 * 3. Send message to CIQ app (report result, terminate on failure)
 * 4. Wait for response with 5-minute AlarmManager timeout (most reliable and battery-efficient)
 * 5. Report final result and terminate entire app process via System.exit(0)
 * 
 * Battery Efficiency Design:
 * - Uses CountDownLatch for waiting - no busy loops or polling
 * - AlarmManager for precise 5-minute timeout - most battery-efficient method
 * - Minimal CPU usage during waiting period
 * - Immediate app termination after completion
 * - No unnecessary background services
 * 
 * Termination Strategy:
 * - Every error condition reports via intent and calls System.exit(0)
 * - Empty device list reports and terminates
 * - Message send failure reports and terminates
 * - Successful response reports and terminates
 * - 5-minute timeout reports and terminates
 * 
 * @property applicationContext Application context for background operations
 * @property workerParams WorkManager parameters
 */
class ConnectIQQueryWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : Worker(appContext, workerParams) {

    companion object {
        /**
         * CIQ response timeout - exactly 5 minutes as required.
         * Note: Initialization and message send have separate shorter timeouts.
         */
        private const val RESPONSE_TIMEOUT_MS = 5 * 60 * 1000L // 300,000ms = 5 minutes
        
        /**
         * Timeout action for AlarmManager broadcast
         */
        private const val TIMEOUT_ACTION = "net.xrxss15.TIMEOUT_ACTION"
        
        private const val TAG = "ActStatusWorker"
    }

    // Service instance for ConnectIQ operations
    private val connectIQService = ConnectIQService.getInstance()
    
    // Worker start time for runtime tracking
    private val startTime = System.currentTimeMillis()
    
    // Thread-safe response handling - no polling, event-driven
    private val responseReceived = AtomicBoolean(false)
    private val finalResponse = AtomicReference<String>()
    private val responseDevice = AtomicReference<String>()
    private val responseLatch = CountDownLatch(1)
    
    // AlarmManager components
    private var timeoutPendingIntent: PendingIntent? = null
    private var timeoutReceiver: BroadcastReceiver? = null

    /**
     * Formats current timestamp for logging.
     * @return Formatted timestamp string (HH:mm:ss.SSS)
     */
    private fun ts(): String = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
    
    /**
     * Logs informational message with category and timestamp.
     */
    private fun logInfo(category: String, message: String) {
        val logMsg = "[${ts()}] ℹ️ [WORKER.$category] $message"
        connectIQService.log(logMsg)
        android.util.Log.i(TAG, logMsg)
    }
    
    /**
     * Logs success message with category and timestamp.
     */
    private fun logSuccess(category: String, message: String) {
        val logMsg = "[${ts()}] ✅ [WORKER.$category] $message" 
        connectIQService.log(logMsg)
        android.util.Log.i(TAG, logMsg)
    }
    
    /**
     * Logs error message with category and timestamp.
     */
    private fun logError(category: String, message: String) {
        val logMsg = "[${ts()}] ❌ [WORKER.$category] $message"
        connectIQService.log(logMsg)
        android.util.Log.e(TAG, logMsg)
    }
    
    /**
     * Logs warning message with category and timestamp.
     */
    private fun logWarning(category: String, message: String) {
        val logMsg = "[${ts()}] ⚠️ [WORKER.$category] $message"
        connectIQService.log(logMsg)
        android.util.Log.w(TAG, logMsg)
    }

    /**
     * Main worker execution method implementing the complete workflow.
     * 
     * This method orchestrates all stages of the headless operation:
     * - ConnectIQ initialization
     * - Device discovery
     * - Message sending
     * - Response waiting with timeout
     * - Final termination
     * 
     * @return Result.success() in all cases (app terminates via System.exit)
     */
    override fun doWork(): Result {
        val ctx = applicationContext
        
        try {
            logInfo("STARTUP", "═══════════════════════════════════════")
            logInfo("STARTUP", "HEADLESS WORKER STARTED")
            logInfo("STARTUP", "5-Minute AlarmManager Timeout Configured")
            logInfo("STARTUP", "App will terminate via System.exit(0) after completion")
            logInfo("STARTUP", "═══════════════════════════════════════")
            
            // STAGE 1: Initialize ConnectIQ
            if (!initializeConnectIQ(ctx)) {
                return Result.success() // Error reported, app will terminate
            }
            
            // STAGE 2: Discover devices
            val devices = discoverDevices(ctx)
            if (devices == null) {
                return Result.success() // Error reported, app will terminate
            }
            
            if (devices.isEmpty()) {
                reportNoDevicesAndTerminateApp(ctx)
                return Result.success() // No devices, app will terminate
            }
            
            reportDevicesFound(ctx, devices)
            
            // STAGE 3: Send message to CIQ app
            if (!sendMessageToCIQApp(ctx, devices)) {
                return Result.success() // Send failed, app will terminate
            }
            
            // STAGE 4: Wait for response with 5-minute AlarmManager timeout
            val responseResult = waitForResponseWithAlarmManagerTimeout(ctx)
            
            // STAGE 5: Report final result and terminate entire app
            reportFinalResultAndTerminateApp(ctx, responseResult, devices)
            
            logSuccess("COMPLETION", "Worker completed - app terminating")
            return Result.success()
            
        } catch (e: Exception) {
            logError("EXCEPTION", "Worker failed with exception: ${e.message}\n${e.stackTraceToString()}")
            reportErrorAndTerminateApp(ctx, "Worker exception: ${e.message}")
            return Result.failure()
        }
    }
    
    /**
     * Initializes ConnectIQ SDK for worker operations.
     * 
     * This method checks permissions and initializes the SDK synchronously
     * for use in the background worker context.
     * 
     * @param ctx Application context
     * @return true if initialization successful, false otherwise (error reported)
     */
    private fun initializeConnectIQ(ctx: Context): Boolean {
        logInfo("INIT", "Initializing ConnectIQ SDK for headless operation...")
        
        // Check permissions first
        if (!connectIQService.hasRequiredPermissions(ctx)) {
            logError("INIT", "Missing required Bluetooth/Location permissions")
            reportErrorAndTerminateApp(ctx, "Missing required Bluetooth/Location permissions")
            return false
        }
        
        try {
            // Initialize ConnectIQ synchronously for worker
            val initResult = connectIQService.initializeForWorker(ctx)
            if (!initResult) {
                logError("INIT", "ConnectIQ SDK initialization failed")
                reportErrorAndTerminateApp(ctx, "ConnectIQ SDK initialization failed")
                return false
            }
            
            logSuccess("INIT", "ConnectIQ SDK initialized successfully")
            return true
            
        } catch (e: Exception) {
            logError("INIT", "ConnectIQ initialization exception: ${e.message}")
            reportErrorAndTerminateApp(ctx, "ConnectIQ initialization error: ${e.message}")
            return false
        }
    }
    
    /**
     * Discovers connected ConnectIQ devices.
     * 
     * Scans for real connected devices (excludes simulators).
     * 
     * @param ctx Application context
     * @return List of connected devices, or null if discovery failed (error reported)
     */
    private fun discoverDevices(ctx: Context): List<com.garmin.android.connectiq.IQDevice>? {
        logInfo("DISCOVERY", "Starting device discovery...")
        
        try {
            val devices = connectIQService.getConnectedRealDevices()
            logSuccess("DISCOVERY", "Device discovery completed - found ${devices.size} device(s)")
            
            if (devices.isNotEmpty()) {
                devices.forEach { device ->
                    logInfo("DISCOVERY", "  • ${device.friendlyName} (ID: ${device.deviceIdentifier})")
                }
            }
            
            return devices
            
        } catch (e: Exception) {
            logError("DISCOVERY", "Device discovery failed: ${e.message}")
            reportErrorAndTerminateApp(ctx, "Device discovery error: ${e.message}")
            return null
        }
    }
    
    /**
     * Reports no devices found and terminates the app.
     * 
     * Sends intent with empty device list and terminates via System.exit(0).
     * 
     * @param ctx Application context
     */
    private fun reportNoDevicesAndTerminateApp(ctx: Context) {
        logWarning("NO_DEVICES", "No devices found - reporting and terminating app")
        
        val payload = JSONObject().apply {
            put("stage", ActivityStatusCheckReceiver.STAGE_NO_DEVICES)
            put("timestamp", ts())
            put("device_count", 0)
            put("devices", JSONArray())
            put("message", "No connected ConnectIQ devices found")
            put("terminated", true)
            put("app_will_exit", true)
            put("headless_mode", true)
        }
        
        sendResponseAndTerminateApp(ctx, ActivityStatusCheckReceiver.STAGE_NO_DEVICES, false, payload.toString())
    }
    
    /**
     * Reports found devices (non-terminating stage).
     * 
     * Sends intent with device list to inform Tasker of available devices.
     * Processing continues to message sending stage.
     * 
     * @param ctx Application context
     * @param devices List of connected devices
     */
    private fun reportDevicesFound(ctx: Context, devices: List<com.garmin.android.connectiq.IQDevice>) {
        logSuccess("DEVICES", "Reporting ${devices.size} found device(s)")
        
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
            put("message", "Found ${devices.size} connected device(s)")
            put("terminated", false) // Continue to next stage
            put("app_will_exit", false)
            put("headless_mode", true)
        }
        
        sendResponse(ctx, ActivityStatusCheckReceiver.STAGE_DEVICES_FOUND, true, payload.toString(), false)
        logInfo("DEVICES", "Device list reported - continuing to message send stage")
    }
    
    /**
     * Sends status query message to CIQ app and sets up response callback.
     * 
     * Registers callback for immediate response processing (no internal timeout).
     * The 5-minute timeout is handled separately via AlarmManager.
     * 
     * @param ctx Application context
     * @param devices List of connected devices (uses first device)
     * @return true if message sent successfully, false otherwise (error reported and app terminates)
     */
    private fun sendMessageToCIQApp(ctx: Context, devices: List<com.garmin.android.connectiq.IQDevice>): Boolean {
        logInfo("MESSAGE", "Attempting to send status query to CIQ app...")
        
        val targetDevice = devices.firstOrNull()
        if (targetDevice == null) {
            logError("MESSAGE", "No target device available")
            reportMessageFailedAndTerminateApp(ctx, "No target device available for messaging")
            return false
        }
        
        logInfo("MESSAGE", "Target device: ${targetDevice.friendlyName} (${targetDevice.deviceIdentifier})")
        
        // Register response callback BEFORE sending message
        // This ensures we capture the response immediately when it arrives
        connectIQService.setResponseCallback { response, deviceName, timestamp ->
            if (!responseReceived.getAndSet(true)) {
                logSuccess("RESPONSE_CALLBACK", "CIQ response received: '$response' from $deviceName at $timestamp")
                finalResponse.set(response)
                responseDevice.set(deviceName)
                
                // Release the waiting thread immediately
                responseLatch.countDown()
                
                // Cancel the timeout alarm since we got a response
                cancelTimeoutAlarm(ctx)
            } else {
                logWarning("RESPONSE_CALLBACK", "Duplicate response ignored (already received)")
            }
        }
        
        try {
            // Send query message to CIQ app
            val queryResult = connectIQService.queryActivityStatus(
                context = ctx,
                selected = targetDevice,
                showUiIfInitNeeded = false // Headless mode - no UI
            )
            
            if (queryResult.success) {
                logSuccess("MESSAGE", "Message sent successfully to CIQ app")
                reportMessageSentSuccessfully(ctx, targetDevice, queryResult)
                return true
            } else {
                logError("MESSAGE", "Failed to send message: ${queryResult.payload}")
                reportMessageFailedAndTerminateApp(ctx, "Send failed: ${queryResult.payload}")
                return false
            }
            
        } catch (e: Exception) {
            logError("MESSAGE", "Exception sending message: ${e.message}\n${e.stackTraceToString()}")
            reportMessageFailedAndTerminateApp(ctx, "Send exception: ${e.message}")
            return false
        }
    }
    
    /**
     * Reports successful message send (non-terminating stage).
     * 
     * Informs Tasker that the query was sent successfully.
     * Processing continues to response waiting stage.
     * 
     * @param ctx Application context
     * @param device Target device
     * @param result Query result details
     */
    private fun reportMessageSentSuccessfully(
        ctx: Context,
        device: com.garmin.android.connectiq.IQDevice,
        result: ConnectIQService.QueryResult
    ) {
        logSuccess("MESSAGE_SENT", "Message sent to ${device.friendlyName} - awaiting response")
        
        val payload = JSONObject().apply {
            put("stage", ActivityStatusCheckReceiver.STAGE_MESSAGE_SENT)
            put("timestamp", ts())
            put("target_device", device.friendlyName)
            put("target_device_id", device.deviceIdentifier.toString())
            put("message", "Message sent successfully to CIQ app - awaiting response")
            put("debug_info", result.debug)
            put("terminated", false) // Continue to response waiting
            put("app_will_exit", false)
            put("headless_mode", true)
        }
        
        sendResponse(ctx, ActivityStatusCheckReceiver.STAGE_MESSAGE_SENT, true, payload.toString(), false)
    }
    
    /**
     * Reports message send failure and terminates app.
     * 
     * @param ctx Application context
     * @param error Error description
     */
    private fun reportMessageFailedAndTerminateApp(ctx: Context, error: String) {
        logError("MESSAGE_FAILED", "Message send failed: $error")
        
        val payload = JSONObject().apply {
            put("stage", ActivityStatusCheckReceiver.STAGE_MESSAGE_FAILED)
            put("timestamp", ts())
            put("error", error)
            put("message", "Failed to send message to CIQ app")
            put("terminated", true)
            put("app_will_exit", true)
            put("headless_mode", true)
        }
        
        sendResponseAndTerminateApp(ctx, ActivityStatusCheckReceiver.STAGE_MESSAGE_FAILED, false, payload.toString())
    }
    
    /**
     * Data class for response result containing type and details.
     */
    private data class ResponseResult(
        val success: Boolean,
        val type: String, // "response", "timeout", "error"
        val response: String = "",
        val deviceName: String = "",
        val error: String = ""
    )
    
    /**
     * Waits for CIQ response with 5-minute AlarmManager timeout.
     * 
     * This is the most battery-efficient method for waiting:
     * - CountDownLatch blocks thread efficiently (no busy waiting)
     * - AlarmManager provides precise timeout (most reliable)
     * - No polling or repeated checks
     * - Immediate wake-up on response or timeout
     * 
     * Note: The 5-minute timeout applies ONLY to waiting for CIQ app response.
     * Initialization and message sending have separate shorter timeouts.
     * 
     * @param ctx Application context
     * @return ResponseResult indicating success, timeout, or error
     */
    private fun waitForResponseWithAlarmManagerTimeout(ctx: Context): ResponseResult {
        logInfo("WAIT", "═══════════════════════════════════════")
        logInfo("WAIT", "Starting 5-minute wait for CIQ response")
        logInfo("WAIT", "Method: AlarmManager (most reliable & battery-efficient)")
        logInfo("WAIT", "Timeout: exactly ${RESPONSE_TIMEOUT_MS / 1000} seconds")
        logInfo("WAIT", "═══════════════════════════════════════")
        
        // Set up AlarmManager for precise 5-minute timeout
        setupAlarmManagerTimeout(ctx)
        
        val waitStartTime = System.currentTimeMillis()
        
        try {
            // Battery-efficient wait using CountDownLatch
            // Thread blocks here with zero CPU usage until:
            // 1. Response received (responseLatch.countDown() called in callback)
            // 2. Timeout alarm fires (responseLatch.countDown() called by alarm receiver)
            // 3. Wait timeout expires (backup safety mechanism)
            val awaitResult = responseLatch.await(
                RESPONSE_TIMEOUT_MS + 5000, // Slight buffer beyond alarm time
                TimeUnit.MILLISECONDS
            )
            
            val waitDuration = System.currentTimeMillis() - waitStartTime
            logInfo("WAIT", "Wait completed after ${waitDuration}ms (${waitDuration / 1000}s)")
            
            // Check what caused the wait to end
            if (responseReceived.get()) {
                // Response was received from CIQ app
                val response = finalResponse.get() ?: ""
                val device = responseDevice.get() ?: "Unknown"
                logSuccess("WAIT", "CIQ response received from $device: '$response'")
                cancelTimeoutAlarm(ctx) // Ensure alarm is cancelled
                return ResponseResult(true, "response", response, device)
            } else if (awaitResult) {
                // Latch was released but no response flag - likely timeout alarm fired
                logWarning("WAIT", "5-minute AlarmManager timeout reached - no response received")
                return ResponseResult(false, "timeout", error = "5-minute timeout - no response from CIQ app")
            } else {
                // Backup timeout expired (should not happen if alarm works correctly)
                logWarning("WAIT", "Fallback timeout reached - alarm may have failed")
                cancelTimeoutAlarm(ctx)
                return ResponseResult(false, "timeout", error = "Backup timeout - no response within ${RESPONSE_TIMEOUT_MS / 1000} seconds")
            }
            
        } catch (e: InterruptedException) {
            logError("WAIT", "Wait interrupted: ${e.message}")
            cancelTimeoutAlarm(ctx)
            return ResponseResult(false, "error", error = "Wait interrupted: ${e.message}")
        } catch (e: Exception) {
            logError("WAIT", "Wait failed with exception: ${e.message}\n${e.stackTraceToString()}")
            cancelTimeoutAlarm(ctx)
            return ResponseResult(false, "error", error = "Wait exception: ${e.message}")
        } finally {
            // Ensure alarm and receiver are cleaned up
            cleanupTimeout(ctx)
        }
    }
    
    /**
     * Sets up AlarmManager for precise 5-minute timeout.
     * 
     * AlarmManager is the most reliable and battery-efficient method for
     * timing on Android. It uses hardware timers and wakes the device
     * precisely when needed.
     * 
     * @param ctx Application context
     */
    private fun setupAlarmManagerTimeout(ctx: Context) {
        logInfo("TIMEOUT_SETUP", "Configuring AlarmManager for 5-minute timeout...")
        
        try {
            val alarmManager = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            
            // Create timeout receiver
            timeoutReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == TIMEOUT_ACTION) {
                        logWarning("TIMEOUT_ALARM", "⏰ AlarmManager timeout fired - 5 minutes elapsed with no response")
                        
                        // Mark as "received" (but with timeout flag) and release the latch
                        if (!responseReceived.getAndSet(true)) {
                            responseLatch.countDown()
                        }
                    }
                }
            }
            
            // Register receiver for timeout action
            val filter = IntentFilter(TIMEOUT_ACTION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ctx.registerReceiver(timeoutReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                ctx.registerReceiver(timeoutReceiver, filter)
            }
            
            // Create pending intent for alarm
            val timeoutIntent = Intent(TIMEOUT_ACTION).apply {
                setPackage(ctx.packageName)
            }
            
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            
            timeoutPendingIntent = PendingIntent.getBroadcast(
                ctx,
                12345, // Unique request code
                timeoutIntent,
                flags
            )
            
            // Set exact alarm for 5 minutes from now
            // ELAPSED_REALTIME_WAKEUP ensures the alarm fires even if device is asleep
            val triggerTime = SystemClock.elapsedRealtime() + RESPONSE_TIMEOUT_MS
            alarmManager.setExact(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerTime,
                timeoutPendingIntent!!
            )
            
            logSuccess("TIMEOUT_SETUP", "AlarmManager configured: will fire in exactly ${RESPONSE_TIMEOUT_MS / 1000} seconds")
            logInfo("TIMEOUT_SETUP", "Trigger time: ${Date(System.currentTimeMillis() + RESPONSE_TIMEOUT_MS)}")
            
        } catch (e: Exception) {
            logError("TIMEOUT_SETUP", "Failed to setup AlarmManager timeout: ${e.message}\n${e.stackTraceToString()}")
            // Continue anyway - we have backup timeout in await()
        }
    }
    
    /**
     * Cancels the AlarmManager timeout.
     * 
     * Called when response is received before timeout expires.
     * 
     * @param ctx Application context
     */
    private fun cancelTimeoutAlarm(ctx: Context) {
        try {
            timeoutPendingIntent?.let { pendingIntent ->
                val alarmManager = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                alarmManager.cancel(pendingIntent)
                logInfo("TIMEOUT_CANCEL", "AlarmManager timeout cancelled (response received)")
            }
        } catch (e: Exception) {
            logWarning("TIMEOUT_CANCEL", "Failed to cancel timeout alarm: ${e.message}")
            // Non-critical - alarm will fire but be ignored
        }
    }
    
    /**
     * Cleans up timeout resources (alarm and receiver).
     * 
     * Should be called in finally block to ensure cleanup.
     * 
     * @param ctx Application context
     */
    private fun cleanupTimeout(ctx: Context) {
        try {
            // Cancel alarm if still pending
            cancelTimeoutAlarm(ctx)
            
            // Unregister receiver
            timeoutReceiver?.let { receiver ->
                try {
                    ctx.unregisterReceiver(receiver)
                    logInfo("TIMEOUT_CLEANUP", "Timeout receiver unregistered")
                } catch (e: IllegalArgumentException) {
                    // Receiver was never registered or already unregistered
                    logWarning("TIMEOUT_CLEANUP", "Receiver already unregistered")
                }
                timeoutReceiver = null
            }
            
            timeoutPendingIntent = null
            
        } catch (e: Exception) {
            logWarning("TIMEOUT_CLEANUP", "Cleanup warning: ${e.message}")
            // Non-critical - resources will be cleaned up on app termination anyway
        }
    }
    
    /**
     * Reports final result and terminates app based on response type.
     * 
     * Delegates to specific reporting methods based on result type.
     * 
     * @param ctx Application context
     * @param result Response result from waiting
     * @param devices List of devices for inclusion in final report
     */
    private fun reportFinalResultAndTerminateApp(
        ctx: Context,
        result: ResponseResult,
        devices: List<com.garmin.android.connectiq.IQDevice>
    ) {
        when (result.type) {
            "response" -> {
                logSuccess("FINAL", "CIQ app responded successfully - reporting and terminating")
                reportResponseReceivedAndTerminateApp(ctx, result, devices)
            }
            "timeout" -> {
                logWarning("FINAL", "5-minute timeout reached - reporting and terminating")
                reportTimeoutAndTerminateApp(ctx, devices)
            }
            "error" -> {
                logError("FINAL", "Wait error occurred - reporting and terminating: ${result.error}")
                reportErrorAndTerminateApp(ctx, "Response wait error: ${result.error}")
            }
        }
    }
    
    /**
     * Reports successful CIQ response and terminates app.
     * 
     * This is the successful completion path.
     * 
     * @param ctx Application context
     * @param result Response result with CIQ data
     * @param devices List of devices
     */
    private fun reportResponseReceivedAndTerminateApp(
        ctx: Context,
        result: ResponseResult,
        devices: List<com.garmin.android.connectiq.IQDevice>
    ) {
        logSuccess("RESPONSE_FINAL", "═══════════════════════════════════════")
        logSuccess("RESPONSE_FINAL", "CIQ APP RESPONSE RECEIVED SUCCESSFULLY")
        logSuccess("RESPONSE_FINAL", "═══════════════════════════════════════")
        
        val devicesArray = JSONArray()
        devices.forEach { device ->
            devicesArray.put(JSONObject().apply {
                put("name", device.friendlyName ?: "Unknown")
                put("id", device.deviceIdentifier.toString())
                put("status", device.status.toString())
            })
        }
        
        val payload = JSONObject().apply {
            put("stage", ActivityStatusCheckReceiver.STAGE_RESPONSE_RECEIVED)
            put("timestamp", ts())
            put("device_count", devices.size)
            put("devices", devicesArray)
            put("responding_device", result.deviceName)
            put("ciq_response", result.response)
            put("message", "CIQ app response received successfully")
            put("terminated", true)
            put("app_will_exit", true)
            put("headless_mode", true)
            
            // Parse CIQ response components if possible
            // Expected format: "RUNNING: YES|12:34:56|5"
            val parts = result.response.split("|")
            if (parts.size >= 3) {
                put("activity_status", parts[0])
                put("activity_time", parts[1])
                put("message_count", parts[2])
            } else {
                put("activity_status", result.response)
            }
            
            put("total_runtime_ms", System.currentTimeMillis() - startTime)
            put("total_runtime_seconds", (System.currentTimeMillis() - startTime) / 1000)
        }
        
        sendResponseAndTerminateApp(ctx, ActivityStatusCheckReceiver.STAGE_RESPONSE_RECEIVED, true, payload.toString())
    }
    
    /**
     * Reports 5-minute timeout and terminates app.
     * 
     * @param ctx Application context
     * @param devices List of devices
     */
    private fun reportTimeoutAndTerminateApp(
        ctx: Context,
        devices: List<com.garmin.android.connectiq.IQDevice>
    ) {
        logWarning("TIMEOUT_FINAL", "═══════════════════════════════════════")
        logWarning("TIMEOUT_FINAL", "5-MINUTE TIMEOUT REACHED - NO RESPONSE")
        logWarning("TIMEOUT_FINAL", "═══════════════════════════════════════")
        
        val devicesArray = JSONArray()
        devices.forEach { device ->
            devicesArray.put(JSONObject().apply {
                put("name", device.friendlyName ?: "Unknown")
                put("id", device.deviceIdentifier.toString())
                put("status", device.status.toString())
            })
        }
        
        val payload = JSONObject().apply {
            put("stage", ActivityStatusCheckReceiver.STAGE_TIMEOUT)
            put("timestamp", ts())
            put("device_count", devices.size)
            put("devices", devicesArray)
            put("timeout_seconds", RESPONSE_TIMEOUT_MS / 1000)
            put("timeout_method", "AlarmManager - most reliable and battery-efficient")
            put("message", "5-minute timeout waiting for CIQ app response")
            put("terminated", true)
            put("app_will_exit", true)
            put("headless_mode", true)
            put("total_runtime_ms", System.currentTimeMillis() - startTime)
            put("total_runtime_seconds", (System.currentTimeMillis() - startTime) / 1000)
        }
        
        sendResponseAndTerminateApp(ctx, ActivityStatusCheckReceiver.STAGE_TIMEOUT, false, payload.toString())
    }
    
    /**
     * Reports error and terminates app.
     * 
     * Generic error reporting for any stage.
     * 
     * @param ctx Application context
     * @param error Error description
     */
    private fun reportErrorAndTerminateApp(ctx: Context, error: String) {
        logError("ERROR_FINAL", "═══════════════════════════════════════")
        logError("ERROR_FINAL", "ERROR OCCURRED: $error")
        logError("ERROR_FINAL", "═══════════════════════════════════════")
        
        val payload = JSONObject().apply {
            put("stage", ActivityStatusCheckReceiver.STAGE_ERROR)
            put("timestamp", ts())
            put("error", error)
            put("message", "Worker error occurred")
            put("terminated", true)
            put("app_will_exit", true)
            put("headless_mode", true)
            put("total_runtime_ms", System.currentTimeMillis() - startTime)
            put("total_runtime_seconds", (System.currentTimeMillis() - startTime) / 1000)
        }
        
        sendResponseAndTerminateApp(ctx, ActivityStatusCheckReceiver.STAGE_ERROR, false, payload.toString())
    }
    
    /**
     * Sends response intent to Tasker (non-terminating).
     * 
     * Used for intermediate stages that don't terminate the app.
     * 
     * @param ctx Application context
     * @param stage Current stage identifier
     * @param success Success boolean
     * @param payload JSON payload string
     * @param terminated Whether this terminates the operation
     */
    private fun sendResponse(
        ctx: Context,
        stage: String,
        success: Boolean,
        payload: String,
        terminated: Boolean
    ) {
        val responseIntent = Intent(ActivityStatusCheckReceiver.ACTION_RESPONSE).apply {
            putExtra(ActivityStatusCheckReceiver.EXTRA_STAGE, stage)
            putExtra(ActivityStatusCheckReceiver.EXTRA_SUCCESS, success)
            putExtra(ActivityStatusCheckReceiver.EXTRA_PAYLOAD, payload)
            putExtra(ActivityStatusCheckReceiver.EXTRA_TIMESTAMP, ts())
            putExtra(ActivityStatusCheckReceiver.EXTRA_TERMINATED, terminated)
            putExtra(ActivityStatusCheckReceiver.EXTRA_HEADLESS, true)
        }
        
        logInfo("INTENT_SEND", "📤 Sending response intent to Tasker:")
        logInfo("INTENT_SEND", "   Stage: $stage")
        logInfo("INTENT_SEND", "   Success: $success")
        logInfo("INTENT_SEND", "   Terminated: $terminated")
        logInfo("INTENT_SEND", "   Payload length: ${payload.length} chars")
        
        ctx.sendBroadcast(responseIntent)
        logSuccess("INTENT_SEND", "Response broadcast sent successfully")
    }
    
    /**
     * Sends final response and terminates entire app process.
     * 
     * This method:
     * 1. Sends final response intent to Tasker
     * 2. Allows brief time for intent delivery
     * 3. Calls System.exit(0) to terminate entire app process
     * 
     * System.exit(0) is the most reliable method to terminate an Android app
     * completely, cleaning up all resources and ensuring the process is killed.
     * 
     * @param ctx Application context
     * @param stage Current stage identifier
     * @param success Success boolean
     * @param payload JSON payload string
     */
    private fun sendResponseAndTerminateApp(
        ctx: Context,
        stage: String,
        success: Boolean,
        payload: String
    ) {
        logWarning("TERMINATION", "═══════════════════════════════════════")
        logWarning("TERMINATION", "SENDING FINAL RESPONSE AND TERMINATING APP")
        logWarning("TERMINATION", "═══════════════════════════════════════")
        
        // Send final response
        sendResponse(ctx, stage, success, payload, true)
        
        // Give broadcast brief time to be sent (100ms is sufficient)
        try {
            Thread.sleep(100)
        } catch (e: InterruptedException) {
            // Ignore
        }
        
        logWarning("TERMINATION", "Calling System.exit(0) to terminate app process")
        logWarning("TERMINATION", "═══════════════════════════════════════")
        
        // Terminate entire app process
        // System.exit(0) is confirmed by Android documentation and community
        // to be the correct way to exit an app completely
        System.exit(0)
    }
}