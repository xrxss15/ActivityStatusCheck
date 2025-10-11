package net.xrxss15

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var statusText: TextView
    private lateinit var uptimeText: TextView
    private lateinit var batteryText: TextView
    private lateinit var batteryBtn: Button
    private lateinit var copyBtn: Button
    private lateinit var clearBtn: Button
    private lateinit var exitBtn: Button
    private lateinit var hideBtn: Button
    private val handler = Handler(Looper.getMainLooper())
    private var messageReceiver: BroadcastReceiver? = null
    private val connectIQService = ConnectIQService.getInstance()
    private var workerStartTime: Long = 0

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUEST_CODE = 100

        @JvmStatic
        private fun formatTime(timestamp: Long): String {
            return SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
        }

        @JvmStatic
        private fun formatDateTime(timestamp: Long): String {
            return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
        }

        @JvmStatic
        private fun formatDuration(seconds: Int): String {
            val hours = seconds / 3600
            val minutes = (seconds % 3600) / 60
            val secs = seconds % 60
            return String.format("%02d:%02d:%02d", hours, minutes, secs)
        }

        @JvmStatic
        private fun formatUptime(startTime: Long): String {
            if (startTime == 0L) return "Unknown"
            val now = System.currentTimeMillis()
            val uptimeSeconds = ((now - startTime) / 1000).toInt()
            return formatDuration(uptimeSeconds)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createUI()
        registerBroadcastReceiver()
        appendLog("Garmin Activity Listener")
        
        updateServiceStatus()
        updateBatteryOptimizationStatus()
        
        if (isListenerRunning()) {
            appendLog("Requesting worker status...")
            handler.postDelayed({
                // Send Ping to get worker start time
                val pingIntent = Intent(ConnectIQQueryWorker.ACTION_PING).apply {
                    setPackage(packageName)
                }
                sendBroadcast(pingIntent)
                
                // Request history
                val historyIntent = Intent(ConnectIQQueryWorker.ACTION_REQUEST_HISTORY).apply {
                    setPackage(packageName)
                }
                sendBroadcast(historyIntent)
            }, 500)
        }
        
        if (!hasRequiredPermissions()) {
            requestRequiredPermissions()
        } else {
            initializeAndStart()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        appendLog("[${formatTime(System.currentTimeMillis())}] App reopened")
        updateServiceStatus()
        updateBatteryOptimizationStatus()
        
        if (isListenerRunning()) {
            handler.postDelayed({
                val pingIntent = Intent(ConnectIQQueryWorker.ACTION_PING).apply {
                    setPackage(packageName)
                }
                sendBroadcast(pingIntent)
                
                val historyIntent = Intent(ConnectIQQueryWorker.ACTION_REQUEST_HISTORY).apply {
                    setPackage(packageName)
                }
                sendBroadcast(historyIntent)
            }, 500)
        }
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
        updateBatteryOptimizationStatus()
        updateUptimeDisplay()
    }

    private fun initializeAndStart() {
        if (isListenerRunning()) {
            appendLog("[${formatTime(System.currentTimeMillis())}] Worker already running")
            return
        }

        appendLog("[${formatTime(System.currentTimeMillis())}] Initializing ConnectIQ SDK...")
        connectIQService.initializeSdkIfNeeded(this) {
            handler.post {
                appendLog("[${formatTime(System.currentTimeMillis())}] SDK initialized")
                if (!isBatteryOptimizationDisabled()) {
                    appendLog("Battery optimization enabled - press 'Battery Settings'")
                }
                if (!isListenerRunning()) {
                    startWorker()
                }
            }
        }
    }

    private fun startWorker() {
        appendLog("[${formatTime(System.currentTimeMillis())}] Starting worker...")
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(false)
            .setRequiresCharging(false)
            .setRequiresDeviceIdle(false)
            .build()
        val workRequest = OneTimeWorkRequestBuilder<ConnectIQQueryWorker>()
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniqueWork(
            "garmin_listener",
            ExistingWorkPolicy.KEEP,
            workRequest
        )
    }

    private fun updateBatteryOptimizationStatus() {
        val optimizationDisabled = isBatteryOptimizationDisabled()
        batteryText.text = if (optimizationDisabled) {
            "Battery optimization: Disabled ✓"
        } else {
            "Battery optimization: Enabled"
        }
    }

    private fun updateUptimeDisplay() {
        if (workerStartTime > 0) {
            uptimeText.text = "Worker uptime: ${formatUptime(workerStartTime)}"
        } else {
            uptimeText.text = "Worker uptime: Unknown"
        }
    }

    private fun createUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
            addView(TextView(this@MainActivity).apply {
                text = "Garmin Activity Listener"
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
            statusText = TextView(this@MainActivity).apply {
                text = "Status: Initializing..."
                textSize = 14f
                setPadding(0, 8, 0, 0)
            }
            addView(statusText)
            uptimeText = TextView(this@MainActivity).apply {
                text = "Worker uptime: Unknown"
                textSize = 14f
                setPadding(0, 4, 0, 0)
            }
            addView(uptimeText)
            batteryText = TextView(this@MainActivity).apply {
                text = "Battery optimization: Checking..."
                textSize = 14f
                setPadding(0, 4, 0, 8)
            }
            addView(batteryText)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 8, 0, 8)
                batteryBtn = Button(this@MainActivity).apply {
                    text = "Battery Settings"
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener {
                        requestBatteryOptimizationExemption()
                        handler.postDelayed({
                            updateBatteryOptimizationStatus()
                        }, 500)
                    }
                }
                hideBtn = Button(this@MainActivity).apply {
                    text = "Hide"
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener {
                        appendLog("[${formatTime(System.currentTimeMillis())}] Hiding GUI")
                        finishAndRemoveTask()
                    }
                }
                exitBtn = Button(this@MainActivity).apply {
                    text = "Exit"
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener {
                        exitAppCompletely()
                    }
                }
                addView(batteryBtn)
                addView(hideBtn)
                addView(exitBtn)
            })
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, 8)
                copyBtn = Button(this@MainActivity).apply {
                    text = "Copy Log"
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener {
                        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("log", logView.text))
                        Toast.makeText(this@MainActivity, "Log copied", Toast.LENGTH_SHORT).show()
                    }
                }
                clearBtn = Button(this@MainActivity).apply {
                    text = "Clear Log"
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener {
                        logView.text = ""
                        appendLog("Garmin Activity Listener")
                    }
                }
                addView(copyBtn)
                addView(clearBtn)
            })
            scroll = ScrollView(this@MainActivity)
            logView = TextView(this@MainActivity).apply {
                textSize = 12f
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(12, 12, 12, 12)
                setBackgroundColor(0xFF1E1E1E.toInt())
                setTextColor(0xFF00FF00.toInt())
            }
            scroll.addView(logView)
            addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }

        setContentView(root)
    }

    private fun exitAppCompletely() {
        appendLog("[${formatTime(System.currentTimeMillis())}] Stopping listener...")
        WorkManager.getInstance(this).cancelUniqueWork("garmin_listener")
        var checkCount = 0
        val checkRunnable = object : Runnable {
            override fun run() {
                checkCount++
                val running = isListenerRunning()
                if (!running || checkCount >= 20) {
                    ConnectIQService.resetInstance()
                    val intent = Intent("net.xrxss15.GARMIN_ACTIVITY_LISTENER_EVENT").apply {
                        addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                        putExtra("type", "Terminated")
                        putExtra("reason", "User exit")
                    }
                    sendBroadcast(intent)
                    finishAffinity()
                    android.os.Process.killProcess(android.os.Process.myPid())
                } else {
                    handler.postDelayed(this, 100)
                }
            }
        }
        handler.postDelayed(checkRunnable, 100)
    }

    private fun isListenerRunning(): Boolean {
        return try {
            val workManager = WorkManager.getInstance(applicationContext)
            val workInfos = workManager.getWorkInfosForUniqueWork("garmin_listener").get()
            workInfos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking worker status: ${e.message}")
            false
        }
    }

    private fun updateServiceStatus() {
        val running = isListenerRunning()
        statusText.text = if (running) {
            "Status: Listener Active"
        } else {
            "Status: Listener Inactive"
        }
    }

    private fun isBatteryOptimizationDisabled(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            return pm?.isIgnoringBatteryOptimizations(packageName) ?: false
        }
        return true
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!isBatteryOptimizationDisabled()) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    intent.data = Uri.parse("package:$packageName")
                    startActivity(intent)
                    appendLog("[${formatTime(System.currentTimeMillis())}] Opening battery settings...")
                } catch (e: Exception) {
                    appendLog("[${formatTime(System.currentTimeMillis())}] Failed to open battery settings")
                }
            } else {
                appendLog("[${formatTime(System.currentTimeMillis())}] Battery optimization already disabled")
            }
        }
    }

    private fun registerBroadcastReceiver() {
        messageReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val type = intent?.getStringExtra("type") ?: return
                val receiveTime = intent.getLongExtra("receive_time", System.currentTimeMillis())
                handleGarminEvent(type, intent, receiveTime)
            }
        }
        val filter = IntentFilter("net.xrxss15.GARMIN_ACTIVITY_LISTENER_EVENT")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(messageReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(messageReceiver, filter)
        }
    }

    private fun handleGarminEvent(type: String?, intent: Intent, receiveTime: Long) {
        val time = formatTime(receiveTime)
        
        when (type) {
            "Pong" -> {
                workerStartTime = intent.getLongExtra("worker_start_time", 0)
                appendLog("[${formatTime(System.currentTimeMillis())}] Worker running since ${formatDateTime(workerStartTime)}")
                updateUptimeDisplay()
            }
            "Started" -> {
                val device = intent.getStringExtra("device") ?: "Unknown"
                val activity = intent.getStringExtra("activity") ?: "Unknown"
                appendLog("[$time] $device: Started $activity")
            }
            "Stopped" -> {
                val device = intent.getStringExtra("device") ?: "Unknown"
                val activity = intent.getStringExtra("activity") ?: "Unknown"
                val duration = intent.getIntExtra("duration", 0)
                appendLog("[$time] $device: Stopped $activity (${formatDuration(duration)})")
            }
            "Connected" -> {
                val device = intent.getStringExtra("device") ?: "Unknown"
                appendLog("[$time] $device: Connected")
            }
            "Disconnected" -> {
                val device = intent.getStringExtra("device") ?: "Unknown"
                appendLog("[$time] $device: Disconnected")
            }
            "Created" -> {
                workerStartTime = intent.getLongExtra("worker_start_time", 0)
                val deviceCount = intent.getIntExtra("device_count", 0)
                appendLog("[$time] Worker started ($deviceCount device(s))")
                appendLog("[$time] Start time: ${formatDateTime(workerStartTime)}")
                updateServiceStatus()
                updateUptimeDisplay()
            }
            "Terminated" -> {
                val reason = intent.getStringExtra("reason") ?: "Unknown"
                appendLog("[$time] Worker stopped: $reason")
                workerStartTime = 0
                updateServiceStatus()
                updateUptimeDisplay()
            }
            "CloseGUI" -> {
                appendLog("[$time] Closing GUI via Tasker")
                finishAndRemoveTask()
            }
        }
    }

    private fun appendLog(line: String) {
        handler.post {
            logView.append("$line\n")
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        val needs = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            needs.add(Manifest.permission.BLUETOOTH_SCAN)
            needs.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            needs.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return needs.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun requestRequiredPermissions() {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), PERMISSION_REQUEST_CODE)
        appendLog("[${formatTime(System.currentTimeMillis())}] Requesting permissions...")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            appendLog("[${formatTime(System.currentTimeMillis())}] Permissions ${if (allGranted) "granted" else "denied"}")
            if (allGranted) {
                initializeAndStart()
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
        
        messageReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: IllegalArgumentException) {
                // Already unregistered
            }
        }
        messageReceiver = null
    }
}