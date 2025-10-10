package net.xrxss15

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
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

/**
 * Background worker that maintains foreground service for Garmin activity listening.
 * Runs indefinitely until explicitly stopped.
 */
class ConnectIQQueryWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "GarminActivityListener.Worker"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "garmin_listener_channel"
        private const val SDK_INIT_TIMEOUT_MS = 30_000L
        private const val SDK_INIT_CHECK_INTERVAL_MS = 100L
        private const val WAKE_LOCK_TAG = "GarminActivityListener:WorkerWakeLock"
    }

    private val connectIQService = ConnectIQService.getInstance()
    private var connectedDeviceNames = listOf<String>()
    private var lastMessage: String? = null
    private var lastMessageTime: Long = 0
    private lateinit var notificationManager: NotificationManager
    private var wakeLock: PowerManager.WakeLock? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Main worker execution method.
     * Initializes foreground service, waits for SDK, and listens for events.
     */
    override suspend fun doWork(): Result {
        Log.i(TAG, "Worker starting")
        
        acquireWakeLock()
        
        return try {
            notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            // Start foreground IMMEDIATELY to avoid Android 12+ crash
            setForeground(createForegroundInfo())
            Log.i(TAG, "Foreground service started")
            
            Log.i(TAG, "Waiting for SDK initialization...")
            var attempts = 0
            val maxAttempts = (SDK_INIT_TIMEOUT_MS / SDK_INIT_CHECK_INTERVAL_MS).toInt()
            
            while (!connectIQService.isInitialized() && attempts < maxAttempts) {
                delay(SDK_INIT_CHECK_INTERVAL_MS)
                attempts++
            }
            
            if (!connectIQService.isInitialized()) {
                Log.e(TAG, "SDK not initialized after ${SDK_INIT_TIMEOUT_MS}ms")
                sendTerminatedBroadcast("SDK not initialized")
                return Result.failure()
            }
            
            Log.i(TAG, "SDK initialized")
            
            // Set up callbacks
            connectIQService.setMessageCallback { payload, deviceName, timestamp ->
                lastMessage = parseMessage(payload, deviceName)
                lastMessageTime = timestamp
                updateNotification()
            }
            
            connectIQService.setDeviceChangeCallback {
                mainHandler.post {
                    connectedDeviceNames = connectIQService.getConnectedRealDevices(applicationContext)
                        .map { it.friendlyName ?: "Unknown" }
                    
                    val deviceNames = connectedDeviceNames.joinToString("/")
                    val intent = Intent(ActivityStatusCheckReceiver.ACTION_EVENT).apply {
                        putExtra("type", "DeviceList")
                        putExtra("devices", deviceNames)
                    }
                    applicationContext.sendBroadcast(intent)
                    
                    updateNotification()
                }
            }
            
            // Register listeners for all devices
            connectIQService.registerListenersForAllDevices()
            
            // Get initial device list
            connectedDeviceNames = connectIQService.getConnectedRealDevices(applicationContext)
                .map { it.friendlyName ?: "Unknown" }
            
            updateNotification()
            
            // Send "Created" broadcast ONLY after successful initialization with device list
            sendCreatedBroadcast(connectedDeviceNames)
            
            Log.i(TAG, "Listening for events (${connectedDeviceNames.size} device(s))")
            
            releaseWakeLock()
            
            // Suspend indefinitely until cancelled
            suspendCancellableCoroutine<Nothing> { continuation ->
                continuation.invokeOnCancellation {
                    Log.i(TAG, "Worker cancelled")
                }
            }
            
        } catch (e: CancellationException) {
            Log.i(TAG, "Worker cancelled via exception")
            sendTerminatedBroadcast("Worker cancelled")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Worker error", e)
            sendTerminatedBroadcast(e.message ?: "Unknown error")
            Result.failure()
        } finally {
            Log.i(TAG, "Worker finally block")
            releaseWakeLock()
            connectIQService.setMessageCallback(null)
            connectIQService.setDeviceChangeCallback(null)
        }
    }
    
    /**
     * Acquires wake lock to prevent CPU sleep during critical operations.
     */
    private fun acquireWakeLock() {
        try {
            val powerManager = applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                WAKE_LOCK_TAG
            )
            wakeLock?.acquire(10 * 60 * 1000L)
            Log.i(TAG, "Wake lock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire wake lock: ${e.message}")
        }
    }
    
    /**
     * Releases wake lock.
     */
    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.i(TAG, "Wake lock released")
                }
            }
            wakeLock = null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release wake lock: ${e.message}")
        }
    }
    
    /**
     * Updates the foreground notification.
     */
    private fun updateNotification() {
        val notification = buildNotification()
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    /**
     * Parses activity message payload.
     */
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
    
    /**
     * Formats timestamp for notification display.
     */
    private fun formatTime(timestamp: Long): String {
        return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
    }
    
    /**
     * Creates foreground service info.
     */
    private fun createForegroundInfo(): ForegroundInfo {
        createNotificationChannel()
        val notification = buildNotification()
        
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }
    
    /**
     * Builds the foreground notification.
     */
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
    
    /**
     * Builds notification content text.
     */
    private fun buildContentText(): String {
        return when {
            connectedDeviceNames.isEmpty() -> "Waiting for devices..."
            lastMessage != null -> lastMessage!!
            else -> "${connectedDeviceNames.size} device(s) connected"
        }
    }
    
    /**
     * Builds notification expanded text.
     */
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
    
    /**
     * Creates notification channel for Android O+.
     */
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
    
    /**
     * Sends "Created" broadcast to indicate worker is running and ready.
     * Includes initial device list.
     * 
     * @param deviceNames List of connected device names
     */
    private fun sendCreatedBroadcast(deviceNames: List<String>) {
        try {
            val devicesString = deviceNames.joinToString("/")
            val intent = Intent(ActivityStatusCheckReceiver.ACTION_EVENT).apply {
                putExtra("type", "Created")
                putExtra("timestamp", System.currentTimeMillis())
                putExtra("devices", devicesString)
            }
            
            applicationContext.sendBroadcast(intent)
            Log.i(TAG, "Created broadcast sent with ${deviceNames.size} device(s): $devicesString")
            
        } catch (e: Exception) {
            Log.e(TAG, "Created broadcast failed", e)
        }
    }
    
    /**
     * Sends "Terminated" broadcast to indicate worker has stopped.
     * 
     * @param reason Termination reason
     */
    private fun sendTerminatedBroadcast(reason: String) {
        try {
            val intent = Intent(ActivityStatusCheckReceiver.ACTION_EVENT).apply {
                putExtra("type", "Terminated")
                putExtra("reason", reason)
            }
            
            applicationContext.sendBroadcast(intent)
            Log.i(TAG, "Terminated broadcast sent: $reason")
            
        } catch (e: Exception) {
            Log.e(TAG, "Terminated broadcast failed", e)
        }
    }
}