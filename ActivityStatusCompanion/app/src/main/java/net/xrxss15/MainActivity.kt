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
 * MAIN UI ACTIVITY WITH IMPROVED LOGGING
 * 
 * Provides manual testing interface for ConnectIQ functionality.
 * Enhanced with clearer, more readable log statements and intent action logging.
 * GUI layout preserved from original implementation.
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

    private fun ts(): String = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep original GUI layout exactly as is
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)

            val header = TextView(this@MainActivity).apply {
                text = "Activity Status Companion (Tasker Integration)"
                textSize = 20f
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
                setTextColor(0xFF00FF00.toInt()) // Green text on dark background for better readability
            }
            scroll.addView(logView)
            addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)

        connectIQService.registerLogSink { line -> appendLog(line) }

        if (!hasRequiredPermissions()) {
            logInfo("STARTUP", "Requesting required permissions...")
            requestRequiredPermissions()
        } else {
            logInfo("STARTUP", "All required permissions already granted ✓")
        }

        // Button listeners with improved logging
        initBtn.setOnClickListener {
            logInfo("USER_ACTION", "Initialize button clicked - starting manual initialization")
            Thread {
                logInfo("INIT", "Beginning ConnectIQ initialization with UI...")
                val ok = initWithUi()
                if (ok) {
                    logSuccess("INIT", "ConnectIQ initialization completed successfully")
                    handler.post { 
                        logInfo("DEVICES", "Auto-refreshing device list after successful init")
                        reloadDevices() 
                    }
                } else {
                    logError("INIT", "ConnectIQ initialization failed")
                }
            }.start()
        }

        refreshBtn.setOnClickListener { 
            logInfo("USER_ACTION", "Refresh Devices button clicked")
            reloadDevices() 
        }

        queryBtn.setOnClickListener {
            logInfo("USER_ACTION", "Query Status button clicked")
            Thread {
                val selected = devices.getOrNull(devicesSpinner.selectedItemPosition)
                val deviceName = selected?.friendlyName ?: "auto-select"
                
                logInfo("QUERY", "Starting activity status query for device: $deviceName")
                val res = connectIQService.queryActivityStatus(
                    context = this@MainActivity,
                    selected = selected,
                    showUiIfInitNeeded = true
                )
                
                if (res.success) {
                    logSuccess("QUERY", "Query completed - Payload: '${res.payload}'")
                } else {
                    logError("QUERY", "Query failed - Payload: '${res.payload}'")
                }
                logDebug("QUERY_DETAILS", "\n${res.debug.trim()}")
            }.start()
        }

        copyBtn.setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("ActStatus log", logView.text))
            Toast.makeText(this, "Log copied to clipboard", Toast.LENGTH_SHORT).show()
            logInfo("USER_ACTION", "Log copied to clipboard")
        }

        clearBtn.setOnClickListener {
            handler.post { 
                logView.text = "" 
                logInfo("USER_ACTION", "Log cleared by user")
            }
        }

        // Startup messages with clear formatting
        logInfo("STARTUP", "═══════════════════════════════════════")
        logInfo("STARTUP", "Activity Status Companion READY")
        logInfo("STARTUP", "═══════════════════════════════════════")
        logInfo("CONFIG", "Target CIQ App UUID: 7b408c6e-fc9c-4080-bad4-97a3557fc995")
        logInfo("CONFIG", "Tasker Trigger Intent: net.xrxss15.ACTIVITY_STATUS_TRIGGER")
        logInfo("CONFIG", "Device List Intent: net.xrxss15.DEVICE_LIST")  
        logInfo("CONFIG", "Response Intent: net.xrxss15.CIQ_RESPONSE")
        logInfo("STARTUP", "═══════════════════════════════════════")
    }

    private fun initWithUi(): Boolean {
        return try {
            connectIQService.queryActivityStatus(this, null, true)
            true
        } catch (e: Exception) {
            logError("INIT", "Initialization failed: ${e.message}")
            false
        }
    }

    private fun reloadDevices() {
        Thread {
            logInfo("DEVICES", "Scanning for connected ConnectIQ devices...")
            val ds = connectIQService.getConnectedRealDevices()
            val labels = ds.map { "${it.friendlyName} (${it.deviceIdentifier})" }
            
            handler.post {
                devices = ds
                val adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, labels).apply {
                    setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                }
                devicesSpinner.adapter = adapter
                
                if (devices.isEmpty()) {
                    logWarning("DEVICES", "No connected real devices found - check Bluetooth connection")
                } else {
                    logSuccess("DEVICES", "Found ${devices.size} connected device(s):")
                    devices.forEach { device ->
                        logInfo("DEVICE_DETAIL", "  • ${device.friendlyName} (ID: ${device.deviceIdentifier})")
                    }
                }
            }
        }.start()
    }

    // Enhanced logging methods with clear categories and formatting
    private fun appendLog(line: String) {
        handler.post {
            logView.append("$line\n")
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun logInfo(category: String, message: String) {
        appendLog("[${ts()}] ℹ️ [$category] $message")
    }

    private fun logSuccess(category: String, message: String) {
        appendLog("[${ts()}] ✅ [$category] $message")
    }

    private fun logWarning(category: String, message: String) {
        appendLog("[${ts()}] ⚠️ [$category] $message")
    }

    private fun logError(category: String, message: String) {
        appendLog("[${ts()}] ❌ [$category] $message")
    }

    private fun logDebug(category: String, message: String) {
        appendLog("[${ts()}] 🔍 [$category] $message")
    }

    private fun hasRequiredPermissions(): Boolean {
        val needs = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            needs.add(Manifest.permission.BLUETOOTH_SCAN)
            needs.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        
        val granted = needs.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        
        if (!granted) {
            logWarning("PERMISSIONS", "Missing permissions: ${needs.filter { 
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED 
            }}")
        }
        
        return granted
    }

    private fun requestRequiredPermissions() {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        logInfo("PERMISSIONS", "Requesting permissions: ${perms.joinToString(", ")}")
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), 100)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                logSuccess("PERMISSIONS", "All permissions granted by user")
            } else {
                logError("PERMISSIONS", "Some permissions denied by user")
                val denied = permissions.filterIndexed { index, _ -> 
                    grantResults[index] != PackageManager.PERMISSION_GRANTED 
                }
                logError("PERMISSIONS", "Denied permissions: ${denied.joinToString(", ")}")
                Toast.makeText(this, "Location/Bluetooth permissions required for ConnectIQ", Toast.LENGTH_LONG).show()
            }
        }
    }
}