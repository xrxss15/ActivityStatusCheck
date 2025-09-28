package net.xrxss15

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup.LayoutParams
import android.widget.*
import com.garmin.android.connectiq.ConnectIQ
import com.garmin.android.connectiq.ConnectIQ.ConnectIQListener
import com.garmin.android.connectiq.ConnectIQ.IQConnectType
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice

class MainActivity : Activity() {

    companion object { private const val TAG = "ActStatusCheck" }

    private lateinit var logView: TextView
    private var ciq: ConnectIQ? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        /* Enable ConnectIQ internal logging */
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            System.setProperty("connectiq.debug", "true")
        }

        /* ------------- simple GUI ------------- */
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        fun btn(txt: String, onClick: () -> Unit) =
            Button(this).apply { text = txt; setOnClickListener { onClick() } }

        root.addView(btn("FIRST Init (with UI)") { initSdk(true) })
        root.addView(btn("Re-Init (no UI)")      { initSdk(false) })
        root.addView(btn("Dump knownDevices")    { dump { it.knownDevices } })
        root.addView(btn("Dump getConnected")    { dump { it.getConnectedDevices() } })
        root.addView(btn("Send Tasker Intent")   {
            sendBroadcast(Intent(ActivityStatusCheckReceiver.ACTION_TRIGGER))
            toast("Broadcast fired")
        })

        logView = TextView(this)
        val scroll = ScrollView(this).apply {
            addView(logView)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        root.addView(scroll)
        setContentView(root)
    }

    /* ---------- helpers ---------- */

    private fun initSdk(showUi: Boolean) {
        if (ciq != null) { toast("SDK already ready"); return }
        ciq = ConnectIQ.getInstance(this, IQConnectType.TETHERED)
        ciq!!.initialize(this, showUi, object : ConnectIQListener {
            override fun onSdkReady()  = log("SDK ready")
            override fun onInitializeError(e: ConnectIQ.IQSdkErrorStatus?) = log("Init error $e")
            override fun onSdkShutDown() = log("SDK shut down")
        })
        log("init(showUi=$showUi)")
    }

    private fun dump(block: (ConnectIQ) -> List<IQDevice>?) {
        val c = ciq ?: return toast("Init first")
        log("---- dump ----")
        try { block(c)?.forEachIndexed { i, d -> log("[$i] ${d.friendlyName} id=${d.deviceIdentifier}") } }
        catch (t: Throwable) { log("err ${t.message}") }
    }

    private fun log(msg: String) { Log.d(TAG, msg); logView.append("$msg\n") }
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}