package net.xrxss15

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.work.WorkInfo
import androidx.work.WorkManager

/**
 * Singleton controller for app-wide operations.
 * Provides single source of truth for common functionality.
 * 
 * This centralized controller eliminates code duplication and ensures
 * consistent behavior across MainActivity and BroadcastReceiver.
 */
object AppController {
    
    private const val TAG = "AppController"
    
    // Shared Preferences
    const val PREFS_NAME = "GarminActivityListener"
    const val PREF_LOG = "saved_log"
    
    // Worker
    const val WORKER_NAME = "garmin_listener"
    
    // Broadcasts
    const val ACTION_EVENT = "net.xrxss15.GARMIN_ACTIVITY_LISTENER_EVENT"
    const val ACTION_CLOSE_GUI = "net.xrxss15.CLOSE_GUI"
    
    // Termination
    const val MAX_TERMINATION_CHECKS = 20
    const val TERMINATION_CHECK_DELAY_MS = 100L
    
    // Log batching
    const val LOG_SAVE_DELAY_MS = 5000L
    
    /**
     * Check if background worker is running.
     * Thread-safe, can be called from any thread.
     * 
     * @param context Application context
     * @return true if worker is RUNNING or ENQUEUED
     */
    fun isListenerRunning(context: Context): Boolean {
        return try {
            val workManager = WorkManager.getInstance(context)
            val workInfos = workManager.getWorkInfosForUniqueWork(WORKER_NAME).get()
            workInfos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking worker status: ${e.message}")
            false
        }
    }
    
    /**
     * Save log to SharedPreferences.
     * 
     * @param context Application context
     * @param log Log content to save
     */
    fun saveLog(context: Context, log: String) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(PREF_LOG, log).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save log: ${e.message}")
        }
    }
    
    /**
     * Restore log from SharedPreferences.
     * 
     * @param context Application context
     * @return Saved log content, or null if not found
     */
    fun restoreLog(context: Context): String? {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.getString(PREF_LOG, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore log: ${e.message}")
            null
        }
    }
    
    /**
     * Clear saved log from SharedPreferences.
     * 
     * @param context Application context
     */
    fun clearSavedLog(context: Context) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().remove(PREF_LOG).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear saved log: ${e.message}")
        }
    }
    
    /**
     * Terminate the app completely.
     * This is the SINGLE SOURCE OF TRUTH for app termination.
     * 
     * Process:
     * 1. Cancel background worker
     * 2. Wait for worker to stop (up to 2 seconds)
     * 3. Reset ConnectIQ SDK
     * 4. Clear saved log
     * 5. Send termination broadcast
     * 6. Kill process
     * 
     * @param context Application context
     * @param reason Reason for termination (included in broadcast)
     */
    fun terminateApp(context: Context, reason: String) {
        Log.i(TAG, "Terminating app: $reason")
        
        // Cancel worker
        WorkManager.getInstance(context).cancelUniqueWork(WORKER_NAME)
        
        // Wait for worker to stop
        val handler = Handler(Looper.getMainLooper())
        var checkCount = 0
        val checkRunnable = object : Runnable {
            override fun run() {
                checkCount++
                val running = isListenerRunning(context)
                
                if (!running || checkCount >= MAX_TERMINATION_CHECKS) {
                    // Reset SDK
                    ConnectIQService.resetInstance()
                    
                    // Clear saved log
                    clearSavedLog(context)
                    
                    // Send terminated broadcast
                    val intent = Intent(ACTION_EVENT).apply {
                        addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                        putExtra("type", "Terminated")
                        putExtra("reason", reason)
                    }
                    context.sendBroadcast(intent)
                    
                    // Kill process completely
                    android.os.Process.killProcess(android.os.Process.myPid())
                } else {
                    handler.postDelayed(this, TERMINATION_CHECK_DELAY_MS)
                }
            }
        }
        handler.postDelayed(checkRunnable, TERMINATION_CHECK_DELAY_MS)
    }
}