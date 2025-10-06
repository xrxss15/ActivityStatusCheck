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
    
    private var connectedDeviceNames = listOf<String>()
    private var lastMessage: String? = null
    private var lastMessageTime: Long = 0

    override suspend fun doWork(): Result {
        Log.i(TAG, "Worker starting")
        
        return try {
            // Try to become foreground
            try {
                setForeground(createForegroundInfo())
                Log.i(TAG, "✓ Running as foreground service")
            } catch (e: Exception) {
                Log.w(TAG, "Cannot run as foreground (expected from background), continuing anyway", e)
                // Continue without foreground - will work but may be killed on low memory
            }
            
            if (!initializeSDK()) {
                sendBroadcast("terminating|SDK initialization failed")
                return Result.failure()
            }
            
            delay(1500)
            
            val devices = connectIQService.getConnectedRealDevices()
            Log.i(TAG, "Found ${devices.size} device(s)")
            
            lastDeviceIds = devices.map { it.deviceIdentifier }.toSet()
            connectedDeviceNames = devices.map { it.friendlyName ?: "Unknown" }
            
            try {
                setForeground(createForegroundInfo())
            } catch (ignored: Exception) {}
            
            sendDeviceListMessage(devices)
            
            connectIQService.setMessageCallback { payload, deviceName, timestamp ->
                lastMessage = parseMessage(payload, deviceName)
                lastMessageTime = timestamp
                sendBroadcast("message_received|$deviceName|$payload")
            }
            
            connectIQService.setDeviceChangeCallback {
                // Handled in main loop
            }
            
            Log.i(TAG, "Worker ready, listening for messages")
            
            var updateCounter = 0
            while (!isStopped) {
                delay(1000)
                
                updateCounter++
                if (updateCounter >= 5 && lastMessage != null) {
                    updateCounter = 0
                    try {
                        setForeground(createForegroundInfo())
                    } catch (ignored: Exception) {}
                }
                
                val currentDevices = connectIQService.getConnectedRealDevices()
                val currentIds = currentDevices.map { it.deviceIdentifier }.toSet()
                
                if (currentIds != lastDeviceIds) {
                    lastDeviceIds = currentIds
                    connectedDeviceNames = currentDevices.map { it.friendlyName ?: "Unknown" }
                    sendDeviceListMessage(currentDevices)
                    connectIQService.registerListenersForAllDevices()
                    try {
                        setForeground(createForegroundInfo())
                    } catch (ignored: Exception) {}
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
        
        val openIntent = Intent(applicationContext, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            applicationContext, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val stopIntent = Intent(ActivityStatusCheckReceiver.ACTION_STOP).apply {
            setPackage(applicationContext.packageName)
        }
        val stopPendingIntent = PendingIntent.getBroadcast(
            applicationContext, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val contentText = buildContentText()
        val bigText = buildBigText()
        
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Garmin Activity Listener")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setColor(0xFF4CAF50.toInt())
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openPendingIntent)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .addAction(android.R.drawable.ic_menu_view, "Open", openPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPendingIntent)
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
        
        if (connectedDeviceNames.isEmpty()) {
            sb.append("Devices: None connected\n")
        } else {
            sb.append("Devices:\n")
            connectedDeviceNames.forEach { name ->
                sb.append("  • $name\n")
            }
        }
        
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
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Listening for Garmin watch activity events"
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
                setShowBadge(false)
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
            val explicitIntent = Intent(ActivityStatusCheckReceiver.ACTION_MESSAGE).apply {
                putExtra(ActivityStatusCheckReceiver.EXTRA_MESSAGE, message)
                setPackage(applicationContext.packageName)
            }
            applicationContext.sendBroadcast(explicitIntent)
            
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