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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
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
    private var connectedDeviceNames = listOf<String>()
    private var lastMessage: String? = null
    private var lastMessageTime: Long = 0
    private lateinit var notificationManager: NotificationManager

    override suspend fun doWork(): Result {
        Log.i(TAG, "Worker starting")
        
        return try {
            notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            setForeground(createForegroundInfo())
            Log.i(TAG, "Foreground service started")
            
            Log.i(TAG, "Waiting for SDK initialization...")
            var attempts = 0
            while (!connectIQService.isInitialized() && attempts < 300) {
                delay(100)
                attempts++
            }
            
            if (!connectIQService.isInitialized()) {
                Log.e(TAG, "SDK not initialized after 30 seconds")
                sendTerminateBroadcast("SDK not initialized")
                return Result.failure()
            }
            
            Log.i(TAG, "SDK initialized")
            
            connectIQService.setMessageCallback { payload, deviceName, timestamp ->
                lastMessage = parseMessage(payload, deviceName)
                lastMessageTime = timestamp
                updateNotification()
            }
            
            connectIQService.setDeviceChangeCallback {
                connectedDeviceNames = connectIQService.getConnectedRealDevices()
                    .map { it.friendlyName ?: "Unknown" }
                updateNotification()
            }
            
            connectIQService.registerListenersForAllDevices()
            
            connectedDeviceNames = connectIQService.getConnectedRealDevices()
                .map { it.friendlyName ?: "Unknown" }
            updateNotification()
            
            Log.i(TAG, "Listening for events (${connectedDeviceNames.size} device(s))")
            
            suspendCancellableCoroutine<Nothing> { continuation ->
                continuation.invokeOnCancellation {
                    Log.i(TAG, "Worker cancelled")
                }
            }
            
        } catch (e: CancellationException) {
            Log.i(TAG, "Worker cancelled")
            sendTerminateBroadcast("Stopped")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Worker error", e)
            sendTerminateBroadcast(e.message ?: "Unknown error")
            Result.failure()
        } finally {
            connectIQService.setMessageCallback(null)
            connectIQService.setDeviceChangeCallback(null)
        }
    }
    
    private fun updateNotification() {
        val notification = buildNotification()
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    private fun parseMessage(payload: String, deviceName: String): String {
        val parts = payload.split("|")
        if (parts.size < 4) return "Message from $deviceName"
        
        val event = when (parts[0]) {
            "STARTED", "ACTIVITY_STARTED" -> "Started"
            "STOPPED", "ACTIVITY_STOPPED" -> "Stopped"
            else -> parts[0]
        }
        return "$event ${parts[2]}"
    }
    
    private fun formatTime(timestamp: Long): String {
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
    }
    
    private fun createForegroundInfo(): ForegroundInfo {
        createNotificationChannel()
        val notification = buildNotification()
        
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }
    
    private fun buildNotification(): android.app.Notification {
        val openIntent = Intent(applicationContext, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            applicationContext, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val exitIntent = Intent(ActivityStatusCheckReceiver.ACTION_TERMINATE).apply {
            setClass(applicationContext, ActivityStatusCheckReceiver::class.java)
        }
        val exitPendingIntent = PendingIntent.getBroadcast(
            applicationContext, 1, exitIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Garmin Activity Listener")
            .setContentText(buildContentText())
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setColor(0xFF4CAF50.toInt())
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openPendingIntent)
            .setStyle(NotificationCompat.BigTextStyle().bigText(buildBigText()))
            .addAction(android.R.drawable.ic_menu_view, "Open", openPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Exit", exitPendingIntent)
            .build()
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
            sb.append("Devices: None\n")
        } else {
            sb.append("Devices:\n")
            connectedDeviceNames.forEach { sb.append("  - $it\n") }
        }
        if (lastMessage != null && lastMessageTime > 0) {
            sb.append("\nLast: $lastMessage\n${formatTime(lastMessageTime)}")
        } else {
            sb.append("\nWaiting for activity events...")
        }
        return sb.toString()
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Garmin Listener", NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Listening for Garmin events"
                enableLights(false)
                enableVibration(false)
                setSound(null, null)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun sendTerminateBroadcast(reason: String) {
        try {
            val intent = Intent(ActivityStatusCheckReceiver.ACTION_EVENT).apply {
                putExtra("type", "Terminate")
                putExtra("reason", reason)
            }
            
            applicationContext.sendBroadcast(intent)
            Log.i(TAG, "Terminate broadcast sent: $reason")
            
        } catch (e: Exception) {
            Log.e(TAG, "Broadcast failed", e)
        }
    }
}