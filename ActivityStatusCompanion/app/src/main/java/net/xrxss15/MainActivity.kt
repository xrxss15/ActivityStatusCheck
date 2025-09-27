package net.xrxss15

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.garmin.android.connectiq.ConnectIQ
import com.garmin.android.connectiq.ConnectIQ.ConnectIQListener
import com.garmin.android.connectiq.ConnectIQ.IQApplicationEventListener
import com.garmin.android.connectiq.ConnectIQ.IQConnectType
import com.garmin.android.connectiq.ConnectIQ.IQMessageStatus
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice

class MainActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private var ciq: ConnectIQ? = null
    private var device: IQDevice? = null
    private var app: IQApp? = null

    // TODO: set to the exact UUID of the watch app
    private val ciqAppId = "REPLACE_WITH_WATCH_APP_UUID"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        logView = TextView(this)
        val scroll = ScrollView(this).apply { addView(logView) }

        val btnInit = Button(this).apply {
            text = "Init SDK"
            setOnClickListener { initSdk() }
        }
        val btnStatus = Button(this).apply {
            text = "Check device status"
            setOnClickListener { checkDeviceStatus() }
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
            text = "Broadcast (receiver)"
            setOnClickListener {
                sendBroadcast(Intent("net.xrxss15.ACTIVITY_STATUS_CHECK"))
                toast("Broadcast sent")
                log("Broadcast sent: net.xrxss15.ACTIVITY_STATUS_CHECK")
            }
        }

        listOf(btnInit, btnStatus, btnRegister, btnSend, btnBroadcast, scroll).forEach { root.addView(it) }
        setContentView(root)
    }

    private fun initSdk() {
        if (ciq != null) {
            toast("SDK already initialized")
            return
        }
        ciq = ConnectIQ.getInstance(this, IQConnectType.TETHERED)
        ciq!!.initialize(this, true, object : ConnectIQListener {
            override fun onSdkReady() {
                app = IQApp(ciqAppId)
                log("SDK ready")
                toast("SDK ready")
            }
            override fun onInitializeError(status: ConnectIQ.IQSdkErrorStatus?) {
                log("SDK init error: $status")
                toast("SDK init error: $status")
            }
            override fun onSdkShutDown() {
                log("SDK shutdown")
            }
        })
    }

    private fun checkDeviceStatus() {
        val c = ciq ?: return toast("Init SDK first")
        val devices = try { c.knownDevices ?: emptyList() } catch (_: Throwable) { emptyList() }
        device = devices.firstOrNull()
        if (device == null) {
            log("No known devices (is watch connected to Garmin Connect?)")
            toast("No known devices")
            return
        }
        log("Selected device: ${device!!.friendlyName} (${device!!.deviceIdentifier})")
        try {
            c.registerForDeviceEvents(device) { dev, status ->
                log("Device ${dev.friendlyName} status=${status.name}")
            }
        } catch (t: Throwable) {
            log("registerForDeviceEvents failed: ${t.message}")
        }
    }

    private fun registerForAppEvents() {
        val c = ciq ?: return toast("Init SDK first")
        val d = device ?: return toast("No device")
        val a = app ?: return toast("No app id")
        try {
            c.registerForAppEvents(d, a, object : IQApplicationEventListener {
                override fun onMessageReceived(
                    iqDevice: IQDevice,
                    iqApp: IQApp,
                    messageData: List<Any>,
                    status: IQMessageStatus
                ) {
                    log("onMessageReceived status=$status data=$messageData")
                    if (status == IQMessageStatus.SUCCESS && messageData.isNotEmpty()) {
                        val txt = messageData.joinToString("")
                        toast("Reply: $txt")
                    }
                }
            })
            log("Registered for app events")
        } catch (t: Throwable) {
            log("registerForAppEvents failed: ${t.message}")
        }
    }

    private fun sendStatusQuery() {
        val c = ciq ?: return toast("Init SDK first")
        val d = device ?: return toast("No device")
        val a = app ?: return toast("No app id")
        try {
            c.sendMessage(d, a, listOf("status?")) { _, _, status ->
                log("sendMessage status=$status")
                if (status != IQMessageStatus.SUCCESS) {
                    toast("sendMessage: $status")
                }
            }
            log("Sent message: status?")
        } catch (t: Throwable) {
            log("sendMessage failed: ${t.message}")
            toast("sendMessage failed: ${t.message}")
        }
    }

    private fun log(msg: String) {
        logView.append("$msg\n")
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}