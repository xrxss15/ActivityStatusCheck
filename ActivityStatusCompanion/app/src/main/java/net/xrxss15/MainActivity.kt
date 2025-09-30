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
 * MainActivity - Debug Mode UI for Manual Testing
 * 
 * This activity provides a GUI for manual testing and debugging of the ConnectIQ integration.
 * It is only used when the app is launched directly (not via Tasker intent).
 * 
 * Features:
 * - Manual ConnectIQ initialization
 * - Device discovery and refresh
 * - Manual status queries
 * - Real-time log display with copy/clear
 * 
 * Note: When app is triggered via Tasker intent, MainActivity is NOT launched.
 * The app operates in headless mode via WorkManager.
 * 
 * UI Components:
 * - Initialize button: Manually initializes ConnectIQ SDK
 * - Refresh Devices button: Scans for connected devices
 * - Query Status button: Sends status query to selected device
 * - Copy Log button: Copies log to clipboard
 * - Clear Log button: Clears the log display
 * - Device spinner: Dropdown to select target device
 * - Log view: Scrollable log with monospace font
 * 
 * @see ConnectIQQueryWorker for headless mode implementation
 * @see ActivityStatusCheckReceiver for Tasker integration
 */
class MainActivity : Activity() {

    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var devicesSpinner: Spinner
    private lateinit var refreshBtn: Button
    private lateinit var initBtn: Button
    private lateinit var queryBtn: Button
    private lateinit var copyBtn: Button
    private lateinit var clearBtn: Button

    private val handler = Handler(Looper.getMainLooper())
    private val connectIQService = ConnectIQService.getInstance()
    private var devices: List<IQDevice> = emptyList()

    /**
     * Formats current timestamp for logging.
     */
    private fun ts(): String = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())

    /**
     * Creates the activity and sets up UI components.
     * 
     * This method:
     * 1. Creates UI layout programmatically
     * 2. Registers log sink for service logging
     * 3. Checks and requests permissions
     * 4. Sets up button click listeners
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Create UI layout programmatically (same as original)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)

            val header = TextView(this@MainActivity).apply {
                text = "Activity Status Companion\n(Debug Mode - Dual Operation)"
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            addView(header)

            val row1 = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            initBtn = Button(this@MainActivity).apply { 
                text = "Initialize"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            refreshBtn = Button(this@MainActivity).apply { 
                text = "Refresh Devices"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            queryBtn = Button(this@MainActivity).apply { 
                text = "Query Status"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row1.addView(initBtn); row1.addView(refreshBtn); row1.addView(queryBtn)
            addView(row1)

            val row2 = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
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
                textSize = 11f
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(8, 8, 8, 8)
                setBackgroundColor(0xFF1E1E1E.toInt())
                setTextColor(0xFF00FF00.toInt())
            }
            scroll.addView(logView)
            addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)

        // Register log sink to display service logs in UI
        connectIQService.registerLogSink { line -> appendLog(line) }

        // Check permissions
        if (!hasRequiredPermissions()) {
            requestRequiredPermissions()
        }

        // Button click listeners
        initBtn.setOnClickListener {
            Thread {
                appendLog("[${ts()}] Manual initialization started...")
                val ok = connectIQService.initializeForWorker(this@MainActivity)
                appendLog("[${ts()}] Manual initialization: ${if (ok) "✅ SUCCESS" else "❌ FAILED"}")
                if (ok) handler.post { reloadDevices() }
            }.start()
        }

        refreshBtn.setOnClickListener { 
            appendLog("[${ts()}] Refreshing device list...")
            reloadDevices() 
        }

        queryBtn.setOnClickListener {
            Thread {
                val selected = devices.getOrNull(devicesSpinner.selectedItemPosition)
                val deviceName = selected?.friendlyName ?: "auto-select"
                appendLog("[${ts()}] Querying device: $deviceName")
                
                val res = connectIQService.queryActivityStatus(this@MainActivity, selected, true)
                appendLog("[${ts()}] Query result: ${if (res.success) "✅ SUCCESS" else "❌ FAILED"}")
                appendLog("[${ts()}] Payload: ${res.payload}")
                if (res.debug.isNotEmpty()) {
                    appendLog("[${ts()}] Debug:\n${res.debug}")
                }
            }.start()
        }

        copyBtn.setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("ActStatus log", logView.text))
            Toast.makeText(this, "Log copied to clipboard", Toast.LENGTH_SHORT).show()
            appendLog("[${ts()}] Log copied to clipboard")
        }

        clearBtn.setOnClickListener { 
            logView.text = "" 
            appendLog("[${ts()}] Log cleared")
        }

        // Startup messages
        appendLog("═══════════════════════════════════════")
        appendLog("Activity Status Companion - DEBUG MODE")
        appendLog("═══════════════════════════════════════")
        appendLog("Operating Modes:")
        appendLog("  • DEBUG MODE (this GUI) - manual testing")
        appendLog("  • HEADLESS MODE - Tasker trigger (no GUI)")
        appendLog("")
        appendLog("Tasker Integration:")
        appendLog("  Trigger: net.xrxss15.ACTIVITY_STATUS_TRIGGER")
        appendLog("  Response: net.xrxss15.ACTIVITY_STATUS_RESPONSE")
        appendLog("  Timeout: 5 minutes (AlarmManager)")
        appendLog("  Termination: System.exit(0)")
        appendLog("═══════════════════════════════════════")
    }

    /**
     * Reloads device list and updates spinner.
     */
    private fun reloadDevices() {
        Thread {
            val ds = connectIQService.getConnectedRealDevices()
            val labels = ds.map { "${it.friendlyName} (${it.deviceIdentifier})" }
            
            handler.post {
                devices = ds
                val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).apply {
                    setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                }
                devicesSpinner.adapter = adapter
                
                if (devices.isEmpty()) {
                    appendLog("[${ts()}] ⚠️ No devices found")
                } else {
                    appendLog("[${ts()}] ✅ Found ${devices.size} device(s):")
                    devices.forEach { device ->
                        appendLog("[${ts()}]   • ${device.friendlyName}")
                    }
                }
            }
        }.start()
    }

    /**
     * Appends log line to UI.
     */
    private fun appendLog(line: String) {
        handler.post {
            logView.append("$line\n")
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    /**
     * Checks if all required permissions are granted.
     */
    private fun hasRequiredPermissions(): Boolean {
        val needs = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            needs.add(Manifest.permission.BLUETOOTH_SCAN)
            needs.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        return needs.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    /**
     * Requests required permissions.
     */
    private fun requestRequiredPermissions() {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), 100)
        appendLog("[${ts()}] Requesting permissions: ${perms.joinToString(", ")}")
    }

    /**
     * Handles permission request results.
     */
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            appendLog("[${ts()}] Permissions ${if (allGranted) "✅ GRANTED" else "❌ DENIED"}")
            if (!allGranted) {
                Toast.makeText(this, "Permissions required for ConnectIQ", Toast.LENGTH_LONG).show()
            }
        }
    }
}