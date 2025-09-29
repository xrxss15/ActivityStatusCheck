package net.xrxss15

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
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
    private lateinit var shutdownBtn: Button
    private lateinit var queryBtn: Button
    private lateinit var clearBtn: Button
    private lateinit var copyBtn: Button
    private lateinit var permsView: TextView
    private val handler = Handler(Looper.getMainLooper())

    private val connectIQService = ConnectIQService.getInstance()
    private var devices: List<IQDevice> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "[MAIN] onCreate")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        val header = TextView(this).apply {
            text = "Activity Status Companion (BLE)"
            textSize = 20f
            gravity = Gravity.CENTER_HORIZONTAL
        }
        root.addView(header, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        permsView = TextView(this).apply { textSize = 12f }
        root.addView(permsView)

        val row1 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        initBtn = Button(this).apply { text = "Initialize" }
        shutdownBtn = Button(this).apply { text = "Shutdown" }
        refreshBtn = Button(this).apply { text = "Refresh Devices" }
        row1.addView(initBtn)
        row1.addView(shutdownBtn)
        row1.addView(refreshBtn)
        root.addView(row1)

        devicesSpinner = Spinner(this)
        root.addView(devicesSpinner)

        val row2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        queryBtn = Button(this).apply { text = "Query Activity Status" }
        clearBtn = Button(this).apply { text = "Clear Log" }
        copyBtn = Button(this).apply { text = "Copy Log" }
        row2.addView(queryBtn)
        row2.addView(clearBtn)
        row2.addView(copyBtn)
        root.addView(row2)

        scroll = ScrollView(this)
        logView = TextView(this).apply {
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        scroll.addView(logView)
        root.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0, 1f
        ))

        setContentView(root)

        connectIQService.registerLogSink { line -> appendLog(line) }

        if (!hasRequiredPermissions()) {
            requestRequiredPermissions()
        } else {
            updatePermsUi(granted = true)
        }

        initBtn.setOnClickListener {
            val ok = connectIQService.ensureInitialized(this, showUi = true)
            appendLog("[MAIN] ensureInitialized(showUi=true) -> $ok")
            if (ok) reloadDevices()
        }
        shutdownBtn.setOnClickListener {
            connectIQService.shutdown(applicationContext)
            appendLog("[MAIN] shutdown() requested")
            reloadDevices()
        }
        refreshBtn.setOnClickListener { reloadDevices() }

        clearBtn.setOnClickListener { logView.text = "" }
        copyBtn.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("ActStatus log", logView.text))
            Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show()
        }

        queryBtn.setOnClickListener {
            Thread {
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

        handler.post {
            val ok = connectIQService.ensureInitialized(this, showUi = true)
            appendLog("[MAIN] auto-init -> $ok")
            reloadDevices()
        }
    }

    private fun reloadDevices() {
        devices = connectIQService.getConnectedRealDevices(this)
        val labels = devices.map { "${it.friendlyName} (${it.deviceIdentifier})" }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        devicesSpinner.adapter = adapter
        appendLog("[MAIN] devices real-connected=${devices.size}")
    }

    private fun appendLog(line: String) {
        handler.post {
            val ts = String.format("%tT", System.currentTimeMillis())
            logView.append("[$ts] $line\n")
            scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
        }
        Log.d(TAG, line)
    }

    private fun hasRequiredPermissions(): Boolean {
        val needs = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 31) {
            needs.add(Manifest.permission.BLUETOOTH_SCAN)
            needs.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        return needs.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestRequiredPermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= 31) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), PERMISSION_REQUEST_CODE)
        updatePermsUi(granted = false)
    }

    private fun updatePermsUi(granted: Boolean) {
        permsView.text = if (granted) {
            "Permissions OK (Location, Bluetooth)"
        } else {
            "Permissions missing: Location and Bluetooth (grant to use BLE messaging)"
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val allGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            updatePermsUi(granted = allGranted)
            appendLog("[PERMS] onRequestPermissionsResult allGranted=$allGranted")
        }
    }
}