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
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var statusText: TextView
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var batteryBtn: Button
    private lateinit var copyBtn: Button
    private lateinit var clearBtn: Button
    
    private val handler = Handler(Looper.getMainLooper())
    private var messageReceiver: BroadcastReceiver? = null

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

        createUI()
        registerBroadcastReceiver()

        if (!hasRequiredPermissions()) {
            requestRequiredPermissions()
        } else if (!isBatteryOptimizationDisabled()) {
            appendLog("⚠ Battery optimization is enabled")
            appendLog("Press 'Battery Settings' to allow background running")
        } else {
            // Auto-start listener if permissions granted and battery optimization disabled
            startListener()
        }

        appendLog("Garmin Activity Listener - Debug Mode")
        updateServiceStatus()
    }

    private fun createUI() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)

            addView(TextView(this@MainActivity).apply {
                text = "Garmin Activity Listener\nDebug Mode"
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
            })

            statusText = TextView(this@MainActivity).apply {
                text = "Status: Checking..."
                textSize = 14f
                setPadding(0, 8, 0, 8)
            }
            addView(statusText)

            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 8, 0, 8)
                
                startBtn = Button(this@MainActivity).apply {
                    text = "Start Listener"
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener {
                        appendLog("[${ts()}] Starting listener...")
                        startListener()
                    }
                }
                
                stopBtn = Button(this@MainActivity).apply {
                    text = "Stop Listener"
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener {
                        appendLog("[${ts()}] Stopping listener...")
                        stopListener()
                    }
                }
                
                addView(startBtn)
                addView(stopBtn)
            })

            batteryBtn = Button(this@MainActivity).apply {
                text = "Battery Settings"
                setOnClickListener {
                    requestBatteryOptimizationExemption()
                }
            }
            addView(batteryBtn)

            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 8, 0, 8)
                
                copyBtn = Button(this@MainActivity).apply {
                    text = "Copy"
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener {
                        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("log", logView.text))
                        Toast.makeText(this@MainActivity, "Copied", Toast.LENGTH_SHORT).show()
                    }
                }
                
                clearBtn = Button(this@MainActivity).apply {
                    text = "Clear"
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener { logView.text = "" }
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

    private fun startListener() {
        val intent = Intent(ActivityStatusCheckReceiver.ACTION_START).apply {
            setPackage(packageName)
        }
        sendBroadcast(intent)
        updateServiceStatus()
    }

    private fun stopListener() {
        val intent = Intent(ActivityStatusCheckReceiver.ACTION_STOP).apply {
            setPackage(packageName)
        }
        sendBroadcast(intent)
        updateServiceStatus()
    }

    private fun isListenerRunning(): Boolean {
        val workManager = WorkManager.getInstance(applicationContext)
        val workInfos = workManager.getWorkInfosForUniqueWork("garmin_listener").get()
        return workInfos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
    }

    private fun updateServiceStatus() {
        handler.postDelayed({
            val running = isListenerRunning()
            statusText.text = if (running) {
                "Status: ✓ Listener Running"
            } else {
                "Status: ✗ Listener Stopped"
            }
            startBtn.isEnabled = !running
            stopBtn.isEnabled = running
        }, 500)
    }

    private fun isBatteryOptimizationDisabled(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            return pm.isIgnoringBatteryOptimizations(packageName)
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
                if (intent?.action == ActivityStatusCheckReceiver.ACTION_MESSAGE) {
                    val message = intent.getStringExtra(ActivityStatusCheckReceiver.EXTRA_MESSAGE)
                    message?.let { handleGarminMessage(it) }
                }
            }
        }
        
        val filter = IntentFilter(ActivityStatusCheckReceiver.ACTION_MESSAGE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(messageReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(messageReceiver, filter)
        }
    }

    private fun handleGarminMessage(message: String) {
        val parts = message.split("|")
        if (parts.isEmpty()) return
        
        when (parts[0]) {
            "devices" -> {
                if (parts.size >= 2) {
                    val count = parts[1].toIntOrNull() ?: 0
                    appendLog("[${ts()}] DEVICES: $count")
                    for (i in 2 until parts.size) {
                        appendLog("[${ts()}]   ${parts[i]}")
                    }
                }
            }
            
            "message_received" -> {
                if (parts.size >= 6) {
                    appendLog("")
                    appendLog("MESSAGE RECEIVED")
                    appendLog("Device: ${parts[1]}")
                    appendLog("Received: ${formatTimestamp(System.currentTimeMillis())}")
                    
                    val event = when (parts[2]) {
                        "STARTED", "ACTIVITY_STARTED" -> "STARTED"
                        "STOPPED", "ACTIVITY_STOPPED" -> "STOPPED"
                        else -> parts[2]
                    }
                    appendLog("Event: $event")
                    
                    try {
                        val eventTime = parts[3].toLong() * 1000
                        appendLog("Event Time: ${formatTimestamp(eventTime)}")
                    } catch (e: Exception) {
                        appendLog("Event Time: ${parts[3]}")
                    }
                    
                    appendLog("Activity: ${parts[4]}")
                    
                    try {
                        appendLog("Duration: ${formatDuration(parts[5].toInt())}")
                    } catch (e: Exception) {
                        appendLog("Duration: ${parts[5]}")
                    }
                    appendLog("")
                }
            }
            
            "terminating" -> {
                if (parts.size >= 2) {
                    appendLog("[${ts()}] TERMINATING: ${parts[1]}")
                }
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
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), 100)
        appendLog("[${ts()}] Requesting permissions...")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            appendLog("[${ts()}] Permissions ${if (allGranted) "granted" else "denied"}")
            if (allGranted && isBatteryOptimizationDisabled()) {
                startListener()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
    }
    
    override fun onDestroy() {
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