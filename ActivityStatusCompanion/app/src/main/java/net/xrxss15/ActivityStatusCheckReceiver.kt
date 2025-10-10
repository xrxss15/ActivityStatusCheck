package net.xrxss15

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.work.WorkManager
import androidx.work.WorkInfo

/**
 * BroadcastReceiver for controlling the Garmin Activity Listener app remotely.
 *
 * This receiver handles intents from external automation tools like Tasker,
 * allowing complete control over the app's lifecycle and GUI visibility.
 *
 * ========================================
 * SUPPORTED ACTIONS
 * ========================================
 *
 * 1. ACTION_TERMINATE (net.xrxss15.TERMINATE)
 *    - Stops the background worker
 *    - Shuts down the ConnectIQ SDK
 *    - Kills the app process completely
 *    - Next app launch will be a fresh start
 *    - Use case: User wants to completely exit the app
 *
 * 2. ACTION_OPEN_GUI (net.xrxss15.OPEN_GUI)
 *    - Opens the MainActivity GUI
 *    - If worker is running, GUI reconnects to it
 *    - If worker is not running, user can start it manually
 *    - Use case: User wants to view logs or status
 *
 * 3. ACTION_CLOSE_GUI (net.xrxss15.CLOSE_GUI)
 *    - Closes the MainActivity GUI
 *    - Worker continues running in background
 *    - Listening for activity events continues
 *    - Use case: Hide GUI but keep monitoring
 *
 * 4. ACTION_EVENT (net.xrxss15.GARMIN_ACTIVITY_LISTENER_EVENT)
 *    - Internal event broadcast (not meant for external use)
 *    - Sent by worker when activity events occur
 *
 * ========================================
 * TASKER INTEGRATION EXAMPLES
 * ========================================
 *
 * Example 1: Terminate app completely
 * -----------------------------------
 * Task: "Stop Garmin Listener"
 * Action: Send Intent
 *   - Action: net.xrxss15.TERMINATE
 *   - Target: Broadcast Receiver
 *
 * Example 2: Open GUI
 * -----------------------------------
 * Task: "Show Garmin Listener"
 * Action: Send Intent
 *   - Action: net.xrxss15.OPEN_GUI
 *   - Target: Broadcast Receiver
 *
 * Example 3: Hide GUI (keep listening)
 * -----------------------------------
 * Task: "Hide Garmin Listener"
 * Action: Send Intent
 *   - Action: net.xrxss15.CLOSE_GUI
 *   - Target: Broadcast Receiver
 *
 * Example 4: Listen for activity events
 * -----------------------------------
 * Profile: Event > Intent Received
 *   - Action: net.xrxss15.GARMIN_ACTIVITY_LISTENER_EVENT
 *
 * Task: "Activity Event Handler"
 * Variable %type contains the event type:
 *   - "Created" = Worker started
 *   - "Terminated" = Worker stopped
 *   - "Started" = Activity started on device
 *   - "Stopped" = Activity stopped on device
 *   - "DeviceList" = Device connection status changed
 *
 * Example: Flash notification on activity start
 * If %type ~ Started:
 *   - Flash: "Activity started on %device"
 *   - Variable %device = device name
 *   - Variable %activity = activity type
 *   - Variable %time = start time (Unix timestamp)
 *
 * ========================================
 * BROADCAST EVENTS SENT BY APP
 * ========================================
 *
 * All events are sent with action:
 * net.xrxss15.GARMIN_ACTIVITY_LISTENER_EVENT
 *
 * Event 1: Worker Created
 * -----------------------
 * Extras:
 *   - type: "Created" (String)
 *   - timestamp: Worker start time (Long, milliseconds)
 *   - devices: Connected device names, separated by "/" (String)
 *
 * Event 2: Worker Terminated
 * ---------------------------
 * Extras:
 *   - type: "Terminated" (String)
 *   - reason: Reason for termination (String)
 *
 * Event 3: Activity Started
 * --------------------------
 * Extras:
 *   - type: "Started" (String)
 *   - device: Device name (String)
 *   - time: Activity start time (Long, Unix timestamp seconds)
 *   - activity: Activity type like "running", "cycling" (String)
 *   - duration: Always 0 for start events (Int)
 *
 * Event 4: Activity Stopped
 * --------------------------
 * Extras:
 *   - type: "Stopped" (String)
 *   - device: Device name (String)
 *   - time: Activity start time (Long, Unix timestamp seconds)
 *   - activity: Activity type (String)
 *   - duration: Activity duration in seconds (Int)
 *
 * Event 5: Device List Changed
 * -----------------------------
 * Extras:
 *   - type: "DeviceList" (String)
 *   - devices: Connected device names, separated by "/" (String)
 *
 * ========================================
 * TECHNICAL NOTES
 * ========================================
 *
 * - All broadcasts use FLAG_INCLUDE_STOPPED_PACKAGES for Tasker compatibility
 * - ACTION_TERMINATE kills the process with Process.killProcess()
 * - ACTION_CLOSE_GUI uses moveTaskToBack() to hide without stopping
 * - Worker cancellation is asynchronous, receiver waits up to 2 seconds
 * - ConnectIQ SDK is properly shutdown before process termination
 *
 * ========================================
 * MANIFEST REQUIREMENTS
 * ========================================
 *
 * <receiver
 *     android:name=".ActivityStatusCheckReceiver"
 *     android:enabled="true"
 *     android:exported="true">
 *     <intent-filter>
 *         <action android:name="net.xrxss15.TERMINATE"/>
 *         <action android:name="net.xrxss15.OPEN_GUI"/>
 *         <action android:name="net.xrxss15.CLOSE_GUI"/>
 *     </intent-filter>
 * </receiver>
 */
class ActivityStatusCheckReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_EVENT = "net.xrxss15.GARMIN_ACTIVITY_LISTENER_EVENT"
        const val ACTION_TERMINATE = "net.xrxss15.TERMINATE"
        const val ACTION_OPEN_GUI = "net.xrxss15.OPEN_GUI"
        const val ACTION_CLOSE_GUI = "net.xrxss15.CLOSE_GUI"
        private const val TAG = "GarminActivityListener.Receiver"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        Log.i(TAG, "Received action: ${intent.action}")
        when (intent.action) {
            ACTION_TERMINATE -> {
                Log.i(TAG, "TERMINATE received - stopping worker and killing process")
                // Cancel worker
                WorkManager.getInstance(context).cancelUniqueWork("garmin_listener")
                // Wait for worker to stop
                val handler = Handler(Looper.getMainLooper())
                var checkCount = 0
                val checkRunnable = object : Runnable {
                    override fun run() {
                        checkCount++
                        val running = isListenerRunning(context)
                        if (!running || checkCount >= 20) {
                            // Reset SDK
                            ConnectIQService.resetInstance()
                            // Send terminated broadcast
                            val terminatedIntent = Intent(ACTION_EVENT).apply {
                                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                                putExtra("type", "Terminated")
                                putExtra("reason", "Tasker terminate")
                            }
                            context.sendBroadcast(terminatedIntent)
                            // Kill process completely
                            android.os.Process.killProcess(android.os.Process.myPid())
                        } else {
                            handler.postDelayed(this, 100)
                        }
                    }
                }
                handler.postDelayed(checkRunnable, 100)
            }
            ACTION_OPEN_GUI -> {
                Log.i(TAG, "OPEN_GUI received - launching MainActivity")
                val openIntent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                context.startActivity(openIntent)
            }
            ACTION_CLOSE_GUI -> {
                Log.i(TAG, "CLOSE_GUI received - closing GUI only")
                // Move app to background without stopping worker
                val moveBackIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(moveBackIntent)
            }
        }
    }

    /**
     * Check if the background worker is currently running.
     *
     * @param context Application context
     * @return true if worker is RUNNING or ENQUEUED, false otherwise
     */
    private fun isListenerRunning(context: Context): Boolean {
        return try {
            val workManager = WorkManager.getInstance(context)
            val workInfos = workManager.getWorkInfosForUniqueWork("garmin_listener").get()
            workInfos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
        } catch (e: Exception) {
            false
        }
    }
}