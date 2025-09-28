package net.xrxss15

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat

class MainActivity : Activity() {

    companion object {
        private const val TAG = "ActStatusMain"
        private const val PERMISSION_REQUEST_CODE = 100
    }

    private lateinit var logView: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val connectIQService = ConnectIQService.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "[MAIN] onCreate")
        
        if (!connectIQService.hasRequiredPermissions(this)) {
            Log.w(TAG, "[MAIN] Permissions missing, requesting")
            requestRequiredPermissions()
            return
        }
        
        setupUI()
        Log.d(TAG, "[MAIN] Setup complete")
    }

    private fun requestRequiredPermissions() {
        val required = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            required.add(Manifest.permission.BLUETOOTH_SCAN)
            required.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        Log.d(TAG, "[MAIN] Requesting ${required.size} permissions")
        ActivityCompat.requestPermissions(this, required.toTypedArray(), PERMISSION_REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val granted = grantResults.count { it == PackageManager.PERMISSION_GRANTED }
            Log.d(TAG, "[MAIN] Permission result: $granted/${grantResults.size} granted")
            
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                setupUI()
            } else {
                Toast.makeText(this, "Location/Bluetooth permission required", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun setupUI() {
        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        
        val scrollView = ScrollView(this)
        logView = TextView(this).apply {
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
        }
        scrollView.addView(logView)

        val queryButton = Button(this).apply {
            text = "Query Activity Status"
            setOnClickListener { 
                Log.d(TAG, "[MAIN] Query button clicked")
                queryActivityStatus() 
            }
        }

        val scrollParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        mainLayout.addView(scrollView, scrollParams)
        mainLayout.addView(queryButton)
        
        setContentView(mainLayout)
        logUI("Activity Status Companion v2.0")
        logUI("App UUID: 7b408c6e-fc9c-4080-bad4-97a3557fc995")
        logUI("Ready for testing - press button to query")
    }

    private fun queryActivityStatus() {
        logUI("=== STARTING QUERY ===")
        Log.i(TAG, "[MAIN] Starting status query via service")
        
        connectIQService.queryActivityStatus(
            context = this, 
            tag = TAG, 
            showUi = true, // GUI always uses UI mode
            callback = object : ConnectIQService.StatusQueryCallback {
                override fun onSuccess(payload: String, debug: String) {
                    Log.i(TAG, "[MAIN] Query SUCCESS: $payload")
                    logUI("=== SUCCESS ===")
                    logUI("Response: $payload")
                    logUI("Debug: $debug")
                }

                override fun onFailure(error: String, debug: String) {
                    Log.e(TAG, "[MAIN] Query FAILED: $error")
                    logUI("=== FAILED ===")
                    logUI("Error: $error")
                    logUI("Debug: $debug")
                }

                override fun onLog(tag: String, message: String, level: ConnectIQService.LogLevel) {
                    when (level) {
                        ConnectIQService.LogLevel.ERROR -> Log.e(tag, message)
                        ConnectIQService.LogLevel.WARN -> Log.w(tag, message)
                        ConnectIQService.LogLevel.INFO -> Log.i(tag, message)
                        ConnectIQService.LogLevel.DEBUG -> Log.d(tag, message)
                    }
                    
                    // Show important messages in UI
                    if (level == ConnectIQService.LogLevel.INFO || level == ConnectIQService.LogLevel.ERROR) {
                        logUI(message.removePrefix("[SERVICE] ").removePrefix("[CIQ-API] "))
                    }
                }
            }
        )
    }

    private fun logUI(message: String) {
        val ts = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        handler.post {
            logView.append("[$ts] $message\n")
            (logView.parent as? ScrollView)?.post { 
                (logView.parent as ScrollView).fullScroll(ScrollView.FOCUS_DOWN) 
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "[MAIN] onDestroy")
    }
}