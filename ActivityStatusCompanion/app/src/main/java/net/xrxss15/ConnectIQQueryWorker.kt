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
        Log.i(TAG, "Worker starting")
        
        return try {
            // Mark as foreground service immediately
            setForeground(createForegroundInfo())
            
            if (!initializeSDK()) {
                sendBroadcast("terminating|SDK initialization failed")
                return Result.failure()
            }
            
            delay(1500)
            
            val devices = connectIQService.getConnectedRealDevices()
            Log.i(TAG, "Found ${devices.size} device(s)")
            
            lastDeviceIds = devices.map { it.deviceIdentifier }.toSet()
            connectedDeviceNames = devices.map { it.friendlyName ?: "Unknown" }
            
            // Update notification with devices
            setForeground(createForegroundInfo())
            
            sendDeviceListMessage(devices)
            
            connectIQService.setMessageCallback { payload, deviceName, timestamp ->
                // Update notification with last message
                lastMessage = parseMessage(payload, deviceName)
                lastMessageTime = timestamp
                
                sendBroadcast("message_received|$deviceName|$payload")
            }
            
            connectIQService.setDeviceChangeCallback {
                // Device changed - will be handled in main loop
            }
            
            Log.i(TAG, "Worker ready, waiting for events")
            
            // Indefinite wait loop - checks isStopped for cancellation
            var updateCounter = 0
            while (!isStopped) {
                delay(1000)
                
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
                    lastDeviceIds = currentIds
                    connectedDeviceNames = currentDevices.map { it.friendlyName ?: "Unknown" }
                    sendDeviceListMessage(currentDevices)
                    connectIQService.registerListenersForAllDevices()
                    setForeground(createForegroundInfo())
                }
            }
            
            Log.i(TAG, "Worker stopped")
            Result.success()
            
        } catch (e: Exception) {
            Log.e(TAG, "Worker error", e)
            sendBroadcast("terminating|Worker exception: ${e.message}")
            Result.failure()
        } finally {
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
        sendBroadcast(parts.joinToString("|"))
    }
    
    private fun sendBroadcast(message: String) {
        try {
            // 1. Explicit broadcast for MainActivity
            val explicitIntent = Intent(ActivityStatusCheckReceiver.ACTION_MESSAGE).apply {
                putExtra(ActivityStatusCheckReceiver.EXTRA_MESSAGE, message)
                setPackage(applicationContext.packageName)
            }
            applicationContext.sendBroadcast(explicitIntent)
            
            // 2. Implicit broadcast for Tasker
            val implicitIntent = Intent(ActivityStatusCheckReceiver.ACTION_MESSAGE).apply {
                putExtra("message", message)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            applicationContext.sendBroadcast(implicitIntent)
            
        } catch (e: Exception) {
            Log.e(TAG, "Broadcast failed", e)
        }
    }
}