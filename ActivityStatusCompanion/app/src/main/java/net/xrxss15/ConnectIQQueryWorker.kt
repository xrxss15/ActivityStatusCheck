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
        Log.i(TAG, "Worker starting")
        
        return try {
            setForeground(createForegroundInfo())
            Log.i(TAG, "✓ Foreground service")
            
            if (!connectIQService.isInitialized()) {
                Log.e(TAG, "SDK not initialized")
                sendBroadcast("terminating|Open app first")
                return Result.failure()
            }
            
            delay(500)
            
            // SET THE MESSAGE CALLBACK FIRST!
            connectIQService.setMessageCallback { payload, deviceName, timestamp ->
                Log.i(TAG, "Message from $deviceName: $payload")
                lastMessage = parseMessage(payload, deviceName)
                lastMessageTime = timestamp
                sendBroadcast("message_received|$deviceName|$payload")
            }
            
            connectIQService.setDeviceChangeCallback {
                Log.i(TAG, "Device change")
            }
            
            // NOW register listeners (will use the callback we just set)
            connectIQService.registerListenersForAllDevices()
            
            val devices = connectIQService.getConnectedRealDevices()
            Log.i(TAG, "Found ${devices.size} device(s)")
            
            lastDeviceIds = devices.map { it.deviceIdentifier }.toSet()
            connectedDeviceNames = devices.map { it.friendlyName ?: "Unknown" }
            
            setForeground(createForegroundInfo())
            sendDeviceListMessage(devices)
            
            Log.i(TAG, "Worker ready, listening for messages")
            
            var loopCounter = 0
            var updateCounter = 0
            while (!isStopped) {
                delay(1000)
                loopCounter++
                
                if (loopCounter % 30 == 0) {
                    Log.i(TAG, "Running ${loopCounter}s")
                }
                
                updateCounter++
                if (updateCounter >= 5) {
                    updateCounter = 0
                    setForeground(createForegroundInfo())
                }
                
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
            
            Log.i(TAG, "Worker stopped")
            sendBroadcast("terminating|Stopped")
            Result.success()
            
        } catch (e: CancellationException) {
            Log.i(TAG, "Cancelled")
            sendBroadcast("terminating|Stopped")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error", e)
            sendBroadcast("terminating|${e.message}")
            Result.failure()
        } finally {
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
        return "$event ${parts[3]}"
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
        
        val cancelIntent = androidx.work.WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
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
            .addAction(android.R.drawable.ic_delete, "Stop", cancelIntent)
            .build()
        
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }
    
    private fun buildContentText(): String {
        return when {
            connectedDeviceNames.isEmpty() -> "Waiting for devices..."
            lastMessage != null -> lastMessage!!
            else -> "${connectedDeviceNames.size} device(s)"
        }
    }
    
    private fun buildBigText(): String {
        val sb = StringBuilder()
        if (connectedDeviceNames.isEmpty()) {
            sb.append("Devices: None\n")
        } else {
            sb.append("Devices:\n")
            connectedDeviceNames.forEach { sb.append("  • $it\n") }
        }
        if (lastMessage != null && lastMessageTime > 0) {
            sb.append("\nLast: $lastMessage\n${formatTime(lastMessageTime)}")
        } else {
            sb.append("\nNo messages")
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
            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }
    
    private fun sendDeviceListMessage(devices: List<IQDevice>) {
        val parts = mutableListOf("devices", devices.size.toString())
        devices.forEach { parts.add(it.friendlyName ?: "Unknown") }
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