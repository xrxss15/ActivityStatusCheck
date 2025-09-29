package net.xrxss15

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.garmin.android.connectiq.IQDevice

class MainActivity : Activity() {

    companion object {
        private const val TAG = "ActStatus"
        private const val PERMISSION_REQUEST_CODE = 100
    }

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "[MAIN] onCreate")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        val header = TextView(this).apply {
            text = "Activity Status Companion (BLE)"
            textSize = 20f
        }
        root.addView(header)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        initBtn = Button(this).apply { text = "Initialize" }
        refreshBtn = Button(this).apply { text = "Refresh Devices" }
        queryBtn = Button(this).apply { text = "Query Status" }
        copyBtn = Button(this).apply { text = "Copy Log" }
        clearBtn = Button(this).apply { text = "Clear Log" }
        row.addView(initBtn); row.addView(refreshBtn); row.addView(queryBtn); row.addView(copyBtn); row.addView(clearBtn)
        root.addView(row)

        devicesSpinner = Spinner(this)
        root.addView(devicesSpinner)

        scroll = ScrollView(this)
        logView = TextView(this).apply {
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        scroll.addView(logView)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        setContentView(root)

        connectIQService.registerLogSink { appendLog(it) }

        if (!hasRequiredPermissions()) {
            requestRequiredPermissions()
        } else {
            appendLog("[MAIN] Permissions OK")
        }

        // Initialize runs off-UI, but reloadDevices is posted to UI thread
        initBtn.setOnClickListener {
            Thread {
                appendLog("[MAIN] Initializing CIQ (UI)")
                val ok = initWithUi()
                appendLog("[MAIN] Initialized=$ok")
                if (ok) handler.post { reloadDevices() }
            }.start()
        }

        refreshBtn.setOnClickListener { reloadDevices() }

        queryBtn.setOnClickListener {
            Thread {
                appendLog("[MAIN] Query start")
                val selected = devices.getOrNull(devicesSpinner.selectedItemPosition)
                val res = connectIQService.queryActivityStatus(
                    context = this,
                    selected = selected,
                    showUiIfInitNeeded = true
                )
                appendLog("[QUERY] success=${res.success} payload='${res.payload}'")
                appendLog("[QUERY] debug:\n${res.debug.trim()}")
            }.start()
        }

        copyBtn.setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("ActStatus log", logView.text))
            Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show()
        }

        clearBtn.setOnClickListener { logView.text = "" }

        appendLog("Activity Status Companion ready")
        appendLog("App UUID: 7b408c6e-fc9c-4080-bad4-97a3557fc995")

        // No auto-init on main to avoid main-thread blocking
    }

    private fun initWithUi(): Boolean {
        return try {
            connectIQService.queryActivityStatus(this, null, true)
            true
        } catch (e: Exception) {
            appendLog("[MAIN] init failed: ${e.message}")
            false
        }
    }

    // Safe reload: fetch on worker, update UI on main
    private fun reloadDevices() {
        Thread {
            val ds = connectIQService.getConnectedRealDevices(this)
            val labels = ds.map { "${it.friendlyName} (${it.deviceIdentifier})" }
            handler.post {
                devices = ds
                val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).apply {
                    setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                }
                devicesSpinner.adapter = adapter
                if (devices.isEmpty()) {
                    appendLog("[MAIN] No connected devices detected")
                } else {
                    appendLog("[MAIN] Devices: ${labels.joinToString()}")
                }
            }
        }.start()
    }

    private fun appendLog(line: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        handler.post {
            logView.append("[$ts] $line\n")
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
        Log.d(TAG, line)
    }

    private fun hasRequiredPermissions(): Boolean {
        val needs = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            needs.add(Manifest.permission.BLUETOOTH_SCAN)
            needs.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        return needs.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestRequiredPermissions() {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), PERMISSION_REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                appendLog("[MAIN] Permissions granted")
            } else {
                appendLog("[MAIN] Permissions denied")
                Toast.makeText(this, "Location/Bluetooth permission required", Toast.LENGTH_LONG).show()
            }
        }
    }
}