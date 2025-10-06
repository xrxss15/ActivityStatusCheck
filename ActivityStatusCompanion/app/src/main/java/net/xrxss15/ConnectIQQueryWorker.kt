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
import kotlinx.coroutines.CancellationException
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
        Log.i(TAG, "========================================")
        Log.i(TAG, "Worker starting - doWork() called")
        Log.i(TAG, "========================================")
        
        return try {
            // Mark as foreground service immediately
            Log.i(TAG, "Calling setForeground()...")
            setForeground(createForegroundInfo())
            Log.i(TAG, "✓ Running as foreground service")
            
            // Initialize SDK - this MUST be done on the coroutine thread
            // The SDK will use Handler internally for callbacks
            Log.i(TAG, "Initializing SDK...")
            if (!initializeSDK()) {
                Log.e(TAG, "SDK initialization failed")
                sendBroadcast("terminating|SDK initialization failed")
                return Result.failure()
            }
            Log.i(TAG, "✓ SDK initialized successfully")
            
            // Wait a bit for SDK to settle
            delay(1500)
            
            val devices = connectIQService.getConnectedRealDevices()
            Log.i(TAG, "Found ${devices.size} device(s)")
            
            lastDeviceIds = devices.map { it.deviceIdentifier }.toSet()
            connectedDeviceNames = devices.map { it.friendlyName ?: "Unknown" }
            
            // Update notification with devices
            setForeground(createForegroundInfo())
            sendDeviceListMessage(devices)
            
            connectIQService.setMessageCallback { payload, deviceName, timestamp ->
                Log.i(TAG, "MESSAGE RECEIVED from $deviceName")
                lastMessage = parseMessage(payload, deviceName)
                lastMessageTime = timestamp
                sendBroadcast("message_received|$deviceName|$payload")
            }
            
            connectIQService.setDeviceChangeCallback {
                Log.i(TAG, "Device change detected")
            }
            
            Log.i(TAG, "Worker ready, listening for messages")
            
            // Indefinite loop for long-running worker
            var loopCounter = 0
            var updateCounter = 0
            while (!isStopped) {
                delay(1000)
                loopCounter++
                
                // Log every 30 seconds
                if (loopCounter % 30 == 0) {
                    Log.i(TAG, "Worker still running (${loopCounter}s elapsed)")
                }
                
                // Update notification every 5 seconds
                updateCounter++
                if (updateCounter >= 5) {
                    updateCounter = 0
                    setForeground(createForegroundInfo())
                }
                
                // Check for device changes
                val currentDevices = connectIQService.getConnectedRealDevices()
                val currentIds = currentDevices.map { it.deviceIdentifier }.toSet()
                
                if (currentIds != lastDeviceIds) {
                    Log.i(TAG, "Device list changed")
                    lastDeviceIds = currentIds
                    connectedDeviceNames = currentDevices.map { it.friendlyName ?: "Unknown" }
                    sendDeviceListMessage(currentDevices)
                    connectIQService.registerListenersForAllDevices()
                    setForeground(createForegroundInfo())
                }
            }
            
            Log.i(TAG, "Worker stopped normally (isStopped = true)")
            sendBroadcast("terminating|Stopped by user")
            Result.success()
            
        } catch (e: CancellationException) {
            // This is normal cancellation when work is stopped
            Log.i(TAG, "Worker cancelled normally")
            sendBroadcast("terminating|Stopped by user")
            throw e // Re-throw to let WorkManager handle it properly
        } catch (e: Exception) {
            Log.e(TAG, "Worker crashed with unexpected exception", e)
            sendBroadcast("terminating|Worker exception: ${e.message}")
            Result.failure()
        } finally {
            Log.i(TAG, "Worker cleanup")
            connectIQService.setMessageCallback(null)
            connectIQService.setDeviceChangeCallback(null)
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
        
        val cancelIntent = androidx.work.WorkManager.getInstance(applicationContext)
            .createCancelPendingIntent(id)
        
        val contentText = buildContentText()
        val bigText = buildBigText()
        
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Garmin Activity Listener")
            .setContentText(contentText)
            .setTicker("Listening for Garmin messages")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setColor(0xFF4CAF50.toInt())
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openPendingIntent)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .addAction(android.R.drawable.ic_menu_view, "Open", openPendingIntent)
            .addAction(android.R.drawable.ic_delete, "Stop", cancelIntent)
            .build()
        
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
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