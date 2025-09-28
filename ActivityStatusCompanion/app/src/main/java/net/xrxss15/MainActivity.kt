package net.xrxss15

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup.LayoutParams
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.garmin.android.connectiq.ConnectIQ
import com.garmin.android.connectiq.ConnectIQ.ConnectIQListener
import com.garmin.android.connectiq.ConnectIQ.IQConnectType
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice

class MainActivity : Activity() {

    companion object {
        private const val TAG = "ActStatusCheck"
        private const val GC_PACKAGE = "com.garmin.android.apps.connectmobile"
        private const val SIMULATOR_ID: Long = 12345L
    }

    private lateinit var logView: TextView
    private var ciq: ConnectIQ? = null
    private var app: IQApp? = null
    private var appId: String = "5cd85684-4b48-419b-b63a-a2065368ae1e"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }

        val btnInitUi = Button(this).apply {
            text = "Init SDK (with UI)"
            setOnClickListener { initSdk(true) }
        }
        val btnInitNoUi = Button(this).apply {
            text = "Init SDK (no UI)"
            setOnClickListener { initSdk(false) }
        }
        val btnDumpKnown = Button(this).apply {
            text = "Dump known devices"
            setOnClickListener { dumpKnown() }
        }
        val btnDumpConnected = Button(this).apply {
            text = "Dump connected devices"
            setOnClickListener { dumpConnected() }
        }
        val btnTriggerReceiver = Button(this).apply {
            text = "Trigger Receiver"
            setOnClickListener {
                Log.d(TAG, "Triggering receiver action")
                sendBroadcast(Intent(ActivityStatusCheckReceiver.ACTION_TRIGGER))
                toast("Broadcast sent")
                appendUi("Broadcast sent: ${ActivityStatusCheckReceiver.ACTION_TRIGGER}")
            }
        }
        val btnOpenGC = Button(this).apply {
            text = "Open Garmin Connect"
            setOnClickListener { openGarminConnect() }
        }
        val btnClear = Button(this).apply {
            text = "Clear logs"
            setOnClickListener { logView.text = "" }
        }

        logView = TextView(this).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        val scroll = ScrollView(this).apply {
            addView(logView)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }

        listOf(btnInitUi, btnInitNoUi, btnDumpKnown, btnDumpConnected, btnTriggerReceiver, btnOpenGC, btnClear, scroll).forEach { root.addView(it) }
        setContentView(root)
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy - cleaning up ConnectIQ SDK")
        
        try {
            ciq?.let { connectIQ ->
                connectIQ.shutdown(this)
                Log.d(TAG, "ConnectIQ SDK shutdown completed")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Error during ConnectIQ cleanup: ${t.message}")
        } finally {
            ciq = null
            app = null
        }
        
        super.onDestroy()
    }

    private fun initSdk(showUi: Boolean) {
        if (ciq != null) {
            toast("SDK already initialized")
            return
        }
        
        try {
            Log.d(TAG, "Initializing SDK TETHERED, showUi=$showUi")
            ciq = ConnectIQ.getInstance(this, IQConnectType.TETHERED)
            ciq!!.initialize(this, showUi, object : ConnectIQListener {
                override fun onSdkReady() {
                    app = IQApp(appId)
                    Log.d(TAG, "SDK ready (Activity)")
                    toast("SDK ready")
                    appendUi("SDK ready")
                    
                    // Wait 3 seconds then check devices
                    Handler(Looper.getMainLooper()).postDelayed({
                        checkDevicesAfterInit()
                    }, 3000)
                }
                override fun onInitializeError(status: ConnectIQ.IQSdkErrorStatus?) {
                    Log.e(TAG, "SDK init error (Activity): $status")
                    toast("SDK init error: $status")
                    appendUi("SDK init error: $status")
                }
                override fun onSdkShutDown() {
                    Log.d(TAG, "SDK shutdown (Activity)")
                    appendUi("SDK shutdown")
                }
            })
            appendUi("Init called (showUi=$showUi)")
        } catch (t: Throwable) {
            Log.e(TAG, "Init exception: ${t.message}", t)
            toast("Init exception: ${t.message}")
            appendUi("Init exception: ${t.message}")
        }
    }

    private fun checkDevicesAfterInit() {
        val c = ciq ?: return
        val known = try { c.knownDevices ?: emptyList() } catch (_: Throwable) { emptyList() }
        val connected = try { c.getConnectedDevices() ?: emptyList() } catch (_: Throwable) { emptyList() }
        
        appendUi("=== AFTER INIT CHECK ===")
        appendUi("Known: ${known.size}, Connected: ${connected.size}")
        
        val realKnown = known.filter { it.deviceIdentifier != SIMULATOR_ID }
        val realConnected = connected.filter { it.deviceIdentifier != SIMULATOR_ID }
        
        appendUi("Real known: ${realKnown.size}, Real connected: ${realConnected.size}")
        
        if (realKnown.isNotEmpty() || realConnected.isNotEmpty()) {
            appendUi("✅ Bridge established - real devices found!")
        } else {
            appendUi("❌ Bridge not established - try 'Init SDK (with UI)' button")
        }
        
        // Log device details
        realKnown.forEach { device ->
            appendUi("Known device: ${device.friendlyName} (${device.deviceIdentifier})")
        }
        realConnected.forEach { device ->
            appendUi("Connected device: ${device.friendlyName} (${device.deviceIdentifier})")
        }
    }

    private fun dumpKnown() {
        val c = ciq ?: return toastUi("Init SDK first")
        try {
            val list = c.knownDevices ?: emptyList()
            appendUi("=== KNOWN DEVICES ===")
            if (list.isEmpty()) {
                appendUi("No known devices")
                return
            }
            
            list.forEachIndexed { i, d ->
                val isSimulator = d.deviceIdentifier == SIMULATOR_ID
                appendUi("[$i] ${d.friendlyName} id=${d.deviceIdentifier} ${if (isSimulator) "[SIMULATOR]" else "[REAL]"}")
            }
        } catch (t: Throwable) {
            appendUi("knownDevices failed: ${t.message}")
        }
    }

    private fun dumpConnected() {
        val c = ciq ?: return toastUi("Init SDK first")
        try {
            val list = c.getConnectedDevices() ?: emptyList()
            appendUi("=== CONNECTED DEVICES ===")
            if (list.isEmpty()) {
                appendUi("No connected devices")
                return
            }
            
            list.forEachIndexed { i, d ->
                val isSimulator = d.deviceIdentifier == SIMULATOR_ID
                appendUi("[$i] ${d.friendlyName} id=${d.deviceIdentifier} ${if (isSimulator) "[SIMULATOR]" else "[REAL]"}")
            }
        } catch (t: Throwable) {
            appendUi("getConnectedDevices failed: ${t.message}")
        }
    }

    private fun openGarminConnect() {
        val pm: PackageManager = packageManager
        val launch = pm.getLaunchIntentForPackage(GC_PACKAGE)
        if (launch == null) {
            appendUi("Garmin Connect not installed: $GC_PACKAGE")
            toast("Garmin Connect not installed")
            return
        }
        appendUi("Opening Garmin Connect…")
        Log.d(TAG, "Launching $GC_PACKAGE")
        startActivity(launch)
    }

    private fun appendUi(msg: String) { 
        runOnUiThread { logView.append("$msg\n") }
    }
    private fun toastUi(msg: String) { 
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        appendUi(msg) 
    }
    private fun toast(msg: String) { 
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() 
    }
}