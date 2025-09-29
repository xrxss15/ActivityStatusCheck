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

        // GUI-only logging
        connectIQService.registerLogSink { line -> appendLog(line) }

        if (!hasRequiredPermissions()) {
            requestRequiredPermissions()
        } else {
            appendLog("[${ts()}] Permissions OK")
        }

        initBtn.setOnClickListener {
            Thread {
                appendLog("[${ts()}] Initializing CIQ (UI)")
                val ok = initWithUi()
                appendLog("[${ts()}] Initialized=$ok")
                if (ok) handler.post { reloadDevices() }
            }.start()
        }

        refreshBtn.setOnClickListener { reloadDevices() }

        queryBtn.setOnClickListener {
            Thread {
                appendLog("[${ts()}] Query start")
                val selected = devices.getOrNull(devicesSpinner.selectedItemPosition)
                val res = connectIQService.queryActivityStatus(
                    context = this,
                    selected = selected,
                    showUiIfInitNeeded = true
                )
                appendLog("[${ts()}] [QUERY-RESULT] success=${res.success} payload='${res.payload}'")
                appendLog("[${ts()}] [QUERY-DEBUG]\n${res.debug.trim()}")
            }.start()
        }

        copyBtn.setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("ActStatus log", logView.text))
            Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show()
        }

        clearBtn.setOnClickListener {
            handler.post { logView.text = "" }
        }

        appendLog("[${ts()}] Activity Status Companion ready")
        appendLog("[${ts()}] App UUID: 7b408c6e-fc9c-4080-bad4-97a3557fc995")
    }

    private fun initWithUi(): Boolean {
        return try {
            connectIQService.queryActivityStatus(this, null, true)
            true
        } catch (e: Exception) {
            appendLog("[${ts()}] init failed: ${e.message}")
            false
        }
    }

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
                    appendLog("[${ts()}] No connected devices detected")
                } else {
                    appendLog("[${ts()}] Devices: ${labels.joinToString()}")
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
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), 100)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                appendLog("[${ts()}] Permissions granted")
            } else {
                appendLog("[${ts()}] Permissions denied")
                Toast.makeText(this, "Location/Bluetooth permission required", Toast.LENGTH_LONG).show()
            }
        }
    }
}