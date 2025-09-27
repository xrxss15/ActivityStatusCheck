package net.xrxss15

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.garmin.android.connectiq.ConnectIQ
import com.garmin.android.connectiq.ConnectIQ.ConnectIQListener
import com.garmin.android.connectiq.ConnectIQ.IQConnectType

class MainActivity : Activity() {

    companion object {
        private const val TAG = "ActStatusCheck"
    }

    private lateinit var logView: TextView
    private var ciq: ConnectIQ? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate") // tag-based log

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
                Log.d(TAG, "Sending trigger broadcast")
                sendBroadcast(Intent(ActivityStatusCheckReceiver.ACTION_TRIGGER))
                toast("Broadcast sent")
                appendUi("Broadcast sent: ${ActivityStatusCheckReceiver.ACTION_TRIGGER}")
            }
        }

        listOf(btnInit, btnTrigger, scroll).forEach { root.addView(it) }
        setContentView(root)
    }

    private fun initSdk() {
        if (ciq != null) { toast("SDK already initialized"); Log.d(TAG, "SDK already initialized"); return }
        try {
            Log.d(TAG, "Initializing SDK (TETHERED, no UI)")
            ciq = ConnectIQ.getInstance(applicationContext, IQConnectType.TETHERED)
            ciq!!.initialize(applicationContext, /*showUi=*/false, object : ConnectIQListener {
                override fun onSdkReady() {
                    Log.d(TAG, "SDK ready")
                    toast("SDK ready")
                    appendUi("SDK ready")
                }
                override fun onInitializeError(status: ConnectIQ.IQSdkErrorStatus?) {
                    Log.e(TAG, "SDK init error: $status")
                    toast("SDK init error: $status")
                    appendUi("SDK init error: $status")
                }
                override fun onSdkShutDown() {
                    Log.d(TAG, "SDK shutdown")
                    appendUi("SDK shutdown")
                }
            })
            Log.d(TAG, "Init call returned")
        } catch (t: Throwable) {
            Log.e(TAG, "Init exception: ${t.message}", t)
            toast("Init exception: ${t.message}")
            appendUi("Init exception: ${t.message}")
        }
    }

    private fun appendUi(msg: String) { logView.append("$msg\n") }
    private fun toast(msg: String) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
}