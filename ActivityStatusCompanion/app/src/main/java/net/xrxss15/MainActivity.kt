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
 * 
 * Displays messages by receiving the same GARMIN_MESSAGE intents that Tasker receives.
 * This ensures debug output matches production behavior exactly.
 */
class MainActivity : Activity() {

    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var copyBtn: Button
    private lateinit var clearBtn: Button
    
    private val handler = Handler(Looper.getMainLooper())
    
    // Broadcast receiver for GARMIN_MESSAGE intents (same as Tasker)
    private val messageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ActivityStatusCheckReceiver.ACTION_MESSAGE) {
                val message = intent.getStringExtra(ActivityStatusCheckReceiver.EXTRA_MESSAGE) ?: return
                handleGarminMessage(message)
            }
        }
    }

    private fun ts(): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
    
    private fun formatTimestamp(timestampMillis: Long): String {
        val date = Date(timestampMillis)
        return SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(date)
    }
    
    private fun formatDuration(seconds: Int): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return String.format("%02d:%02d:%02d", hours, minutes, secs)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)

            val header = TextView(this@MainActivity).apply {
                text = "Garmin Activity Listener\nDebug Mode"
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            addView(header)

            val row1 = LinearLayout(this@MainActivity).apply { 
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 8, 0, 8)
            }
            startBtn = Button(this@MainActivity).apply { 
                text = "Start Listener"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            stopBtn = Button(this@MainActivity).apply { 
                text = "Stop Listener"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row1.addView(startBtn); row1.addView(stopBtn)
            addView(row1)

            val row2 = LinearLayout(this@MainActivity).apply { 
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, 8)
            }
            copyBtn = Button(this@MainActivity).apply { 
                text = "Copy"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            clearBtn = Button(this@MainActivity).apply { 
                text = "Clear"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row2.addView(copyBtn); row2.addView(clearBtn)
            addView(row2)

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

        // Register to receive GARMIN_MESSAGE intents (same as Tasker)
        val filter = IntentFilter(ActivityStatusCheckReceiver.ACTION_MESSAGE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(messageReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(messageReceiver, filter)
        }

        startBtn.setOnClickListener {
            appendLog("[${ts()}] Starting listener...")
            sendBroadcast(Intent(ActivityStatusCheckReceiver.ACTION_START))
        }

        stopBtn.setOnClickListener {
            appendLog("[${ts()}] Stopping listener...")
            sendBroadcast(Intent(ActivityStatusCheckReceiver.ACTION_STOP))
        }

        copyBtn.setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("log", logView.text))
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        }

        clearBtn.setOnClickListener { 
            logView.text = ""
        }

        if (!hasRequiredPermissions()) {
            requestRequiredPermissions()
        }

        appendLog("━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        appendLog("Garmin Activity Listener")
        appendLog("Debug Mode - Receiving Intents")
        appendLog("━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        appendLog("")
        appendLog("Press 'Start Listener' to begin")
    }

    /**
     * Handles GARMIN_MESSAGE intents - same format as Tasker receives
     * 
     * Message types:
     * - devices|COUNT|NAME1|NAME2|...
     * - message_received|DEVICE|EVENT|TIMESTAMP|ACTIVITY|DURATION
     * - terminating|REASON
     */
    private fun handleGarminMessage(message: String) {
        val parts = message.split("|")
        if (parts.isEmpty()) return
        
        when (parts[0]) {
            "devices" -> {
                // Format: devices|COUNT|NAME1|NAME2|...
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
                // Format: message_received|DEVICE|EVENT|TIMESTAMP|ACTIVITY|DURATION
                if (parts.size >= 6) {
                    val device = parts[1]
                    val event = parts[2]
                    val eventTimestamp = parts[3]
                    val activity = parts[4]
                    val duration = parts[5]
                    
                    appendLog("━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    appendLog("📱 MESSAGE")
                    appendLog("Device: $device")
                    appendLog("Received: ${formatTimestamp(System.currentTimeMillis())}")
                    
                    // Event type
                    val eventDisplay = when (event) {
                        "ACTIVITY_STARTED" -> "🏃 STARTED"
                        "ACTIVITY_STOPPED" -> "⏹️  STOPPED"
                        else -> event
                    }
                    appendLog("Event: $eventDisplay")
                    
                    // Event time
                    try {
                        val eventTime = eventTimestamp.toLong() * 1000
                        appendLog("Event Time: ${formatTimestamp(eventTime)}")
                    } catch (e: Exception) {
                        appendLog("Event Time: $eventTimestamp")
                    }
                    
                    // Activity
                    appendLog("Activity: $activity")
                    
                    // Duration (formatted as HH:MM:SS)
                    try {
                        val dur = duration.toInt()
                        appendLog("Duration: ${formatDuration(dur)}")
                    } catch (e: Exception) {
                        appendLog("Duration: $duration")
                    }
                    
                    appendLog("━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                }
            }
            
            "terminating" -> {
                // Format: terminating|REASON
                if (parts.size >= 2) {
                    val reason = parts[1]
                    appendLog("[${ts()}] ━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                    appendLog("[${ts()}] ⚠️ TERMINATING")
                    appendLog("[${ts()}] Reason: $reason")
                    appendLog("[${ts()}] ━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
                }
            }
            
            else -> {
                // Unknown format - show raw
                appendLog("[${ts()}] Unknown: $message")
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
        try {
            unregisterReceiver(messageReceiver)
        } catch (e: Exception) {
            // Already unregistered
        }
    }
}