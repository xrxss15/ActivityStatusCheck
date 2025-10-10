package net.xrxss15

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Invisible activity that only initializes ConnectIQ SDK then finishes.
 * Used for SDK recovery when MainActivity is not running.
 * 
 * This activity:
 * - Has no UI (transparent theme)
 * - Initializes SDK on main thread (required by ConnectIQ)
 * - Waits for SDK ready callback (which includes device registration)
 * - Finishes automatically after SDK is ready
 * - Doesn't appear in recent apps
 * 
 * The activity is started by ConnectIQService.testAndRecoverSdk() when:
 * - Worker detects SDK binding is broken after a device CONNECTED event
 * - getConnectedDevices() throws "SDK not initialized" exception
 */
class SdkInitActivity : Activity() {
    
    companion object {
        private const val TAG = "SdkInitActivity"
        const val ACTION_INIT_SDK = "net.xrxss15.INIT_SDK"
        private const val FINISH_DELAY_MS = 500L
    }
    
    private val handler = Handler(Looper.getMainLooper())
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Log.i(TAG, "Starting SDK recovery initialization")
        
        val service = ConnectIQService.getInstance()
        service.initializeSdkIfNeeded(this) {
            // Callback invoked after:
            // 1. SDK onSdkReady() fires
            // 2. 500ms delay for device discovery
            // 3. refreshAndRegisterDevices() completes
            Log.i(TAG, "SDK initialization and device registration complete")
            
            // Give SDK a brief moment to fully stabilize before closing
            handler.postDelayed({
                Log.i(TAG, "Finishing SDK init activity")
                finish()
            }, FINISH_DELAY_MS)
        }
    }
    
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
        Log.i(TAG, "SDK init activity destroyed")
    }
}