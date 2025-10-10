package net.xrxss15

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        private const val SDK_INIT_WAIT_MS = 30_000L
        private const val SDK_CHECK_INTERVAL_MS = 500L
        private const val MAX_MESSAGE_HISTORY = 100
        const val ACTION_REQUEST_HISTORY = "net.xrxss15.internal.REQUEST_HISTORY"
    }

    private val connectIQService = ConnectIQService.getInstance()
    private var connectedDeviceNames = listOf<String>()
    private var lastMessage: String? = null
    private var lastMessageTime: Long = 0
    private lateinit var notificationManager: NotificationManager
    private val stateMutex = Mutex()
    private val messageHistory = mutableListOf<String>()
    private var historyReceiver: BroadcastReceiver? = null

    override suspend fun doWork(): Result {
        Log.i(TAG, "Worker starting")
        return try {
            notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            setForeground(createForegroundInfo())
            Log.i(TAG, "Foreground service started")

            Log.i(TAG, "Waiting for SDK initialization...")
            var attempts = 0
            val maxAttempts = (SDK_INIT_WAIT_MS / SDK_CHECK_INTERVAL_MS).toInt()

            while (!connectIQService.isInitialized() && attempts < maxAttempts) {
                delay(SDK_CHECK_INTERVAL_MS)
                attempts++
            }

            if (!connectIQService.isInitialized()) {
                Log.e(TAG, "SDK not initialized after ${SDK_INIT_WAIT_MS}ms")
                sendTerminatedBroadcast("SDK not initialized by MainActivity")
                return Result.failure()
            }

            Log.i(TAG, "SDK confirmed initialized")

            // Register history request receiver
            registerHistoryReceiver()

            connectIQService.setMessageCallback { payload, deviceName, timestamp ->
                CoroutineScope(Dispatchers.Main).launch {
                    try {
                        stateMutex.withLock {
                            lastMessage = parseMessage(payload, deviceName)
                            lastMessageTime = timestamp
                            
                            // Add to history buffer
                            val logEntry = "[${formatTime(timestamp)}] $deviceName: $lastMessage"
                            messageHistory.add(logEntry)
                            if (messageHistory.size > MAX_MESSAGE_HISTORY) {
                                messageHistory.removeAt(0)
                            }
                        }
                        updateNotification()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in message callback: ${e.message}")
                    }
                }
            }

            connectIQService.setDeviceChangeCallback {
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        Log.i(TAG, "Device change callback triggered")
                        
                        connectIQService.registerListenersForAllDevices()
                        
                        val devices = connectIQService.getConnectedRealDevices(applicationContext)
                            .map { it.friendlyName ?: "Unknown" }
                        
                        stateMutex.withLock {
                            connectedDeviceNames = devices
                        }

                        val deviceNames = devices.joinToString("/")
                        val intent = Intent(ActivityStatusCheckReceiver.ACTION_EVENT).apply {
                            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                            putExtra("type", "DeviceList")
                            putExtra("devices", deviceNames)
                        }
                        applicationContext.sendBroadcast(intent)
                        updateNotification()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in device change callback: ${e.message}")
                    }
                }
            }

            connectIQService.registerListenersForAllDevices()
            val initialDevices = connectIQService.getConnectedRealDevices(applicationContext)
                .map { it.friendlyName ?: "Unknown" }
            
            stateMutex.withLock {
                connectedDeviceNames = initialDevices
            }

            updateNotification()
            sendCreatedBroadcast(initialDevices)

            Log.i(TAG, "Listening for events (${initialDevices.size} device(s))")

            suspendCancellableCoroutine<Unit> { continuation ->
                continuation.invokeOnCancellation {
                    Log.i(TAG, "Worker cancelled")
                }
            }

            Result.success()
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
            unregisterHistoryReceiver()
            connectIQService.setMessageCallback(null)
            connectIQService.setDeviceChangeCallback(null)
        }
    }

    private fun registerHistoryReceiver() {
        historyReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == ACTION_REQUEST_HISTORY) {
                    Log.i(TAG, "History request received")
                    CoroutineScope(Dispatchers.Main).launch {
                        val historyString = stateMutex.withLock {
                            messageHistory.joinToString("\n")
                        }
                        val responseIntent = Intent(ActivityStatusCheckReceiver.ACTION_EVENT).apply {
                            setPackage(applicationContext.packageName)
                            putExtra("type", "History")
                            putExtra("messages", historyString)
                        }
                        applicationContext.sendBroadcast(responseIntent)
                        Log.i(TAG, "History sent (${messageHistory.size} messages)")
                    }
                }
            }
        }
        
        val filter = IntentFilter(ACTION_REQUEST_HISTORY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            applicationContext.registerReceiver(historyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            applicationContext.registerReceiver(historyReceiver, filter)
        }
        Log.i(TAG, "History receiver registered")
    }

    private fun unregisterHistoryReceiver() {
        historyReceiver?.let {
            try {
                applicationContext.unregisterReceiver(it)
                Log.i(TAG, "History receiver unregistered")
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering history receiver: ${e.message}")
            }
        }
        historyReceiver = null
    }

    private suspend fun updateNotification() {
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

    private suspend fun createForegroundInfo(): ForegroundInfo {
        createNotificationChannel()
        val notification = buildNotification()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private suspend fun buildNotification(): android.app.Notification {
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

        val contentText = stateMutex.withLock { buildContentText() }
        val bigText = stateMutex.withLock { buildBigText() }

        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
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
            connectedDeviceNames.forEach { sb.append(" - $it\n") }
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

    private fun sendCreatedBroadcast(deviceNames: List<String>) {
        try {
            val devicesString = deviceNames.joinToString("/")
            val intent = Intent(ActivityStatusCheckReceiver.ACTION_EVENT).apply {
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
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

    private fun sendTerminatedBroadcast(reason: String) {
        try {
            val intent = Intent(ActivityStatusCheckReceiver.ACTION_EVENT).apply {
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
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