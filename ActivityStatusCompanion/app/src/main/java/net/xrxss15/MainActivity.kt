package net.xrxss15

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.garmin.android.connectiq.IQDevice
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MainActivity - Passive Message Listener UI
 * 
 * Displays incoming messages from CIQ app in real-time.
 * Message format: EVENT|TIMESTAMP|ACTIVITY|DURATION
 */
class MainActivity : Activity() {

    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var devicesSpinner: Spinner
    private lateinit var refreshBtn: Button
    private lateinit var initBtn: Button
    private lateinit var copyBtn: Button
    private lateinit var clearBtn: Button

    private val handler = Handler(Looper.getMainLooper())
    private val connectIQService = ConnectIQService.getInstance()
    private var devices: List<IQDevice> = emptyList()

    private fun ts(): String = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
    
    /**
     * Formats Unix timestamp to dd.MM.yyyy HH:mm:ss
     */
    private fun formatTimestamp(timestampMillis: Long): String {
        val date = Date(timestampMillis)
        return SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()).format(date)
    }
    
    /**
     * Formats duration in seconds to readable format
     */
    private fun formatDuration(seconds: Int): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        
        return when {
            hours > 0 -> String.format("%dh %02dm %02ds", hours, minutes, secs)
            minutes > 0 -> String.format("%dm %02ds", minutes, secs)
            else -> String.format("%ds", secs)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)

            val header = TextView(this@MainActivity).apply {
                text = "Activity Timer Companion\nPassive Listener"
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            addView(header)

            val row1 = LinearLayout(this@MainActivity).apply { 
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 8, 0, 8)
            }
            initBtn = Button(this@MainActivity).apply { 
                text = "Initialize"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            refreshBtn = Button(this@MainActivity).apply { 
                text = "Refresh"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row1.addView(initBtn); row1.addView(refreshBtn)
            addView(row1)

            val row2 = LinearLayout(this@MainActivity).apply { 
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, 0, 0, 8)
            }
            copyBtn = Button(this@MainActivity).apply { 
                text = "Copy Log"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            clearBtn = Button(this@MainActivity).apply { 
                text = "Clear Log"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row2.addView(copyBtn); row2.addView(clearBtn)
            addView(row2)

            devicesSpinner = Spinner(this@MainActivity)
            addView(devicesSpinner)

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

        connectIQService.registerLogSink { line -> appendLog(line) }
        
        // Register message callback with proper parsing
        connectIQService.setMessageCallback { payload, deviceName, timestampMillis ->
            appendLog("━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLog("📱 MESSAGE RECEIVED")
            appendLog("Device: $deviceName")
            appendLog("Received: ${formatTimestamp(timestampMillis)}")
            appendLog("")
            
            // Parse message: EVENT|TIMESTAMP|ACTIVITY|DURATION
            val parts = payload.split("|")
            if (parts.size >= 4) {
                val event = parts[0]
                val eventTimestamp = parts[1]
                val activity = parts[2]
                val duration = parts[3]
                
                // Display event type
                val eventDisplay = when (event) {
                    "ACTIVITY_STARTED" -> "🏃 ACTIVITY STARTED"
                    "ACTIVITY_STOPPED" -> "⏹️  ACTIVITY STOPPED"
                    else -> event
                }
                appendLog(eventDisplay)
                
                // Convert and display event timestamp
                try {
                    val eventTime = eventTimestamp.toLong() * 1000
                    appendLog("Event Time: ${formatTimestamp(eventTime)}")
                } catch (e: Exception) {
                    appendLog("Event Time: $eventTimestamp")
                }
                
                // Display activity type
                appendLog("Activity: $activity")
                
                // Display duration (formatted for STOP events)
                if (event == "ACTIVITY_STOPPED") {
                    try {
                        val durationSeconds = duration.toInt()
                        appendLog("Duration: ${formatDuration(durationSeconds)}")
                    } catch (e: Exception) {
                        appendLog("Duration: $duration")
                    }
                } else {
                    appendLog("Duration: $duration")
                }
            } else {
                appendLog("Raw message: $payload")
            }
            
            appendLog("━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        }

        if (!hasRequiredPermissions()) {
            requestRequiredPermissions()
        }

        initBtn.setOnClickListener {
            Thread {
                val ok = connectIQService.initializeForWorker(this@MainActivity)
                if (ok) handler.post { reloadDevices() }
            }.start()
        }

        refreshBtn.setOnClickListener { 
            Thread {
                connectIQService.refreshListeners()
                reloadDevices()
            }.start()
        }

        copyBtn.setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("log", logView.text))
            Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show()
        }

        clearBtn.setOnClickListener { 
            logView.text = ""
            appendLog("Log cleared at ${ts()}")
        }

        appendLog("━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        appendLog("Activity Timer Companion")
        appendLog("PASSIVE LISTENER MODE")
        appendLog("━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        appendLog("Waiting for activity events...")
        appendLog("")
    }

    private fun reloadDevices() {
        Thread {
            val ds = connectIQService.getConnectedRealDevices()
            val labels = if (ds.isEmpty()) {
                listOf("No devices found")
            } else {
                ds.map { "${it.friendlyName} (${it.deviceIdentifier})" }
            }
            
            handler.post {
                devices = ds
                val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).apply {
                    setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                }
                devicesSpinner.adapter = adapter
                
                if (devices.isEmpty()) {
                    appendLog("[${ts()}] ⚠️ No devices connected")
                } else {
                    appendLog("[${ts()}] ✅ ${devices.size} device(s) found:")
                    devices.forEach { device ->
                        appendLog("  • ${device.friendlyName}")
                    }
                }
            }
        }.start()
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
            
            if (allGranted) {
                Thread {
                    Thread.sleep(500)
                    val ok = connectIQService.initializeForWorker(this@MainActivity)
                    if (ok) handler.post { reloadDevices() }
                }.start()
            }
        }
    }
}