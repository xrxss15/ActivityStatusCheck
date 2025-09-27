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
import com.garmin.android.connectiq.ConnectIQ.IQConnectType

class MainActivity : Activity() {

    private lateinit var logView: TextView
    private var ciq: ConnectIQ? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        logView = TextView(this)
        val scroll = ScrollView(this).apply { addView(logView) }

        val btnInit = Button(this).apply {
            text = "Init SDK (no UI)"
            setOnClickListener { initSdk() }
        }
        val btnTrigger = Button(this).apply {
            text = "Trigger Receiver"
            setOnClickListener {
                sendBroadcast(Intent(ActivityStatusCheckReceiver.ACTION_TRIGGER))
                toast("Broadcast sent")
                log("Broadcast sent: ${ActivityStatusCheckReceiver.ACTION_TRIGGER}")
            }
        }

        listOf(btnInit, btnTrigger, scroll).forEach { root.addView(it) }
        setContentView(root)
    }

    private fun initSdk() {
        if (ciq != null) { toast("SDK already initialized"); return }
        try {
            // Use TETHERED and disable UI prompts to avoid disruptive flows
            ciq = ConnectIQ.getInstance(applicationContext, IQConnectType.TETHERED)
            ciq!!.initialize(applicationContext, /*showUi=*/false, object : ConnectIQListener {
                override fun onSdkReady() { log("SDK ready"); toast("SDK ready") }
                override fun onInitializeError(status: ConnectIQ.IQSdkErrorStatus?) {
                    log("SDK init error: $status"); toast("SDK init error: $status")
                }
                override fun onSdkShutDown() { log("SDK shutdown") }
            })
            log("Init called (no UI)")
        } catch (t: Throwable) {
            log("Init exception: ${t.message}")
            toast("Init exception: ${t.message}")
        }
    }

    private fun log(msg: String) { logView.append("$msg\n") }
    private fun toast(msg: String) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
}