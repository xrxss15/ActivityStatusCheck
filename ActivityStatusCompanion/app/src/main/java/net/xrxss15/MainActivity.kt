package net.xrxss15

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
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
    private var batteryUpdateRunnable: Runnable? = null

    companion object {
        const val ACTION_CLOSE_GUI = "net.xrxss15.CLOSE_GUI"
        const val ACTION_OPEN_GUI = "net.xrxss15.OPEN_GUI"
        
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUEST_CODE = 100
        private const val BATTERY_UPDATE_INTERVAL_MS = 30_000L
        private const val STATUS_UPDATE_DELAY_MS = 500L
        private const val STATUS_UPDATE_INTERVAL_MS = 1_000L
    }

    private fun ts(): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
    
    private fun formatTimestamp(timestampMillis: Long): String {
        return SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(Date(timestampMillis))
    }
    
    private fun formatDuration(seconds: Int): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return String.format("%02d:%02d:%02d", hours, minutes, secs)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent.action == ACTION_CLOSE_GUI) {
            val removeTask = intent.getBooleanExtra("finish_and_remove_task", false)
            if (removeTask) {
                finishAndRemoveTask()
            } else {
                finish()
            }
            return
        }

        createUI()
        registerBroadcastReceiver()

        appendLog("Garmin Activity Listener")
        
        if (!hasRequiredPermissions()) {
            requestRequiredPermissions()
        } else {
            initializeAndStart()
        }

        updateServiceStatus()
        updateBatteryStats()
        
        batteryUpdateRunnable = object : Runnable {
            override fun run() {
                updateBatteryStats()
                handler.postDelayed(this, BATTERY_UPDATE_INTERVAL_MS)
            }
        }
        handler.postDelayed(batteryUpdateRunnable!!, BATTERY_UPDATE_INTERVAL_MS)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        
        when (intent?.action) {
            ACTION_CLOSE_GUI -> {
                val removeTask = intent.getBooleanExtra("finish_and_remove_task", false)
                if (removeTask) {
                    finishAndRemoveTask()
                } else {
                    finish()
                }
            }
            Intent.ACTION_MAIN -> {
                // App restarted from launcher after Exit
                appendLog("[${ts()}] App reopened")
                updateServiceStatus()
            }
        }
    }

    private fun initializeAndStart() {
        // Always initialize SDK in MainActivity
        appendLog("[${ts()}] Initializing ConnectIQ SDK...")
        
        connectIQService.initializeSdkIfNeeded(this) {
            handler.post {
                appendLog("[${ts()}] SDK initialized successfully")
                
                if (!isBatteryOptimizationDisabled()) {
                    appendLog("Battery optimization is enabled")
                    appendLog("Press 'Battery Settings' to allow background running")
                }
                
                // Start worker after SDK is ready
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
        
        handler.postDelayed({
            updateServiceStatus()
        }, STATUS_UPDATE_INTERVAL_MS)
    }

    private fun updateBatteryStats() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                val batteryManager = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                if (batteryManager == null) {
                    batteryText.text = "Battery stats unavailable"
                    return
                }
                
                val batteryPct = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                
                val stats = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    "Battery: $batteryPct% | App usage: Check system battery settings"
                } else {
                    "Battery: $batteryPct%"
                }
                
                batteryText.text = stats
                
            } catch (e: Exception) {
                batteryText.text = "Battery stats unavailable"
                Log.e(TAG, "Failed to get battery stats: ${e.message}")
            }
        } else {
            batteryText.text = "Battery stats not supported"
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
                text = "Battery: --"
                textSize = 14f
                setPadding(0, 4, 0, 8)
            }
            addView(batteryText)

            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 8, 0, 8)
                
                batteryBtn = Button(this@MainActivity).apply {
                    text = "Battery"
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener {
                        requestBatteryOptimizationExemption()
                    }
                }
                
                hideBtn = Button(this@MainActivity).apply {
                    text = "Hide GUI"
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener {
                        appendLog("[${ts()}] Hiding GUI (worker keeps running)...")
                        finish()
                    }
                }
                
                exitBtn = Button(this@MainActivity).apply {
                    text = "Exit"
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener {
                        appendLog("[${ts()}] Exiting app (stopping worker)...")
                        exitApp()
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

    private fun exitApp() {
        val intent = Intent(ActivityStatusCheckReceiver.ACTION_TERMINATE).apply {
            setPackage(packageName)
            setClass(this@MainActivity, ActivityStatusCheckReceiver::class.java)
        }
        sendBroadcast(intent)
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
        handler.postDelayed({
            val running = isListenerRunning()
            statusText.text = if (running) {
                "Status: Listener Active"
            } else {
                "Status: Listener Inactive"
            }
        }, STATUS_UPDATE_DELAY_MS)
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
                when (intent?.action) {
                    ActivityStatusCheckReceiver.ACTION_EVENT -> {
                        val type = intent.getStringExtra("type")
                        handleGarminEvent(type, intent)
                    }
                    ACTION_CLOSE_GUI -> {
                        val removeTask = intent.getBooleanExtra("finish_and_remove_task", false)
                        if (removeTask) {
                            finishAndRemoveTask()
                        } else {
                            finish()
                        }
                    }
                }
            }
        }
        
        val filter = IntentFilter().apply {
            addAction(ActivityStatusCheckReceiver.ACTION_EVENT)
            addAction(ACTION_CLOSE_GUI)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        
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
                appendLog("[${ts()}] Devices updated: ${deviceList.size} device(s)")
                deviceList.forEach {
                    appendLog("[${ts()}]   - $it")
                }
            }
            
            "Created" -> {
                val timestamp = intent.getLongExtra("timestamp", 0)
                val devices = intent.getStringExtra("devices") ?: ""
                val deviceList = if (devices.isEmpty()) emptyList() else devices.split("/")
                appendLog("[${ts()}] WORKER CREATED: Listener is now running")
                appendLog("[${ts()}] Initial devices: ${deviceList.size} device(s)")
                deviceList.forEach {
                    appendLog("[${ts()}]   - $it")
                }
            }
            
            "Terminated" -> {
                val reason = intent.getStringExtra("reason") ?: "Unknown"
                appendLog("[${ts()}] WORKER TERMINATED: $reason")
            }
        }
        
        updateServiceStatus()
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

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
        updateBatteryStats()
    }
    
    override fun onDestroy() {
        batteryUpdateRunnable?.let { handler.removeCallbacks(it) }
        batteryUpdateRunnable = null
        
        super.onDestroy()
        
        messageReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: IllegalArgumentException) {
            }
        }
        messageReceiver = null
    }
}