package net.xrxss15

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/**
 * Broadcast receiver handling app control intents.
 * Manages worker lifecycle and app termination.
 * 
 * =============================================================================
 * TASKER INTEGRATION - INCOMING INTENTS (Control the app from Tasker)
 * =============================================================================
 * 
 * 1. OPEN_GUI - Open the app GUI
 *    Action: net.xrxss15.OPEN_GUI
 *    Target: Broadcast Receiver
 *    Package: net.xrxss15
 *    Effect: Opens MainActivity. If worker not running, starts it automatically.
 *    
 * 2. CLOSE_GUI - Close the app GUI (worker keeps running)
 *    Action: net.xrxss15.CLOSE_GUI
 *    Target: Broadcast Receiver
 *    Package: net.xrxss15
 *    Effect: Closes MainActivity, worker continues in background.
 *    
 * 3. TERMINATE - Completely stop the app (worker + GUI + process)
 *    Action: net.xrxss15.TERMINATE
 *    Target: Broadcast Receiver
 *    Package: net.xrxss15
 *    Class: net.xrxss15.ActivityStatusCheckReceiver
 *    Effect: Stops worker, closes GUI, terminates app gracefully.
 * 
 * =============================================================================
 * TASKER INTEGRATION - OUTGOING BROADCASTS (App sends these to Tasker)
 * =============================================================================
 * 
 * All outgoing broadcasts use:
 *   Action: net.xrxss15.GARMIN_ACTIVITY_LISTENER_EVENT
 *   
 * Event types (check %type variable in Tasker):
 * 
 * 1. "Created" - Worker started successfully and is now running
 *    Sent ONLY after successful SDK initialization and device discovery.
 *    Sent once when app starts and worker initializes successfully.
 *    Extras:
 *      - type: "Created"
 *      - timestamp: Long (milliseconds)
 *      - devices: String (device names separated by "/", empty if no devices)
 *    
 * 2. "Started" - Activity started on watch
 *    Extras:
 *      - type: "Started"
 *      - device: String (device name)
 *      - time: Long (unix timestamp in seconds)
 *      - activity: String (activity type, e.g., "Running", "Cycling")
 *      - duration: Int (0 for start event)
 *    
 * 3. "Stopped" - Activity stopped on watch
 *    Extras:
 *      - type: "Stopped"
 *      - device: String (device name)
 *      - time: Long (unix timestamp in seconds)
 *      - activity: String (activity type)
 *      - duration: Int (duration in seconds)
 *    
 * 4. "DeviceList" - Device connection status changed
 *    Sent when a device connects, disconnects, or is unpaired.
 *    Extras:
 *      - type: "DeviceList"
 *      - devices: String (device names separated by "/", empty if no devices)
 *    
 * 5. "Terminated" - Worker terminated (intentionally or due to error)
 *    Sent when worker stops (user exit, error, or crash).
 *    Extras:
 *      - type: "Terminated"
 *      - reason: String (termination reason)
 * 
 * =============================================================================
 * TASKER EXAMPLE PROFILES
 * =============================================================================
 * 
 * Example 1: Track app running state
 * -----------------------------------
 * Profile: Garmin Listener State
 *   Event: Intent Received
 *     Action: net.xrxss15.GARMIN_ACTIVITY_LISTENER_EVENT
 *   
 * Task: Update State Variable
 *   If %type = Created
 *     Variable Set %GarminListenerRunning = true
 *     Variable Set %GarminDevices = %devices
 *     Flash "Garmin Listener started with %devices"
 *   Else If %type = Terminated
 *     Variable Set %GarminListenerRunning = false
 *     Variable Clear %GarminDevices
 *     Flash "Garmin Listener stopped: %reason"
 * 
 * 
 * Example 2: React to activity start/stop
 * ----------------------------------------
 * Profile: Activity Events
 *   Event: Intent Received
 *     Action: net.xrxss15.GARMIN_ACTIVITY_LISTENER_EVENT
 *   
 * Task: Handle Activity Event
 *   If %type = Started
 *     Say "Activity started: %activity on %device"
 *     Variable Set %ActivityInProgress = true
 *   Else If %type = Stopped
 *     Say "Activity stopped after %duration seconds"
 *     Variable Set %ActivityInProgress = false
 * 
 * 
 * Example 3: Open app GUI
 * ------------------------
 * Profile: Show Garmin Status
 *   Context: Your trigger (e.g., button press)
 *   
 * Task: Open GUI
 *   Send Intent:
 *     Action: net.xrxss15.OPEN_GUI
 *     Package: net.xrxss15
 *     Target: Broadcast Receiver
 * 
 * 
 * Example 4: Close app GUI (keep worker running)
 * -----------------------------------------------
 * Profile: Hide Garmin GUI
 *   Context: Your trigger
 *   
 * Task: Close GUI
 *   Send Intent:
 *     Action: net.xrxss15.CLOSE_GUI
 *     Package: net.xrxss15
 *     Target: Broadcast Receiver
 * 
 * 
 * Example 5: Stop app completely
 * -------------------------------
 * Profile: Stop Garmin Listener
 *   Context: Your trigger (e.g., leaving home)
 *   
 * Task: Terminate App
 *   Send Intent:
 *     Action: net.xrxss15.TERMINATE
 *     Package: net.xrxss15
 *     Target: Broadcast Receiver
 *     Class: net.xrxss15.ActivityStatusCheckReceiver
 * 
 * 
 * Example 6: Monitor device connections
 * --------------------------------------
 * Profile: Garmin Device Status
 *   Event: Intent Received
 *     Action: net.xrxss15.GARMIN_ACTIVITY_LISTENER_EVENT
 *   
 * Task: Check Devices
 *   If %type = DeviceList
 *     Variable Set %ConnectedDevices = %devices
 *     If %devices ~R .+ (not empty)
 *       Flash "Garmin devices: %devices"
 *     Else
 *       Flash "No Garmin devices connected"
 * 
 * =============================================================================
 */
class ActivityStatusCheckReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_START_BACKGROUND = "net.xrxss15.internal.START"
        const val ACTION_OPEN_GUI = "net.xrxss15.OPEN_GUI"
        const val ACTION_CLOSE_GUI = "net.xrxss15.CLOSE_GUI"
        const val ACTION_TERMINATE = "net.xrxss15.TERMINATE"
        
        const val ACTION_EVENT = "net.xrxss15.GARMIN_ACTIVITY_LISTENER_EVENT"
        
        private const val TAG = "GarminActivityListener.Receiver"
        private const val WORKER_NAME = "garmin_listener"

        /**
         * Terminates the entire app including worker and UI.
         * Called when ACTION_TERMINATE intent is received.
         * 
         * Shutdown sequence:
         * 1. Cancel background worker
         * 2. Shutdown ConnectIQ SDK and reset singleton
         * 3. Close MainActivity UI with finishAndRemoveTask()
         * 4. Let Android manage process lifecycle naturally (no killProcess)
         * 
         * @param context Application context
         */
        fun terminateApp(context: Context) {
            Log.i(TAG, "Terminating app")
            
            WorkManager.getInstance(context).cancelUniqueWork(WORKER_NAME)
            ConnectIQService.resetInstance()
            
            val closeIntent = Intent(MainActivity.ACTION_CLOSE_GUI).apply {
                setPackage(context.packageName)
                putExtra("finish_and_remove_task", true)
            }
            context.sendBroadcast(closeIntent)
        }
    }

    /**
     * Handles incoming broadcast intents.
     * Routes control commands to appropriate handlers.
     */
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        when (intent.action) {
            ACTION_START_BACKGROUND -> {
                Log.i(TAG, "START_BACKGROUND received (internal)")
                startWorker(context)
            }
            
            ACTION_OPEN_GUI -> {
                Log.i(TAG, "OPEN_GUI received")
                val openIntent = Intent(context, MainActivity::class.java).apply {
                    action = MainActivity.ACTION_OPEN_GUI
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(openIntent)
            }
            
            ACTION_CLOSE_GUI -> {
                Log.i(TAG, "CLOSE_GUI received")
                val closeIntent = Intent(MainActivity.ACTION_CLOSE_GUI).apply {
                    setPackage(context.packageName)
                }
                context.sendBroadcast(closeIntent)
            }
            
            ACTION_TERMINATE -> {
                Log.i(TAG, "TERMINATE received")
                terminateApp(context)
            }
        }
    }

    /**
     * Starts the background worker with proper constraints.
     * Worker runs as foreground service with notification.
     * 
     * @param context Application context
     */
    private fun startWorker(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(false)
            .setRequiresCharging(false)
            .setRequiresDeviceIdle(false)
            .build()
        
        val workRequest = OneTimeWorkRequestBuilder<ConnectIQQueryWorker>()
            .setConstraints(constraints)
            .build()
        
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORKER_NAME,
            ExistingWorkPolicy.KEEP,
            workRequest
        )
    }
}