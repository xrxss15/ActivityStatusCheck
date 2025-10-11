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
 * SUPPORTED ACTIONS (FOR EXTERNAL USE)
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
 *    - If worker is running, GUI reconnects and shows history
 *    - If worker is not running, user can start it manually
 *    - Use case: User wants to view logs or status
 *
 * 3. ACTION_CLOSE_GUI (net.xrxss15.CLOSE_GUI)
 *    - Closes the MainActivity GUI via finishAndRemoveTask()
 *    - Removes app from recent apps list
 *    - Worker continues running in background
 *    - Listening for activity events continues
 *    - Use case: Hide GUI but keep monitoring
 *
 * 4. ACTION_PING (net.xrxss15.PING)
 *    - Queries worker health status
 *    - Worker responds with Pong broadcast
 *    - Returns worker start time in response
 *    - Use case: Health check from Tasker or monitoring
 *    - Requires: Package name must be specified in Tasker
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
 * Example 4: Ping worker health check
 * -----------------------------------
 * Task: "Check Worker Status"
 * Action: Send Intent
 *   - Action: net.xrxss15.PING
 *   - Package: net.xrxss15
 *   - Target: Broadcast Receiver
 *
 * Then listen for Pong response:
 * Profile: Event > Intent Received
 *   - Action: net.xrxss15.GARMIN_ACTIVITY_LISTENER_EVENT
 * 
 * Task: "Handle Pong"
 * If %type ~ Pong:
 *   - Variable %worker_start_time = worker start timestamp (Long, milliseconds)
 *   - Variable %receive_time = current time (Long, milliseconds)
 *   - Flash: "Worker running since %worker_start_time"
 *   - Calculate uptime: (%receive_time - %worker_start_time) / 1000 seconds
 *
 * Example 5: Listen for activity events
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
 *   - "Connected" = Device connected
 *   - "Disconnected" = Device disconnected
 *   - "Pong" = Response to Ping health check
 *
 * Example: Flash notification on activity start
 * If %type ~ Started:
 *   - Flash: "Activity started on %device"
 *   - Variable %device = device name (String)
 *   - Variable %activity = activity type (String)
 *   - Variable %time = activity start time (Long, Unix timestamp seconds)
 *   - Variable %receive_time = when app received message (Long, milliseconds)
 *
 * Example: Track activity duration
 * If %type ~ Stopped:
 *   - Flash: "%device completed %activity in %duration seconds"
 *   - Variable %duration = activity duration in seconds (Int)
 *
 * ========================================
 * BROADCAST EVENTS SENT BY APP
 * ========================================
 *
 * All events are sent with action: net.xrxss15.GARMIN_ACTIVITY_LISTENER_EVENT
 * All events use FLAG_INCLUDE_STOPPED_PACKAGES (receivable by Tasker)
 *
 * Event 1: Worker Created
 * -----------------------
 * Extras:
 *   - type: "Created" (String)
 *   - timestamp: Worker creation time (Long, milliseconds)
 *   - device_count: Number of devices connected (Int)
 *   - worker_start_time: Worker start timestamp (Long, milliseconds)
 *   - receive_time: Message receive time (Long, milliseconds)
 *
 * Event 2: Worker Terminated
 * ---------------------------
 * Extras:
 *   - type: "Terminated" (String)
 *   - reason: Reason for termination (String)
 *   - receive_time: Message receive time (Long, milliseconds)
 *
 * Event 3: Activity Started
 * --------------------------
 * Extras:
 *   - type: "Started" (String)
 *   - device: Device name (String)
 *   - time: Activity start time (Long, Unix timestamp seconds)
 *   - activity: Activity type like "running", "cycling" (String)
 *   - duration: Always 0 for start events (Int)
 *   - receive_time: When app received message (Long, milliseconds)
 *
 * Event 4: Activity Stopped
 * --------------------------
 * Extras:
 *   - type: "Stopped" (String)
 *   - device: Device name (String)
 *   - time: Activity start time (Long, Unix timestamp seconds)
 *   - activity: Activity type (String)
 *   - duration: Activity duration in seconds (Int)
 *   - receive_time: When app received message (Long, milliseconds)
 *
 * Event 5: Device Connected
 * -------------------------
 * Extras:
 *   - type: "Connected" (String)
 *   - device: Device name (String)
 *   - receive_time: When connection detected (Long, milliseconds)
 *
 * Event 6: Device Disconnected
 * ----------------------------
 * Extras:
 *   - type: "Disconnected" (String)
 *   - device: Device name (String)
 *   - receive_time: When disconnection detected (Long, milliseconds)
 *
 * Event 7: Pong (Health Check Response)
 * --------------------------------------
 * Extras:
 *   - type: "Pong" (String)
 *   - worker_start_time: Worker start timestamp (Long, milliseconds)
 *   - receive_time: Current time (Long, milliseconds)
 *
 * ========================================
 * INTERNAL ACTIONS (APP USE ONLY)
 * ========================================
 *
 * REQUEST_HISTORY (net.xrxss15.REQUEST_HISTORY)
 *   - Internal action used by MainActivity to request event history
 *   - Requires: Package name (internal communication only)
 *   - Worker responds with internal broadcasts (not receivable by Tasker)
 *   - History events are sent with setPackage() to MainActivity only
 *   - Use case: MainActivity displays cached events on reopen
 *
 * ========================================
 * TECHNICAL NOTES
 * ========================================
 *
 * Security Model:
 * - TERMINATE, OPEN_GUI, CLOSE_GUI: Exported in manifest, anyone can send
 * - PING, REQUEST_HISTORY: RECEIVER_NOT_EXPORTED, requires setPackage()
 * - All event broadcasts: Public (FLAG_INCLUDE_STOPPED_PACKAGES)
 * - History responses: Internal only (setPackage, only MainActivity receives)
 *
 * Broadcast Patterns:
 * - All event broadcasts use FLAG_INCLUDE_STOPPED_PACKAGES for Tasker compatibility
 * - ACTION_TERMINATE kills the process with Process.killProcess()
 * - ACTION_CLOSE_GUI sends broadcast to MainActivity which calls finishAndRemoveTask()
 * - Worker cancellation is asynchronous, receiver waits up to 2 seconds
 * - ConnectIQ SDK is properly shutdown before process termination
 *
 * Timestamp Formats:
 * - receive_time: When app received event (always milliseconds since epoch)
 * - time: Activity start time from watch (Unix seconds, only in Started/Stopped)
 * - worker_start_time: When worker started (milliseconds since epoch)
 * - timestamp: Same as receive_time (milliseconds since epoch)
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
                WorkManager.getInstance(context).cancelUniqueWork("garmin_listener")
                val handler = Handler(Looper.getMainLooper())
                var checkCount = 0
                val checkRunnable = object : Runnable {
                    override fun run() {
                        checkCount++
                        val running = isListenerRunning(context)
                        if (!running || checkCount >= 20) {
                            ConnectIQService.resetInstance()
                            val terminatedIntent = Intent(ACTION_EVENT).apply {
                                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                                putExtra("type", "Terminated")
                                putExtra("reason", "Tasker terminate")
                            }
                            context.sendBroadcast(terminatedIntent)
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
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or 
                             Intent.FLAG_ACTIVITY_CLEAR_TASK or
                             Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(openIntent)
            }
            ACTION_CLOSE_GUI -> {
                Log.i(TAG, "CLOSE_GUI received - finishing MainActivity")
                val closeIntent = Intent(ACTION_EVENT).apply {
                    addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                    putExtra("type", "CloseGUI")
                }
                context.sendBroadcast(closeIntent)
            }
        }
    }

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