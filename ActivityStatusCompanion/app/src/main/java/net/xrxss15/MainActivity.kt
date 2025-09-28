package net.xrxss15

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup.LayoutParams
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.garmin.android.connectiq.ConnectIQ
import com.garmin.android.connectiq.ConnectIQ.ConnectIQListener
import com.garmin.android.connectiq.ConnectIQ.IQConnectType
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice
import com.garmin.android.connectiq.IQDevice.IQDeviceStatus

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
    private var sdkInitialized = false
    private var bridgeReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")

        // Enable ConnectIQ debug logging
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            System.setProperty("connectiq.debug", "true")
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }

        val btnInitFirst = Button(this).apply {
            text = "FIRST Init (with UI)"
            setOnClickListener { initSdkFirst() }
        }
        val btnDumpKnown = Button(this).apply {
            text = "Dump known devices"
            setOnClickListener { dumpKnown() }
        }
        val btnDumpConnected = Button(this).apply {
            text = "Dump connected devices" 
            setOnClickListener { dumpConnected() }
        }
        val btnWaitForBridge = Button(this).apply {
            text = "Wait for bridge (10s)"
            setOnClickListener { waitForBridge() }
        }
        val btnTriggerReceiver = Button(this).apply {
            text = "Trigger Receiver"
            setOnClickListener {
                if (!bridgeReady) {
                    toast("Bridge not ready - init first")
                    return@setOnClickListener
                }
                sendBroadcast(Intent(ActivityStatusCheckReceiver.ACTION_TRIGGER))
                toast("Broadcast sent")
            }
        }

        logView = TextView(this)
        val scroll = ScrollView(this).apply {
            addView(logView)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }

        listOf(btnInitFirst, btnDumpKnown, btnDumpConnected, btnWaitForBridge, btnTriggerReceiver, scroll).forEach { root.addView(it) }
        setContentView(root)
    }

    private fun initSdkFirst() {
        if (sdkInitialized) {
            toast("SDK already initialized")
            return
        }
        
        try {
            log("Creating ConnectIQ instance (TETHERED)")
            ciq = ConnectIQ.getInstance(this, IQConnectType.TETHERED)
            
            log("Initializing SDK with showUi=true (REQUIRED for first run)")
            ciq!!.initialize(this, true, object : ConnectIQListener {
                override fun onSdkReady() {
                    log("SDK ready - checking devices in 2s")
                    sdkInitialized = true
                    app = IQApp(appId)
                    
                    // Give bridge time to establish before checking devices
                    Handler(Looper.getMainLooper()).postDelayed({
                        checkBridgeStatus()
                    }, 2000)
                }
                override fun onInitializeError(status: ConnectIQ.IQSdkErrorStatus?) {
                    log("SDK init error: $status")
                    toast("Init failed: $status")
                }
                override fun onSdkShutDown() {
                    log("SDK shutdown")
                    sdkInitialized = false
                    bridgeReady = false
                }
            })
        } catch (t: Throwable) {
            log("Init exception: ${t.message}")
            toast("Init failed: ${t.message}")
        }
    }

    private fun checkBridgeStatus() {
        val c = ciq ?: return
        log("Checking bridge status...")
        
        // Check both known and connected devices
        val known = try { c.knownDevices ?: emptyList() } catch (_: Throwable) { emptyList() }
        val connected = try { c.getConnectedDevices() ?: emptyList() } catch (_: Throwable) { emptyList() }
        
        log("Known devices: ${known.size}")
        log("Connected devices: ${connected.size}")
        
        val realKnown = known.filter { it.deviceIdentifier != SIMULATOR_ID }
        val realConnected = connected.filter { it.deviceIdentifier != SIMULATOR_ID }
        
        log("Real known devices: ${realKnown.size}")
        log("Real connected devices: ${realConnected.size}")
        
        if (realKnown.isNotEmpty() || realConnected.isNotEmpty()) {
            bridgeReady = true
            log("Bridge appears ready - found real devices")
            toast("Bridge ready!")
        } else {
            log("Bridge not ready - only simulator found")
            toast("Only simulator found - check Garmin Connect")
        }
    }

    private fun waitForBridge() {
        if (!sdkInitialized) {
            toast("Init SDK first")
            return
        }
        
        log("Waiting 10s for bridge to establish...")
        toast("Waiting for bridge...")
        
        Handler(Looper.getMainLooper()).postDelayed({
            checkBridgeStatus()
        }, 10000)
    }

    private fun dumpKnown() {
        val c = ciq ?: return toast("Init SDK first")
        try {
            val list = c.knownDevices ?: emptyList()
            log("=== KNOWN DEVICES ===")
            if (list.isEmpty()) {
                log("No known devices")
                return
            }
            
            list.forEachIndexed { i, d ->
                val isSimulator = d.deviceIdentifier == SIMULATOR_ID
                log("[$i] ${d.friendlyName} id=${d.deviceIdentifier} ${if (isSimulator) "[SIMULATOR]" else "[REAL]"}")
            }
        } catch (t: Throwable) {
            log("knownDevices failed: ${t.message}")
        }
    }

    private fun dumpConnected() {
        val c = ciq ?: return toast("Init SDK first")
        try {
            val list = c.getConnectedDevices() ?: emptyList()
            log("=== CONNECTED DEVICES ===")
            if (list.isEmpty()) {
                log("No connected devices")
                return
            }
            
            list.forEachIndexed { i, d ->
                val isSimulator = d.deviceIdentifier == SIMULATOR_ID
                log("[$i] ${d.friendlyName} id=${d.deviceIdentifier} ${if (isSimulator) "[SIMULATOR]" else "[REAL]"}")
            }
        } catch (t: Throwable) {
            log("getConnectedDevices failed: ${t.message}")
        }
    }

    private fun log(msg: String) { 
        Log.d(TAG, msg)
        runOnUiThread { logView.append("$msg\n") }
    }
    private fun toast(msg: String) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
}