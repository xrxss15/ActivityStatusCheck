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
import androidx.core.content.ContextCompat
import com.garmin.android.connectiq.ConnectIQ
import com.garmin.android.connectiq.ConnectIQ.ConnectIQListener
import com.garmin.android.connectiq.ConnectIQ.IQConnectType
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice

class MainActivity : Activity() {

    companion object {
        private const val TAG = "ActStatusMain"
        private const val GC_PACKAGE = "com.garmin.android.apps.connectmobile"
        private const val SIMULATOR_ID: Long = 12345L
        private const val PERMISSION_REQUEST_CODE = 100
        private const val APP_UUID = "5cd85684-4b48-419b-b63a-a2065368ae1e"
    }

    private lateinit var logView: TextView
    private var ciq: ConnectIQ? = null
    private var myApp: IQApp? = null
    private var myDevice: IQDevice? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "MainActivity onCreate")

        if (!hasRequiredPermissions()) {
            Log.w(TAG, "Required permissions not granted, requesting permissions")
            requestRequiredPermissions()
            return
        }

        Log.d(TAG, "All required permissions granted")
        setupUI()
        initializeConnectIQ()
    }

    private fun hasRequiredPermissions(): Boolean {
        val requiredPermissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requiredPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
            requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        val missingPermissions = requiredPermissions.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            Log.w(TAG, "Missing permissions: ${missingPermissions.joinToString()}")
            return false
        }

        Log.d(TAG, "All permissions granted")
        return true
    }

    private fun requestRequiredPermissions() {
        val requiredPermissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requiredPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
            requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        Log.d(TAG, "Requesting ${requiredPermissions.size} permissions")
        ActivityCompat.requestPermissions(
            this,
            requiredPermissions.toTypedArray(),
            PERMISSION_REQUEST_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        
        if (requestCode == PERMISSION_REQUEST_CODE) {
            val granted = grantResults.count { it == PackageManager.PERMISSION_GRANTED }
            val total = grantResults.size
            
            Log.d(TAG, "Permission result: $granted/$total granted")
            
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Log.d(TAG, "All permissions granted by user")
                setupUI()
                initializeConnectIQ()
            } else {
                Log.e(TAG, "Not all permissions granted by user")
                Toast.makeText(this, "Bluetooth permissions required for device discovery", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun setupUI() {
        Log.d(TAG, "Setting up UI")
        
        val mainLayout = LinearLayout(this)
        mainLayout.orientation = LinearLayout.VERTICAL
        mainLayout.setPadding(32, 32, 32, 32)

        val scrollView = ScrollView(this)
        logView = TextView(this)
        logView.textSize = 12f
        logView.typeface = android.graphics.Typeface.MONOSPACE
        scrollView.addView(logView)

        val buttonLayout = LinearLayout(this)
        buttonLayout.orientation = LinearLayout.HORIZONTAL
        buttonLayout.setPadding(0, 16, 0, 0)

        val scanButton = Button(this)
        scanButton.text = "Scan Devices"
        scanButton.setOnClickListener { 
            Log.d(TAG, "Scan button clicked")
            scanForDevices() 
        }

        val sendButton = Button(this)
        sendButton.text = "Send Query"
        sendButton.setOnClickListener { 
            Log.d(TAG, "Send button clicked")
            sendStatusQuery() 
        }

        buttonLayout.addView(scanButton)
        buttonLayout.addView(sendButton)

        val scrollLayoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 
            0, 
            1f
        )
        mainLayout.addView(scrollView, scrollLayoutParams)
        mainLayout.addView(buttonLayout)

        setContentView(mainLayout)
        
        logUI("Activity Status Companion started")
        logUI("SDK Version: 2.3.0")
        logUI("App UUID: $APP_UUID")
    }

    private fun initializeConnectIQ() {
        Log.d(TAG, "Initializing ConnectIQ SDK")
        logUI("Initializing ConnectIQ SDK...")
        
        try {
            ciq = ConnectIQ.getInstance(this, IQConnectType.TETHERED)
            myApp = IQApp(APP_UUID)
            
            Log.d(TAG, "ConnectIQ instance created, initializing with showUi=true")
            
            ciq!!.initialize(this, true, object : ConnectIQListener {
                override fun onSdkReady() {
                    Log.i(TAG, "ConnectIQ SDK ready")
                    logUI("ConnectIQ SDK ready")
                    scanForDevices()
                }

                override fun onInitializeError(errStatus: ConnectIQ.IQSdkErrorStatus) {
                    Log.e(TAG, "ConnectIQ init error: $errStatus")
                    logUI("ConnectIQ init error: $errStatus")
                    
                    when (errStatus) {
                        ConnectIQ.IQSdkErrorStatus.GCM_NOT_INSTALLED -> {
                            Log.w(TAG, "Garmin Connect Mobile not installed")
                            logUI("Garmin Connect Mobile not installed")
                            showInstallGarminConnectDialog()
                        }
                        ConnectIQ.IQSdkErrorStatus.GCM_UPGRADE_NEEDED -> {
                            Log.w(TAG, "Garmin Connect Mobile needs upgrade")
                            logUI("Garmin Connect Mobile needs upgrade")
                        }
                        else -> {
                            Log.e(TAG, "Other initialization error: $errStatus")
                            logUI("Other initialization error: $errStatus")
                        }
                    }
                }

                override fun onSdkShutDown() {
                    Log.d(TAG, "ConnectIQ SDK shutdown")
                    logUI("ConnectIQ SDK shutdown")
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize ConnectIQ", e)
            logUI("Failed to initialize ConnectIQ: ${e.message}")
        }
    }

    private fun scanForDevices() {
        val ciqInstance = ciq ?: run {
            Log.e(TAG, "ConnectIQ not initialized for device scan")
            logUI("ConnectIQ not initialized")
            return
        }

        Log.d(TAG, "Starting device scan")
        logUI("Scanning for devices...")

        try {
            val knownDevices = ciqInstance.knownDevices
            val connectedDevices = ciqInstance.connectedDevices

            Log.i(TAG, "Device scan results - Known: ${knownDevices.size}, Connected: ${connectedDevices.size}")
            logUI("Known devices: ${knownDevices.size}")
            logUI("Connected devices: ${connectedDevices.size}")

            if (knownDevices.isEmpty()) {
                Log.w(TAG, "No known devices found")
                logUI("No known devices found")
                logUI("Make sure:")
                logUI("  - Watch is paired with Garmin Connect")
                logUI("  - Watch app is installed via Connect IQ Store")
                logUI("  - Bluetooth permissions are granted")
                return
            }

            var realDeviceFound = false
            knownDevices.forEachIndexed { index, device ->
                val status = ciqInstance.getDeviceStatus(device)
                val isSimulator = device.deviceIdentifier == SIMULATOR_ID
                val deviceType = if (isSimulator) "SIMULATOR" else "REAL_DEVICE"
                
                Log.i(TAG, "Device $index: ${device.friendlyName}, Type: $deviceType, ID: ${device.deviceIdentifier}, Status: $status")
                logUI("Device: ${device.friendlyName}")
                logUI("  Type: $deviceType")
                logUI("  ID: ${device.deviceIdentifier}")
                logUI("  Status: $status")

                if (!isSimulator) {
                    realDeviceFound = true
                    if (status == IQDevice.IQDeviceStatus.CONNECTED) {
                        myDevice = device
                        Log.i(TAG, "Selected connected real device: ${device.friendlyName}")
                        logUI("Selected device: ${device.friendlyName}")
                    }
                }
            }

            if (!realDeviceFound) {
                Log.w(TAG, "No real devices found, only simulator available")
                logUI("WARNING: Only simulator found, no real devices")
                logUI("This indicates the watch app may not be properly installed")
            }

            if (myDevice == null && knownDevices.isNotEmpty()) {
                myDevice = knownDevices.find { it.deviceIdentifier != SIMULATOR_ID }
                    ?: knownDevices.firstOrNull()
                
                myDevice?.let {
                    Log.d(TAG, "Using fallback device: ${it.friendlyName}")
                    logUI("Using fallback device: ${it.friendlyName}")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error during device scan", e)
            logUI("Error scanning devices: ${e.message}")
        }
    }

    private fun sendStatusQuery() {
        val device = myDevice ?: run {
            Log.w(TAG, "No device selected for message send")
            logUI("No device selected")
            scanForDevices()
            return
        }

        val app = myApp ?: run {
            Log.e(TAG, "App not initialized")
            logUI("App not initialized")
            return
        }

        val ciqInstance = ciq ?: run {
            Log.e(TAG, "ConnectIQ not ready")
            logUI("ConnectIQ not ready")
            return
        }

        Log.d(TAG, "Sending status query to device: ${device.friendlyName}")
        logUI("Sending status query to ${device.friendlyName}...")

        try {
            ciqInstance.sendMessage(
                device,
                app,
                "status?",
                object : ConnectIQ.IQSendMessageListener {
                    override fun onMessageStatus(
                        device: IQDevice,
                        app: IQApp,
                        status: ConnectIQ.IQMessageStatus
                    ) {
                        Log.d(TAG, "Message send status: $status for device ${device.friendlyName}")
                        logUI("Message status: $status")
                        
                        when (status) {
                            ConnectIQ.IQMessageStatus.SUCCESS -> {
                                Log.i(TAG, "Message sent successfully")
                                logUI("Message sent successfully")
                            }
                            else -> {
                                Log.e(TAG, "Message send failed with status: $status")
                                logUI("Message failed: $status")
                            }
                        }
                    }
                }
            )

            Log.d(TAG, "Registering for app events from ${device.friendlyName}")
            ciqInstance.registerForAppEvents(device, app) { responseDevice, _, message, status ->
                Log.i(TAG, "Received message from ${responseDevice.friendlyName}, status: $status, message: $message")
                logUI("Received response: $message")
                logUI("Response status: $status")
                
                if (message is List<*>) {
                    val response = message.joinToString(", ")
                    Log.i(TAG, "Parsed activity status response: $response")
                    logUI("Activity status: $response")
                } else {
                    Log.i(TAG, "Raw response received: $message")
                    logUI("Raw response: $message")
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "Exception while sending message", e)
            logUI("Error sending message: ${e.message}")
        }
    }

    private fun showInstallGarminConnectDialog() {
        Log.d(TAG, "Attempting to show/launch Garmin Connect")
        try {
            val intent = packageManager.getLaunchIntentForPackage(GC_PACKAGE)
            if (intent != null) {
                Log.d(TAG, "Launching Garmin Connect app")
                startActivity(intent)
            } else {
                Log.d(TAG, "Garmin Connect not installed, redirecting to Play Store")
                val playStoreIntent = Intent(Intent.ACTION_VIEW).apply {
                    data = android.net.Uri.parse("market://details?id=$GC_PACKAGE")
                }
                startActivity(playStoreIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cannot open Garmin Connect", e)
            logUI("Cannot open Garmin Connect: ${e.message}")
        }
    }

    private fun logUI(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        
        handler.post {
            logView.append("[$timestamp] $message\n")
            
            val scrollView = logView.parent as? ScrollView
            scrollView?.post {
                scrollView.fullScroll(ScrollView.FOCUS_DOWN)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "MainActivity onDestroy")
        ciq?.shutdown(this)
    }
}