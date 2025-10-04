package net.xrxss15

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MainActivity - Debug UI
 * Receives same intents as Tasker for accurate debugging
 */
class MainActivity : Activity() {

    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
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
        }

        appendLog("━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        appendLog("Garmin Activity Listener")
        appendLog("Debug Mode")
        appendLog("━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        appendLog("")
        appendLog("Press 'Start Listener' to begin")
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

            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 8, 0, 8)
                
                startBtn = Button(this@MainActivity).apply {
                    text = "Start Listener"
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener {
                        appendLog("[${ts()}] Starting listener...")
                        sendBroadcast(Intent(ActivityStatusCheckReceiver.ACTION_START))
                    }
                }
                
                stopBtn = Button(this@MainActivity).apply {
                    text = "Stop Listener"
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener {
                        appendLog("[${ts()}] Stopping listener...")
                        sendBroadcast(Intent(ActivityStatusCheckReceiver.ACTION_STOP))
                    }
                }
                
                addView(startBtn)
                addView(stopBtn)
            })

            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, 8)
                
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
            "log" -> {
                if (parts.size >= 2) appendLog(parts[1])
            }
            
            "devices" -> {
                if (parts.size >= 2) {
                    val count = parts[1].toIntOrNull() ?: 0
                    appendLog("[${ts()}] ━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    appendLog("[${ts()}] 📡 DEVICES: $count")
                    for (i in 2 until parts.size) {
                        appendLog("[${ts()}]   • ${parts[i]}")
                    }
                    appendLog("[${ts()}] ━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                }
            }
            
            "message_received" -> {
                if (parts.size >= 6) {
                    appendLog("━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    appendLog("📱 MESSAGE")
                    appendLog("Device: ${parts[1]}")
                    appendLog("Received: ${formatTimestamp(System.currentTimeMillis())}")
                    
                    // Accept both "STARTED"/"STOPPED" and "ACTIVITY_STARTED"/"ACTIVITY_STOPPED"
                    val eventDisplay = when (parts[2]) {
                        "STARTED", "ACTIVITY_STARTED" -> "🏃 STARTED"
                        "STOPPED", "ACTIVITY_STOPPED" -> "⏹️  STOPPED"
                        else -> parts[2]
                    }
                    appendLog("Event: $eventDisplay")
                    
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
                    
                    appendLog("━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                }
            }
            
            "terminating" -> {
                if (parts.size >= 2) {
                    appendLog("[${ts()}] ━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    appendLog("[${ts()}] ⚠️ TERMINATING")
                    appendLog("[${ts()}] Reason: ${parts[1]}")
                    appendLog("[${ts()}] ━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                }
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
        return needs.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun requestRequiredPermissions() {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), 100)
        appendLog("[${ts()}] Requesting permissions...")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            appendLog("[${ts()}] Permissions ${if (allGranted) "✅ Granted" else "❌ Denied"}")
        }
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