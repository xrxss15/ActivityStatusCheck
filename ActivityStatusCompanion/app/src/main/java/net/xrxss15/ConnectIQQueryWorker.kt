package net.xrxss15

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.garmin.android.connectiq.IQDevice
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ConnectIQQueryWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "GarminActivityListener.Worker"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "garmin_listener_channel"
    }

    private val connectIQService = ConnectIQService.getInstance()
    private var lastDeviceIds = emptySet<Long>()
    
    // State for notification
    private var connectedDeviceNames = listOf<String>()
    private var lastMessage: String? = null
    private var lastMessageTime: Long = 0

    override suspend fun doWork(): Result {
        Log.i(TAG, "========================================")
        Log.i(TAG, "Worker starting - doWork() called")
        Log.i(TAG, "========================================")
        
        return try {
            // Mark as foreground service immediately
            Log.i(TAG, "Calling setForeground()...")
            try {
                setForeground(createForegroundInfo())
                Log.i(TAG, "✓ setForeground() succeeded - notification should be visible")
            } catch (e: Exception) {
                Log.e(TAG, "✗ setForeground() FAILED", e)
                return Result.failure()
            }
            
            Log.i(TAG, "Initializing SDK...")
            if (!initializeSDK()) {
                Log.e(TAG, "✗ SDK initialization failed")
                sendBroadcast("terminating|SDK initialization failed")
                return Result.failure()
            }
            Log.i(TAG, "✓ SDK initialized successfully")
            
            delay(1500)
            
            val devices = connectIQService.getConnectedRealDevices()
            Log.i(TAG, "Found ${devices.size} device(s)")
            devices.forEachIndexed { index, device ->
                Log.i(TAG, "  Device $index: ${device.friendlyName} (ID: ${device.deviceIdentifier})")
            }
            
            lastDeviceIds = devices.map { it.deviceIdentifier }.toSet()
            connectedDeviceNames = devices.map { it.friendlyName ?: "Unknown" }
            
            // Update notification with devices
            Log.i(TAG, "Updating notification with device list...")
            setForeground(createForegroundInfo())
            
            sendDeviceListMessage(devices)
            
            Log.i(TAG, "Setting up message callback...")
            connectIQService.setMessageCallback { payload, deviceName, timestamp ->
                Log.i(TAG, "========================================")
                Log.i(TAG, "MESSAGE RECEIVED!")
                Log.i(TAG, "Device: $deviceName")
                Log.i(TAG, "Payload: $payload")
                Log.i(TAG, "Timestamp: $timestamp")
                Log.i(TAG, "========================================")
                
                // Update notification with last message
                lastMessage = parseMessage(payload, deviceName)
                lastMessageTime = timestamp
                
                Log.i(TAG, "Parsed message: $lastMessage")
                Log.i(TAG, "Broadcasting message...")
                sendBroadcast("message_received|$deviceName|$payload")
                Log.i(TAG, "Broadcast sent")
            }
            
            connectIQService.setDeviceChangeCallback {
                Log.i(TAG, "Device change detected")
            }
            
            Log.i(TAG, "Worker ready - entering main loop")
            Log.i(TAG, "Waiting for messages from watch...")
            
            // Indefinite wait loop
            var loopCounter = 0
            var updateCounter = 0
            while (!isStopped) {
                delay(1000)
                loopCounter++
                
                // Log every 30 seconds to show we're alive
                if (loopCounter % 30 == 0) {
                    Log.i(TAG, "Worker still running (${loopCounter}s elapsed)")
                }
                
                // Update notification every 5 seconds if we have a message
                updateCounter++
                if (updateCounter >= 5 && lastMessage != null) {
                    updateCounter = 0
                    try {
                        setForeground(createForegroundInfo())
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to update notification", e)
                    }
                }
                
                // Check for device changes
                val currentDevices = connectIQService.getConnectedRealDevices()
                val currentIds = currentDevices.map { it.deviceIdentifier }.toSet()
                
                if (currentIds != lastDeviceIds) {
                    Log.i(TAG, "Device list changed!")
                    Log.i(TAG, "Old: $lastDeviceIds")
                    Log.i(TAG, "New: $currentIds")
                    
                    lastDeviceIds = currentIds
                    connectedDeviceNames = currentDevices.map { it.friendlyName ?: "Unknown" }
                    sendDeviceListMessage(currentDevices)
                    connectIQService.registerListenersForAllDevices()
                    setForeground(createForegroundInfo())
                }
            }
            
            Log.i(TAG, "Worker stopped (isStopped = true)")
            Result.success()
            
        } catch (e: Exception) {
            Log.e(TAG, "Worker crashed with exception", e)
            sendBroadcast("terminating|Worker exception: ${e.message}")
            Result.failure()
        } finally {
            Log.i(TAG, "Worker cleanup starting...")
            connectIQService.setMessageCallback(null)
            connectIQService.setDeviceChangeCallback(null)
            Log.i(TAG, "Worker cleanup complete")
        }
    }
    
    private fun parseMessage(payload: String, deviceName: String): String {
        val parts = payload.split("|")
        if (parts.size < 5) return "Message from $deviceName"
        
        val event = when (parts[0]) {
            "STARTED", "ACTIVITY_STARTED" -> "Started"
            "STOPPED", "ACTIVITY_STOPPED" -> "Stopped"
            else -> parts[0]
        }
        val activity = parts[3]
        
        return "$event $activity"
    }
    
    private fun formatTime(timestamp: Long): String {
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
    }
    
    private fun createForegroundInfo(): ForegroundInfo {
        Log.d(TAG, "createForegroundInfo() called")
        createNotificationChannel()
        
        // Intent to open MainActivity
        val openIntent = Intent(applicationContext, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Intent to stop the worker
        val stopIntent = Intent(ActivityStatusCheckReceiver.ACTION_STOP).apply {
            setPackage(applicationContext.packageName)
        }
        val stopPendingIntent = PendingIntent.getBroadcast(
            applicationContext,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Build notification content
        val title = "Garmin Activity Listener"
        val contentText = buildContentText()
        val bigText = buildBigText()
        
        Log.d(TAG, "Notification content: $contentText")
        
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setColor(0xFF4CAF50.toInt())
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openPendingIntent)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .addAction(
                android.R.drawable.ic_menu_view,
                "Open",
                openPendingIntent
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                stopPendingIntent
            )
            .build()
        
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }
    
    private fun buildContentText(): String {
        return when {
            connectedDeviceNames.isEmpty() -> "Waiting for devices..."
            lastMessage != null -> lastMessage!!
            else -> "${connectedDeviceNames.size} device(s) connected"
        }
    }
    
    private fun buildBigText(): String {
        val sb = StringBuilder()
        
        // Devices section
        if (connectedDeviceNames.isEmpty()) {
            sb.append("Devices: None connected\n")
        } else {
            sb.append("Devices:\n")
            connectedDeviceNames.forEach { name ->
                sb.append("  • $name\n")
            }
        }
        
        // Last message section
        if (lastMessage != null && lastMessageTime > 0) {
            sb.append("\nLast Message:\n")
            sb.append("  $lastMessage\n")
            sb.append("  ${formatTime(lastMessageTime)}")
        } else {
            sb.append("\nNo messages received yet")
        }
        
        return sb.toString()
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Garmin Listener Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Listening for Garmin watch activity events"
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
                setShowBadge(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            
            val notificationManager =
                applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            
            Log.d(TAG, "Notification channel created: $CHANNEL_ID")
        }
    }
    
    private fun initializeSDK(): Boolean {
        if (!connectIQService.hasRequiredPermissions(applicationContext)) {
            Log.e(TAG, "Missing permissions")
            return false
        }
        
        return connectIQService.initializeForWorker(applicationContext)
    }
    
    private fun sendDeviceListMessage(devices: List<IQDevice>) {
        val parts = mutableListOf("devices", devices.size.toString())
        devices.forEach { device ->
            parts.add(device.friendlyName ?: "Unknown")
        }
        val message = parts.joinToString("|")
        Log.i(TAG, "Sending device list: $message")
        sendBroadcast(message)
    }
    
    private fun sendBroadcast(message: String) {
        try {
            Log.d(TAG, "sendBroadcast() called with: $message")
            
            // 1. Explicit broadcast for MainActivity
            val explicitIntent = Intent(ActivityStatusCheckReceiver.ACTION_MESSAGE).apply {
                putExtra(ActivityStatusCheckReceiver.EXTRA_MESSAGE, message)
                setPackage(applicationContext.packageName)
            }
            applicationContext.sendBroadcast(explicitIntent)
            Log.d(TAG, "Explicit broadcast sent for MainActivity")
            
            // 2. Implicit broadcast for Tasker
            val implicitIntent = Intent(ActivityStatusCheckReceiver.ACTION_MESSAGE).apply {
                putExtra("message", message)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            applicationContext.sendBroadcast(implicitIntent)
            Log.d(TAG, "Implicit broadcast sent for Tasker")
            
        } catch (e: Exception) {
            Log.e(TAG, "Broadcast failed", e)
        }
    }
}