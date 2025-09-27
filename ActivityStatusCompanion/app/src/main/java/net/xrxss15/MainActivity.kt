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
    private var device: IQDevice? = null
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
            text = "Dump known devices"
            setOnClickListener { dumpDevices() }
        }
        val btnStatus = Button(this).apply {
            text = "Watch status listener"
            setOnClickListener { registerDeviceStatus() }
        }
        val btnRegister = Button(this).apply {
            text = "Register app events"
            setOnClickListener { registerForAppEvents() }
        }
        val btnSend = Button(this).apply {
            text = "Send status?"
            setOnClickListener { sendStatusQuery() }
        }
        val btnBroadcast = Button(this).apply {
            text = "Trigger Receiver"
            setOnClickListener {
                sendBroadcast(Intent("net.xrxss15.ACTIVITY_STATUS_CHECK"))
                toast("Broadcast sent")
                log("Broadcast sent: net.xrxss15.ACTIVITY_STATUS_CHECK")
            }
        }

        listOf(btnInit, btnDump, btnStatus, btnRegister, btnSend, btnBroadcast, scroll).forEach { root.addView(it) }
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

    private fun dumpDevices() {
        val c = ciq ?: return toast("Init SDK first")
        val devices = try { c.knownDevices ?: emptyList() } catch (_: Throwable) { emptyList() }
        if (devices.isEmpty()) { log("No known devices (is Garmin Connect running?)"); toast("No devices"); return }
        devices.forEachIndexed { i, d -> log("[$i] ${d.friendlyName} id=${d.deviceIdentifier}") }
        device = devices.first()
    }

    private fun registerDeviceStatus() {
        val c = ciq ?: return toast("Init SDK first")
        val d = device ?: return toast("No device")
        try {
            c.registerForDeviceEvents(d) { dev, status ->
                log("Device ${dev.friendlyName} status=${status.name}")
            }
            log("Registered device status listener")
        } catch (t: Throwable) { log("registerForDeviceEvents failed: ${t.message}") }
    }

    private fun registerForAppEvents() {
        val c = ciq ?: return toast("Init SDK first")
        val d = device ?: return toast("No device")
        val a = app ?: return toast("No app id")
        try {
            c.registerForAppEvents(d, a, object : IQApplicationEventListener {
                override fun onMessageReceived(iqDevice: IQDevice, iqApp: IQApp, messageData: List<Any>, status: IQMessageStatus) {
                    log("onMessageReceived status=$status data=$messageData")
                    if (status == IQMessageStatus.SUCCESS && messageData.isNotEmpty()) {
                        toast("Reply: " + messageData.joinToString(""))
                    }
                }
            })
            log("Registered app event listener")
        } catch (t: Throwable) { log("registerForAppEvents failed: ${t.message}") }
    }

    private fun sendStatusQuery() {
        val c = ciq ?: return toast("Init SDK first")
        val d = device ?: return toast("No device")
        val a = app ?: return toast("No app id")
        try {
            c.sendMessage(d, a, listOf("status?")) { _, _, status ->
                log("sendMessage status=$status")
                if (status != IQMessageStatus.SUCCESS) toast("sendMessage: $status")
            }
            log("Sent message: status?")
        } catch (t: Throwable) {
            log("sendMessage failed: ${t.message}")
            toast("sendMessage failed: ${t.message}")
        }
    }

    private fun log(msg: String) { logView.append("$msg\n") }
    private fun toast(msg: String) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
}