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

    private var appId: String = "5cd85684-4b48-419b-b63a-a2065368ae1e"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")

        // Safety guard: prevent process termination from ADB thread crashes in older SDKs
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            val st = e.stackTraceToString()
            if (st.contains("com.garmin.android.connectiq.adb.AdbConnection")) {
                Log.e(TAG, "Suppressed ADB thread crash: ${e.message}")
                appendUi("Suppressed ADB thread crash: ${e.javaClass.simpleName}")
                // swallow to keep app alive
            } else {
                Log.e(TAG, "Uncaught exception in ${t.name}: ${e.message}", e)
                // rethrow to default to avoid hiding real problems
                Thread.getDefaultUncaughtExceptionHandler()?.uncaughtException(t, e)
            }
        }

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

        fun add(vararg v: android.view.View) { v.forEach { root.addView(it) } }
        add(appIdField, btnInitNoUi, btnInitUi, btnDumpKnown, btnDumpConnected,
            btnTriggerReceiver, btnOpenGC, btnClear, scroll)

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