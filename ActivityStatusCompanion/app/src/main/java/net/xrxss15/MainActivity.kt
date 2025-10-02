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

            val header = TextView(this@MainActivity).apply {
                text = "Activity Status Companion\nDebug Mode"
                textSize = 16f
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            addView(header)

            val row1 = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
            initBtn = Button(this@MainActivity).apply { 
                text = "Init"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            refreshBtn = Button(this@MainActivity).apply { 
                text = "Devices"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            queryBtn = Button(this@MainActivity).apply { 
                text = "Query"
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row1.addView(initBtn); row1.addView(refreshBtn); row1.addView(queryBtn)
            addView(row1)

            val row2 = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL }
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

            devicesSpinner = Spinner(this@MainActivity)
            addView(devicesSpinner)

            scroll = ScrollView(this@MainActivity)
            logView = TextView(this@MainActivity).apply {
                textSize = 10f
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(8, 8, 8, 8)
                setBackgroundColor(0xFF1E1E1E.toInt())
                setTextColor(0xFF00FF00.toInt())
            }
            scroll.addView(logView)
            addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)

        connectIQService.registerLogSink { line -> appendLog(line) }
        
        connectIQService.setResponseCallback { response, deviceName, timestamp ->
            appendLog("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
            appendLog("[$timestamp] 📱 RESPONSE")
            appendLog("Device: $deviceName")
            appendLog("Data: $response")
            
            val parts = response.split("|")
            if (parts.size >= 3) {
                appendLog("  Status: ${parts[0]}")
                appendLog("  Time: ${parts[1]}")
                appendLog("  Count: ${parts[2]}")
            }
            appendLog("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
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
            reloadDevices() 
        }

        queryBtn.setOnClickListener {
            Thread {
                val selected = devices.getOrNull(devicesSpinner.selectedItemPosition)
                connectIQService.queryActivityStatus(this@MainActivity, selected, true)
            }.start()
        }

        copyBtn.setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("log", logView.text))
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        }

        clearBtn.setOnClickListener { 
            logView.text = "" 
        }

        appendLog("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        appendLog("Activity Status Companion")
        appendLog("DEBUG MODE")
        appendLog("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        appendLog("Tasker:")
        appendLog("  Trigger: ACTIVITY_STATUS_TRIGGER")
        appendLog("  Response: ACTIVITY_STATUS_RESPONSE")
        appendLog("  Timeout: 5 min (AlarmManager)")
        appendLog("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
    }

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
                    appendLog("[${ts()}] ⚠️ No devices")
                } else {
                    appendLog("[${ts()}] ✅ ${devices.size} device(s):")
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
            appendLog("[${ts()}] ${if (allGranted) "✅ Granted" else "❌ Denied"}")
        }
    }
}