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
    private lateinit var batteryText: TextView
    private lateinit var batteryBtn: Button
    private lateinit var copyBtn: Button
    private lateinit var clearBtn: Button
    private lateinit var exitBtn: Button
    private lateinit var hideBtn: Button
    private val handler = Handler(Looper.getMainLooper())
    private var messageReceiver: BroadcastReceiver? = null
    private val connectIQService = ConnectIQService.getInstance()

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUEST_CODE = 100

        @JvmStatic
        private fun ts(): String {
            return SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        }

        @JvmStatic
        private fun formatTimestamp(timestampMillis: Long): String {
            return SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(Date(timestampMillis))
        }

        @JvmStatic
        private fun formatDuration(seconds: Int): String {
            val hours = seconds / 3600
            val minutes = (seconds % 3600) / 60
            val secs = seconds % 60
            return String.format("%02d:%02d:%02d", hours, minutes, secs)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createUI()
        registerBroadcastReceiver()
        appendLog("Garmin Activity Listener")
        
        // Update UI once on creation
        updateServiceStatus()
        updateBatteryOptimizationStatus()
        
        if (!hasRequiredPermissions()) {
            requestRequiredPermissions()
        } else {
            initializeAndStart()
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        appendLog("[${ts()}] App reopened")
        updateServiceStatus()
        updateBatteryOptimizationStatus()
    }

    override fun onResume() {
        super.onResume()
        // Update UI only when activity becomes visible
        updateServiceStatus()
        updateBatteryOptimizationStatus()
    }

    private fun initializeAndStart() {
        if (isListenerRunning()) {
            appendLog("[${ts()}] Worker already running - skipping SDK init")
            return
        }

        appendLog("[${ts()}] Initializing ConnectIQ SDK...")
        connectIQService.initializeSdkIfNeeded(this) {
            handler.post {
                appendLog("[${ts()}] SDK initialized successfully")
                if (!isBatteryOptimizationDisabled()) {
                    appendLog("Battery optimization is enabled")
                    appendLog("Press 'Battery Settings' to allow background running")
                }
                if (!isListenerRunning()) {
                    startWorker()
                } else {
                    appendLog("[${ts()}] Worker already running")
                }
            }
        }
    }

    private fun startWorker() {
        appendLog("[${ts()}] Starting background worker...")
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
            "Battery optimization: Enabled (may affect background)"
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
                        // Update status after button press
                        handler.postDelayed({
                            updateBatteryOptimizationStatus()
                        }, 500)
                    }
                }
                hideBtn = Button(this@MainActivity).apply {
                    text = "Hide"
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener {
                        appendLog("[${ts()}] Hiding GUI (worker keeps running)")
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
        appendLog("[${ts()}] Stopping listener and terminating app...")
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
                    appendLog("[${ts()}] Opening battery settings...")
                } catch (e: Exception) {
                    appendLog("[${ts()}] Failed to open battery settings")
                }
            } else {
                appendLog("[${ts()}] Battery optimization already disabled")
            }
        }
    }

    private fun registerBroadcastReceiver() {
        messageReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val type = intent?.getStringExtra("type") ?: return
                handleGarminEvent(type, intent)
            }
        }
        val filter = IntentFilter("net.xrxss15.GARMIN_ACTIVITY_LISTENER_EVENT")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(messageReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(messageReceiver, filter)
        }
    }

    private fun handleGarminEvent(type: String?, intent: Intent) {
        when (type) {
            "Started", "Stopped" -> {
                val device = intent.getStringExtra("device") ?: "Unknown"
                val time = intent.getLongExtra("time", 0)
                val activity = intent.getStringExtra("activity") ?: "Unknown"
                val duration = intent.getIntExtra("duration", 0)
                appendLog("")
                appendLog("========================================")
                appendLog("ACTIVITY EVENT: $type")
                appendLog("Device: $device")
                appendLog("Time: ${formatTimestamp(time * 1000)}")
                appendLog("Activity: $activity")
                if (type == "Stopped") {
                    appendLog("Duration: ${formatDuration(duration)}")
                }
                appendLog("========================================")
                appendLog("")
            }
            "DeviceList" -> {
                val devices = intent.getStringExtra("devices") ?: ""
                val deviceList = if (devices.isEmpty()) emptyList() else devices.split("/")
                appendLog("[${ts()}] Devices: ${deviceList.size} device(s)")
                deviceList.forEach {
                    appendLog("[${ts()}]   - $it")
                }
            }
            "Created" -> {
                val devices = intent.getStringExtra("devices") ?: ""
                val deviceList = if (devices.isEmpty()) emptyList() else devices.split("/")
                appendLog("[${ts()}] WORKER STARTED")
                appendLog("[${ts()}] Devices: ${deviceList.size} device(s)")
                deviceList.forEach {
                    appendLog("[${ts()}]   - $it")
                }
                // Update status when worker starts
                updateServiceStatus()
            }
            "Terminated" -> {
                val reason = intent.getStringExtra("reason") ?: "Unknown"
                appendLog("[${ts()}] WORKER STOPPED: $reason")
                // Update status when worker stops
                updateServiceStatus()
            }
            "CloseGUI" -> {
                // Handle close GUI request from receiver
                appendLog("[${ts()}] Closing GUI via Tasker")
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
        appendLog("[${ts()}] Requesting permissions...")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            appendLog("[${ts()}] Permissions ${if (allGranted) "granted" else "denied"}")
            if (allGranted) {
                initializeAndStart()
            }
        }
    }

    override fun onDestroy() {
        // Clean up handler callbacks
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