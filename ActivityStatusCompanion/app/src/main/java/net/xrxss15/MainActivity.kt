package net.xrxss15

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
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

    private lateinit var logView: TextView
    private var ciq: ConnectIQ? = null
    private var app: IQApp? = null

    private val ciqAppId = "REPLACE_WITH_WATCH_APP_UUID"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        logView = TextView(this)
        val scroll = ScrollView(this).apply { addView(logView) }

        val btnInit = Button(this).apply {
            text = "Init SDK (TETHERED)"
            setOnClickListener { initSdk() }
        }
        val btnDump = Button(this).apply {
            text = "Dump connected devices"
            setOnClickListener { dumpConnected() }
        }
        val btnTrigger = Button(this).apply {
            text = "Trigger Receiver"
            setOnClickListener {
                sendBroadcast(Intent(ActivityStatusCheckReceiver.ACTION_TRIGGER))
                toast("Broadcast sent")
                log("Broadcast sent: ${ActivityStatusCheckReceiver.ACTION_TRIGGER}")
            }
        }

        listOf(btnInit, btnDump, btnTrigger, scroll).forEach { root.addView(it) }
        setContentView(root)
    }

    private fun initSdk() {
        if (ciq != null) { toast("SDK already initialized"); return }
        ciq = ConnectIQ.getInstance(this, IQConnectType.TETHERED)
        ciq!!.initialize(this, true, object : ConnectIQListener {
            override fun onSdkReady() { app = IQApp(ciqAppId); log("SDK ready"); toast("SDK ready") }
            override fun onInitializeError(status: ConnectIQ.IQSdkErrorStatus?) { log("SDK init error: $status"); toast("SDK init error: $status") }
            override fun onSdkShutDown() { log("SDK shutdown") }
        })
    }

    private fun dumpConnected() {
        val c = ciq ?: return toast("Init SDK first")
        val devices = try { c.getConnectedDevices() ?: emptyList() } catch (_: Throwable) { emptyList() }
        if (devices.isEmpty()) { log("No connected devices"); toast("No connected devices"); return }
        devices.forEachIndexed { i, d -> log("[$i] ${d.friendlyName} id=${d.deviceIdentifier}") }
    }

    private fun log(msg: String) { logView.append("$msg\n") }
    private fun toast(msg: String) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
}