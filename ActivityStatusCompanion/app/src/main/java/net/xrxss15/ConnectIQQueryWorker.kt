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
import org.json.JSONObject
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
        
        // Action constants
        const val ACTION_EVENT = "net.xrxss15.GARMIN_ACTIVITY_LISTENER_EVENT"
        const val ACTION_REQUEST_HISTORY = "net.xrxss15.REQUEST_HISTORY"
        const val ACTION_PING = "net.xrxss15.PING"
        const val ACTION_TERMINATE = "net.xrxss15.TERMINATE"
        const val ACTION_OPEN_GUI = "net.xrxss15.OPEN_GUI"
        const val ACTION_CLOSE_GUI = "net.xrxss15.CLOSE_GUI"
    }

    private val connectIQService = ConnectIQService.getInstance()
    private var connectedDeviceNames = listOf<String>()
    private var lastMessage: String? = null
    private var lastMessageTime: Long = 0
    private lateinit var notificationManager: NotificationManager
    private val stateMutex = Mutex()
    private val eventHistory = mutableListOf<String>()
    private var controlReceiver: BroadcastReceiver? = null
    private var previousDeviceList = emptyList<String>()
    private var workerStartTime: Long = 0

    override suspend fun doWork(): Result {
        Log.i(TAG, "Worker starting")
        workerStartTime = System.currentTimeMillis()
        
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

            registerControlReceiver()

            connectIQService.setMessageCallback { payload, deviceName, _ ->
                CoroutineScope(Dispatchers.Main).launch {
                    try {
                        val receiveTime = System.currentTimeMillis()
                        stateMutex.withLock {
                            lastMessage = parseMessage(payload, deviceName)
                            lastMessageTime = receiveTime
                            storeActivityEvent(payload, deviceName, receiveTime)
                        }
                        
                        sendActivityBroadcast(payload, deviceName, receiveTime)
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
                        val receiveTime = System.currentTimeMillis()
                        
                        connectIQService.registerListenersForAllDevices()
                        
                        val devices = connectIQService.getConnectedRealDevices(applicationContext)
                            .map { it.friendlyName ?: "Unknown" }
                        
                        stateMutex.withLock {
                            val added = devices.filter { it !in previousDeviceList }
                            val removed = previousDeviceList.filter { it !in devices }
                            
                            added.forEach { device ->
                                storeConnectionEvent(device, true, receiveTime)
                                sendConnectionBroadcast(device, true, receiveTime)
                            }
                            
                            removed.forEach { device ->
                                storeConnectionEvent(device, false, receiveTime)
                                sendConnectionBroadcast(device, false, receiveTime)
                            }
                            
                            connectedDeviceNames = devices
                            previousDeviceList = devices
                        }

                        updateNotification()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in device change callback: ${e.message}")
                    }
                }
            }

            connectIQService.registerListenersForAllDevices()
            val initialDevices = connectIQService.getConnectedRealDevices(applicationContext)
                .map { it.friendlyName ?: "Unknown" }
            
            val startTime = System.currentTimeMillis()
            stateMutex.withLock {
                connectedDeviceNames = initialDevices
                previousDeviceList = initialDevices
                
                initialDevices.forEach { device ->
                    storeConnectionEvent(device, true, startTime)
                }
            }

            updateNotification()
            sendCreatedBroadcast(initialDevices, startTime)

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
            unregisterControlReceiver()
            connectIQService.setMessageCallback(null)
            connectIQService.setDeviceChangeCallback(null)
        }
    }

    private fun storeActivityEvent(payload: String, deviceName: String, receiveTime: Long) {
        try {
            val parts = payload.split("|")
            if (parts.size >= 4) {
                val eventData = JSONObject().apply {
                    put("type", if (parts[0].contains("STARTED")) "Started" else "Stopped")
                    put("device", deviceName)
                    put("time", parts[1].toLong())
                    put("activity", parts[2])
                    put("duration", parts[3].toInt())
                    put("receive_time", receiveTime)
                }
                eventHistory.add(eventData.toString())
                if (eventHistory.size > MAX_MESSAGE_HISTORY) {
                    eventHistory.removeAt(0)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error storing activity event: ${e.message}")
        }
    }

    private fun storeConnectionEvent(deviceName: String, connected: Boolean, receiveTime: Long) {
        try {
            val eventData = JSONObject().apply {
                put("type", if (connected) "Connected" else "Disconnected")
                put("device", deviceName)
                put("receive_time", receiveTime)
            }
            eventHistory.add(eventData.toString())
            if (eventHistory.size > MAX_MESSAGE_HISTORY) {
                eventHistory.removeAt(0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error storing connection event: ${e.message}")
        }
    }

    private fun sendActivityBroadcast(payload: String, deviceName: String, receiveTime: Long) {
        try {
            val parts = payload.split("|")
            if (parts.size < 4) return

            val eventType = if (parts[0].contains("STARTED")) "Started" else "Stopped"
            val time = parts[1].toLong()
            val activity = parts[2]
            val duration = parts[3].toInt()

            val intent = Intent(ACTION_EVENT).apply {
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                putExtra("type", eventType)
                putExtra("device", deviceName)
                putExtra("time", time)
                putExtra("activity", activity)
                putExtra("duration", duration)
                putExtra("receive_time", receiveTime)
            }
            applicationContext.sendBroadcast(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending activity broadcast: ${e.message}")
        }
    }

    private fun sendConnectionBroadcast(deviceName: String, connected: Boolean, receiveTime: Long) {
        try {
            val intent = Intent(ACTION_EVENT).apply {
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                putExtra("type", if (connected) "Connected" else "Disconnected")
                putExtra("device", deviceName)
                putExtra("receive_time", receiveTime)
            }
            applicationContext.sendBroadcast(intent)
            Log.i(TAG, "Sent ${if (connected) "Connected" else "Disconnected"} broadcast for $deviceName")
        } catch (e: Exception) {
            Log.e(TAG, "Error sending connection broadcast: ${e.message}")
        }
    }

    private fun registerControlReceiver() {
        controlReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                Log.i(TAG, "Received action: ${intent?.action}")
                when (intent?.action) {
                    ACTION_REQUEST_HISTORY -> {
                        Log.i(TAG, "History request received")
                        CoroutineScope(Dispatchers.Main).launch {
                            val history = stateMutex.withLock {
                                eventHistory.toList()
                            }
                            
                            history.forEach { eventJson ->
                                try {
                                    val event = JSONObject(eventJson)
                                    val responseIntent = Intent(ACTION_EVENT).apply {
                                        setPackage(applicationContext.packageName)
                                        putExtra("type", event.getString("type"))
                                        putExtra("receive_time", event.getLong("receive_time"))
                                        
                                        when (event.getString("type")) {
                                            "Started", "Stopped" -> {
                                                putExtra("device", event.getString("device"))
                                                putExtra("time", event.getLong("time"))
                                                putExtra("activity", event.getString("activity"))
                                                putExtra("duration", event.getInt("duration"))
                                            }
                                            "Connected", "Disconnected" -> {
                                                putExtra("device", event.getString("device"))
                                            }
                                        }
                                    }
                                    applicationContext.sendBroadcast(responseIntent)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error parsing history event: ${e.message}")
                                }
                            }
                            Log.i(TAG, "History sent (${history.size} events)")
                        }
                    }
                    ACTION_PING -> {
                        Log.i(TAG, "Ping received, sending Pong...")
                        try {
                            val pongIntent = Intent(ACTION_EVENT).apply {
                                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                                putExtra("type", "Pong")
                                putExtra("worker_start_time", workerStartTime)
                                putExtra("receive_time", System.currentTimeMillis())
                            }
                            applicationContext.sendBroadcast(pongIntent)
                            Log.i(TAG, "Pong sent: worker_start_time=$workerStartTime")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to send Pong: ${e.message}", e)
                        }
                    }
                    ACTION_TERMINATE -> {
                        Log.i(TAG, "Terminate received - stopping worker")
                        sendTerminatedBroadcast("Tasker terminate")
                        ConnectIQService.resetInstance()
                        android.os.Process.killProcess(android.os.Process.myPid())
                    }
                    ACTION_OPEN_GUI -> {
                        Log.i(TAG, "Open GUI received")
                        try {
                            val openIntent = Intent(context, MainActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            }
                            context?.startActivity(openIntent)
                            Log.i(TAG, "MainActivity started")
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to start MainActivity: ${e.message}", e)
                        }
                    }
                    ACTION_CLOSE_GUI -> {
                        Log.i(TAG, "Close GUI received - sending broadcast to MainActivity")
                        val closeIntent = Intent(ACTION_EVENT).apply {
                            addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                            putExtra("type", "CloseGUI")
                            putExtra("receive_time", System.currentTimeMillis())
                        }
                        applicationContext.sendBroadcast(closeIntent)
                        Log.i(TAG, "CloseGUI broadcast sent")
                    }
                }
            }
        }
        
        val filter = IntentFilter().apply {
            addAction(ACTION_REQUEST_HISTORY)
            addAction(ACTION_PING)
            addAction(ACTION_TERMINATE)
            addAction(ACTION_OPEN_GUI)
            addAction(ACTION_CLOSE_GUI)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            applicationContext.registerReceiver(controlReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            applicationContext.registerReceiver(controlReceiver, filter)
        }
        Log.i(TAG, "Control receiver registered (RECEIVER_EXPORTED) - external apps can send")
    }

    private fun unregisterControlReceiver() {
        controlReceiver?.let {
            try {
                applicationContext.unregisterReceiver(it)
                Log.i(TAG, "Control receiver unregistered")
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering receiver: ${e.message}")
            }
        }
        controlReceiver = null
    }

    private suspend fun updateNotification() {
        val notification = buildNotification()
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun parseMessage(payload: String, deviceName: String): String {
        val parts = payload.split("|")
        if (parts.size < 4) return "Message from $deviceName"

        val event = when {
            parts[0].contains("STARTED") -> "Started"
            parts[0].contains("STOPPED") -> "Stopped"
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

        val exitIntent = Intent(ACTION_TERMINATE)
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

    private fun sendCreatedBroadcast(deviceNames: List<String>, receiveTime: Long) {
        try {
            val intent = Intent(ACTION_EVENT).apply {
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                putExtra("type", "Created")
                putExtra("timestamp", System.currentTimeMillis())
                putExtra("device_count", deviceNames.size)
                putExtra("worker_start_time", workerStartTime)
                putExtra("receive_time", receiveTime)
            }
            applicationContext.sendBroadcast(intent)
            
            deviceNames.forEach { device ->
                sendConnectionBroadcast(device, true, receiveTime)
            }
            
            Log.i(TAG, "Created broadcast sent with ${deviceNames.size} device(s)")
        } catch (e: Exception) {
            Log.e(TAG, "Created broadcast failed", e)
        }
    }

    private fun sendTerminatedBroadcast(reason: String) {
        try {
            val intent = Intent(ACTION_EVENT).apply {
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                putExtra("type", "Terminated")
                putExtra("reason", reason)
                putExtra("receive_time", System.currentTimeMillis())
            }
            applicationContext.sendBroadcast(intent)
            Log.i(TAG, "Terminated broadcast sent: $reason")
        } catch (e: Exception) {
            Log.e(TAG, "Terminated broadcast failed", e)
        }
    }
}