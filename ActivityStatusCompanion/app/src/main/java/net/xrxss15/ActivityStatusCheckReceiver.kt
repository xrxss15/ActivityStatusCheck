/*
 * ===============================================================================
 * GARMIN ACTIVITY LISTENER - Android Companion App
 * ===============================================================================
 * 
 * PURPOSE:
 *   Background listener for Garmin ConnectIQ watch activities.
 *   Receives activity start/stop events from Garmin watches and broadcasts
 *   them to Tasker for home automation integration.
 *
 * ARCHITECTURE:
 *   - MainActivity: UI for monitoring and configuration
 *   - ConnectIQService: Singleton managing Garmin SDK and device communication
 *   - ConnectIQQueryWorker: Long-running WorkManager worker for background listening
 *   - ActivityStatusCheckReceiver: BroadcastReceiver for control and message distribution
 *
 * COMMUNICATION FLOW:
 *   Watch (CIQ App) → Bluetooth → ConnectIQ SDK → ConnectIQService → 
 *   Worker/MainActivity → Broadcast → Tasker
 *
 * ===============================================================================
 * USAGE GUIDE
 * ===============================================================================
 *
 * 1. INITIAL SETUP
 * ----------------
 *   - Install app on Android phone
 *   - Grant all required permissions:
 *     * Location (for Bluetooth device discovery)
 *     * Bluetooth Scan/Connect
 *     * Post Notifications
 *   - Disable battery optimization (Settings → Battery Settings button)
 *   - Install CIQ app on Garmin watch via Garmin Connect
 *   - Pair watch with phone via Garmin Connect app
 *
 * 2. STARTING THE LISTENER
 * -------------------------
 *   METHOD 1 - App Launch (Recommended):
 *     - Launch "Garmin Listener" app
 *     - App auto-starts background worker
 *     - Persistent notification appears
 *
 *   METHOD 2 - Tasker (for automation):
 *     - Use Tasker action: Launch App → Garmin Listener
 *     - App will auto-start worker
 *
 * 3. STOPPING THE LISTENER
 * -------------------------
 *   METHOD 1 - Notification:
 *     - Tap "Exit" in persistent notification
 *     - Fully terminates app and worker
 *
 *   METHOD 2 - Tasker:
 *     - Send Intent action: net.xrxss15.GARMIN_ACTIVITY_LISTENER_TERMINATE
 *     - Target: Broadcast Receiver
 *     - Package: net.xrxss15
 *
 * 4. RECEIVING ACTIVITY EVENTS IN TASKER
 * ---------------------------------------
 *   Create Event Profile:
 *     Event: Intent Received
 *     Action: net.xrxss15.GARMIN_ACTIVITY_LISTENER_EVENT
 *
 * ===============================================================================
 * BROADCAST INTENTS REFERENCE
 * ===============================================================================
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * OUTBOUND (App → Tasker):
 * ─────────────────────────────────────────────────────────────────────────────
 * 
 * ALL events use action: net.xrxss15.GARMIN_ACTIVITY_LISTENER_EVENT
 * Differentiate by checking the "type" extra
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * EVENT 1: Activity Started
 * ─────────────────────────────────────────────────────────────────────────────
 *   When: Watch activity starts
 *
 *   Extras:
 *     type     (String) = "Started"
 *     device   (String) = Device name (e.g., "Fenix 7S")
 *     time     (Long)   = Unix timestamp in seconds
 *     activity (String) = Activity type (e.g., "Running", "Cycling")
 *     duration (Int)    = 0 (always 0 for started events)
 *
 *   Tasker Variables:
 *     %type = "Started"
 *     %device = "Fenix 7S"
 *     %time = 1728405123
 *     %activity = "Running"
 *     %duration = 0
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * EVENT 2: Activity Stopped
 * ─────────────────────────────────────────────────────────────────────────────
 *   When: Watch activity stops
 *
 *   Extras:
 *     type     (String) = "Stopped"
 *     device   (String) = Device name (e.g., "Fenix 7S")
 *     time     (Long)   = Unix timestamp in seconds
 *     activity (String) = Activity type (e.g., "Running", "Cycling")
 *     duration (Int)    = Activity duration in seconds
 *
 *   Tasker Variables:
 *     %type = "Stopped"
 *     %device = "Fenix 7S"
 *     %time = 1728405456
 *     %activity = "Running"
 *     %duration = 333
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * EVENT 3: Device List Updated
 * ─────────────────────────────────────────────────────────────────────────────
 *   When: Device connects/disconnects or on initial connection
 *
 *   Extras:
 *     type    (String) = "DeviceList"
 *     devices (String) = Device names separated by "/" 
 *                        Examples: "Fenix 7S"
 *                                  "Fenix 7S/Edge 1040"
 *                                  "" (empty = no devices)
 *
 *   Tasker Variables:
 *     %type = "DeviceList"
 *     %devices = "Fenix 7S/Edge 1040"
 *
 *   Parse multiple devices:
 *     Variable Split: %devices
 *     Splitter: /
 *     Result: %devices1, %devices2, %devices3, ...
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * EVENT 4: Worker Terminated
 * ─────────────────────────────────────────────────────────────────────────────
 *   When: Background worker stops (normal or error)
 *
 *   Extras:
 *     type   (String) = "Terminate"
 *     reason (String) = Termination reason
 *                       Examples: "Stopped"
 *                                 "SDK not initialized"
 *
 *   Tasker Variables:
 *     %type = "Terminate"
 *     %reason = "Stopped"
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * INBOUND (Tasker → App):
 * ─────────────────────────────────────────────────────────────────────────────
 * 
 * ACTION: net.xrxss15.GARMIN_ACTIVITY_LISTENER_TERMINATE
 *   Purpose: Fully terminate app and worker
 *   Target: Broadcast Receiver
 *   Package: net.xrxss15
 *   Extras: None
 *
 *   Tasker Configuration:
 *     Action: Send Intent
 *     Action: net.xrxss15.GARMIN_ACTIVITY_LISTENER_TERMINATE
 *     Target: Broadcast Receiver
 *     Package: net.xrxss15
 *
 * ===============================================================================
 */

package net.xrxss15

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

class ActivityStatusCheckReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "GarminActivityListener.Receiver"
        
        // INTERNAL INTENTS - Used by MainActivity
        const val ACTION_START = "net.xrxss15.internal.START"
        const val ACTION_STOP = "net.xrxss15.internal.STOP"
        
        // PUBLIC INTENT - For Tasker to terminate app completely
        const val ACTION_TERMINATE = "net.xrxss15.GARMIN_ACTIVITY_LISTENER_TERMINATE"
        
        // OUTBOUND BROADCAST - Sent to Tasker when events occur
        const val ACTION_EVENT = "net.xrxss15.GARMIN_ACTIVITY_LISTENER_EVENT"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "Received intent: ${intent.action}")
        
        when (intent.action) {
            ACTION_START -> {
                Log.i(TAG, "Starting listener (internal)")
                startListener(context)
            }
            
            ACTION_STOP -> {
                Log.i(TAG, "Stopping listener (internal)")
                stopListener(context)
            }
            
            ACTION_TERMINATE -> {
                Log.i(TAG, "Terminating app completely (Tasker)")
                terminateApp(context)
            }
        }
    }

    private fun startListener(context: Context) {
        Log.i(TAG, "Starting Garmin listener")
        
        val workRequest = OneTimeWorkRequestBuilder<ConnectIQQueryWorker>().build()
        
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(
                "garmin_listener",
                ExistingWorkPolicy.KEEP,
                workRequest
            )
        
        Log.i(TAG, "Listener started")
    }

    private fun stopListener(context: Context) {
        Log.i(TAG, "Stopping Garmin listener")
        
        WorkManager.getInstance(context.applicationContext)
            .cancelUniqueWork("garmin_listener")
        
        Log.i(TAG, "Listener stopped")
    }

    private fun terminateApp(context: Context) {
        Log.i(TAG, "Terminating entire app")
        
        // Stop worker first
        stopListener(context)
        
        // Give worker time to stop cleanly
        Thread.sleep(300)
        
        // Shutdown SDK with application context (never becomes null)
        try {
            ConnectIQService.getInstance().shutdown()
        } catch (e: Exception) {
            Log.e(TAG, "Error during shutdown: ${e.message}")
        }
        
        // Kill app process
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}