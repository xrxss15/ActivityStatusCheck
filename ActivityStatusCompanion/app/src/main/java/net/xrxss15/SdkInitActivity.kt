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
 * - Initializes SDK
 * - Finishes immediately after SDK ready
 * - Doesn't appear in recent apps
 */
class SdkInitActivity : Activity() {
    
    companion object {
        private const val TAG = "SdkInitActivity"
        const val ACTION_INIT_SDK = "net.xrxss15.INIT_SDK"
        private const val FINISH_DELAY_MS = 1000L
    }
    
    private val handler = Handler(Looper.getMainLooper())
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        Log.i(TAG, "Initializing SDK in background")
        
        val service = ConnectIQService.getInstance()
        service.initializeSdkIfNeeded(this) {
            Log.i(TAG, "SDK initialized successfully")
            
            // Give SDK a moment to settle before finishing
            handler.postDelayed({
                Log.i(TAG, "Finishing SDK init activity")
                finish()
            }, FINISH_DELAY_MS)
        }
    }
    
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}