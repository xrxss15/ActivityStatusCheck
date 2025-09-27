package net.xrxss15

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
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
import com.garmin.android.connectiq.ConnectIQ.IQApplicationEventListener
import com.garmin.android.connectiq.ConnectIQ.IQConnectType
import com.garmin.android.connectiq.ConnectIQ.IQMessageStatus
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice

class MainActivity : Activity() {

    companion object {
        private const val TAG = "ActStatusCheck"
        private const val GC_PACKAGE = "com.garmin.android.apps.connectmobile"
        private const val SIMULATOR_ID: Long = 12345L
    }

    private lateinit var logView: TextView
    private lateinit var appIdField: EditText

    private var ciq: ConnectIQ? = null
    private var app: IQApp? = null
    private var appId: String = "REPLACE_WITH_WATCH_APP_UUID"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }

        appIdField = EditText(this).apply {
            hint = "Connect IQ App UUID"
            setText(appId)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
        }

        val btnInitNoUi = Button(this).apply {
            text = "Init SDK (no UI)"
            setOnClickListener { initSdk(false) }
        }
        val btnInitUi = Button(this).apply {
            text = "Init SDK (with UI)"
            setOnClickListener { initSdk(true) }
        }
        val btnDumpKnown = Button(this).apply {
            text = "Dump known devices"
            setOnClickListener { dumpKnown() }
        }
        val btnDumpConnected = Button(this).apply {
            text = "Dump connected devices"
            setOnClickListener { dumpConnected() }
        }
        val btnRegisterStatus = Button(this).apply {
            text = "Register device status"
            setOnClickListener { registerDeviceStatus() }
        }
        val btnRegisterAppEvents = Button(this).apply {
            text = "Register app events"
            setOnClickListener { registerAppEvents() }
        }
        val btnSendFromActivity = Button(this).apply {
            text = "Send status? (Activity)"
            setOnClickListener { sendFromActivity() }
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
        val btnApplyAppId = Button(this).apply {
            text = "Apply App UUID"
            setOnClickListener {
                appId = appIdField.text?.toString()?.trim().orEmpty()
                app = if (appId.isNotEmpty()) IQApp(appId) else null
                appendUi("App UUID set: $appId")
                Log.d(TAG, "App UUID updated: $appId")
            }
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

        fun add(vararg v: android.view.View) { v.forEach { root.addView(it) } }
        add(appIdField, btnApplyAppId, btnInitNoUi, btnInitUi, btnDumpKnown, btnDumpConnected,
            btnRegisterStatus, btnRegisterAppEvents, btnSendFromActivity, btnTriggerReceiver, btnOpenGC, btnClear, scroll)

        setContentView(root)
    }

    private fun initSdk(showUi: Boolean) {
        if (ciq != null) {
            toast("SDK already initialized")
            Log.d(TAG, "SDK already initialized")
            return
        }
        if (appId.isEmpty()) {
            toast("Set App UUID first")
            appendUi("Set App UUID first")
            return
        }
        try {
            Log.d(TAG, "Initializing SDK TETHERED, showUi=$showUi")
            ciq = ConnectIQ.getInstance(applicationContext, IQConnectType.TETHERED)
            ciq!!.initialize(applicationContext, showUi, object : ConnectIQListener {
                override fun onSdkReady() {
                    app = IQApp(appId)
                    Log.d(TAG, "SDK ready (Activity)")
                    toast("SDK ready")
                    appendUi("SDK ready")
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

    private fun dumpKnown() {
        val c = ciq ?: return toastUi("Init SDK first")
        try {
            val list = c.knownDevices ?: emptyList()
            appendUi("Known devices count=${list.size}")
            list.forEachIndexed { i, d ->
                appendUi("[$i] ${d.friendlyName} id=${d.deviceIdentifier}")
            }
            Log.d(TAG, "Dumped known devices (${list.size})")
        } catch (t: Throwable) {
            Log.e(TAG, "knownDevices failed: ${t.message}", t)
            appendUi("knownDevices failed: ${t.message}")
        }
    }

    private fun dumpConnected() {
        val c = ciq ?: return toastUi("Init SDK first")
        try {
            val list = c.getConnectedDevices()?.filter { it.deviceIdentifier != SIMULATOR_ID } ?: emptyList()
            appendUi("Connected devices count=${list.size}")
            list.forEachIndexed { i, d ->
                appendUi("[$i] ${d.friendlyName} id=${d.deviceIdentifier}")
            }
            Log.d(TAG, "Dumped connected devices (${list.size})")
        } catch (t: Throwable) {
            Log.e(TAG, "getConnectedDevices failed: ${t.message}", t)
            appendUi("getConnectedDevices failed: ${t.message}")
        }
    }

    private fun registerDeviceStatus() {
        val c = ciq ?: return toastUi("Init SDK first")
        val list = try { c.knownDevices ?: emptyList() } catch (_: Throwable) { emptyList() }
        if (list.isEmpty()) { appendUi("No known devices"); return }
        list.forEach { d ->
            try {
                c.registerForDeviceEvents(d) { dev, status ->
                    Log.d(TAG, "Device ${dev.friendlyName} status=${status.name}")
                    appendUi("Status ${dev.friendlyName}: ${status.name}")
                }
                appendUi("Status listener registered: ${d.friendlyName}")
            } catch (t: Throwable) {
                Log.e(TAG, "registerForDeviceEvents failed for ${d.friendlyName}: ${t.message}", t)
                appendUi("registerForDeviceEvents failed: ${t.message}")
            }
        }
    }

    private fun registerAppEvents() {
        val c = ciq ?: return toastUi("Init SDK first")
        val a = app ?: return toastUi("Set App UUID first")
        val list = try { c.getConnectedDevices()?.filter { it.deviceIdentifier != SIMULATOR_ID } ?: emptyList() } catch (_: Throwable) { emptyList() }
        if (list.isEmpty()) { appendUi("No connected devices"); return }
        list.forEach { d ->
            try {
                c.registerForAppEvents(d, a, object : IQApplicationEventListener {
                    override fun onMessageReceived(iqDevice: IQDevice, iqApp: IQApp, messageData: List<Any>, status: IQMessageStatus) {
                        Log.d(TAG, "AppEvent ${iqDevice.friendlyName} status=$status data=$messageData")
                        appendUi("RX ${iqDevice.friendlyName}: status=$status data=$messageData")
                    }
                })
                appendUi("App events registered: ${d.friendlyName}")
            } catch (t: Throwable) {
                Log.e(TAG, "registerForAppEvents failed for ${d.friendlyName}: ${t.message}", t)
                appendUi("registerForAppEvents failed: ${t.message}")
            }
        }
    }

    private fun sendFromActivity() {
        val c = ciq ?: return toastUi("Init SDK first")
        val a = app ?: return toastUi("Set App UUID first")
        val list = try { c.getConnectedDevices()?.filter { it.deviceIdentifier != SIMULATOR_ID } ?: emptyList() } catch (_: Throwable) { emptyList() }
        if (list.isEmpty()) { appendUi("No connected devices"); return }
        list.forEach { d ->
            try {
                c.registerForAppEvents(d, a, object : IQApplicationEventListener {
                    override fun onMessageReceived(iqDevice: IQDevice, iqApp: IQApp, messageData: List<Any>, status: IQMessageStatus) {
                        Log.d(TAG, "onMessageReceived (Activity) from ${iqDevice.friendlyName} status=$status data=$messageData")
                        appendUi("RX (Activity) ${iqDevice.friendlyName}: status=$status data=$messageData")
                    }
                })
                c.sendMessage(d, a, listOf("status?")) { _, _, status ->
                    Log.d(TAG, "sendMessage (Activity) ${d.friendlyName} status=$status")
                    appendUi("TX (Activity) ${d.friendlyName}: status=$status")
                }
                appendUi("Sent status? to ${d.friendlyName}")
            } catch (t: Throwable) {
                Log.e(TAG, "sendFromActivity failed for ${d.friendlyName}: ${t.message}", t)
                appendUi("sendFromActivity failed: ${t.message}")
            }
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

    private fun appendUi(msg: String) { logView.append("$msg\n") }
    private fun toastUi(msg: String) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show(); appendUi(msg) }
    private fun toast(msg: String) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
}