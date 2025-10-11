# Garmin Activity Listener - Intent API Documentation

This document describes all intents supported by the Garmin Activity Listener app for external automation tools like Tasker.

---

## Table of Contents

- [Overview](#overview)
- [Security Model](#security-model)
- [Supported Actions (Commands)](#supported-actions-commands)
- [Broadcast Events (Notifications)](#broadcast-events-notifications)
- [Tasker Integration Examples](#tasker-integration-examples)
- [Technical Notes](#technical-notes)

---

## Overview

The Garmin Activity Listener app provides an intent-based API for external control and monitoring. All control actions are handled by the background worker.

**Key Principles:**
- All control actions use `RECEIVER_EXPORTED` (no package name required)
- All event broadcasts use `FLAG_INCLUDE_STOPPED_PACKAGES` for Tasker compatibility
- The worker must be running to receive control actions
- Events are broadcast publicly and can be received by any app

---

## Security Model

### Control Actions (Incoming)
All control actions are registered dynamically by the worker at runtime with `RECEIVER_EXPORTED`. This means:
- **External apps can send** these intents without specifying package
- **Worker must be running** to receive control actions
- **Not declared in AndroidManifest.xml** (registered at runtime)

### Event Broadcasts (Outgoing)
All event broadcasts are public with `FLAG_INCLUDE_STOPPED_PACKAGES`:
- **Any app can receive** these broadcasts (including Tasker)
- **Broadcasts continue even if receiving app is stopped**
- **No permission required** to receive events

---

## Supported Actions (Commands)

These actions control the app and are sent **TO** the app from external tools like Tasker.

### 1. TERMINATE
**Action:** `net.xrxss15.TERMINATE`

**Description:**
- Stops the background worker
- Shuts down the ConnectIQ SDK
- Kills the app process completely
- Next app launch will be a fresh start

**Use Case:** User wants to completely exit the app

**Tasker Example:**
```
Task: "Stop Garmin Listener"
Action: Send Intent
  - Action: net.xrxss15.TERMINATE
  - Target: Broadcast Receiver
```

---

### 2. OPEN_GUI
**Action:** `net.xrxss15.OPEN_GUI`

**Description:**
- Shows a notification to open the MainActivity
- Automatically expands notification shade (if permission granted)
- User must tap the notification to open the app
- Workaround for Android 10+ background activity restrictions

**Use Case:** User wants to view logs or status

**Tasker Example:**
```
Task: "Show Garmin Listener"
Action: Send Intent
  - Action: net.xrxss15.OPEN_GUI
  - Target: Broadcast Receiver
```

**Note:** Due to Android 10+ restrictions, apps cannot be opened directly from background. The notification provides a silent, user-friendly way to open the app.

---

### 3. CLOSE_GUI
**Action:** `net.xrxss15.CLOSE_GUI`

**Description:**
- Closes the MainActivity GUI via `finishAndRemoveTask()`
- Removes app from recent apps list
- Worker continues running in background
- Listening for activity events continues

**Use Case:** Hide GUI but keep monitoring

**Tasker Example:**
```
Task: "Hide Garmin Listener"
Action: Send Intent
  - Action: net.xrxss15.CLOSE_GUI
  - Target: Broadcast Receiver
```

---

### 4. PING
**Action:** `net.xrxss15.PING`

**Description:**
- Queries worker health status
- Worker responds with Pong broadcast (see Event 7)
- Returns worker start time in response

**Use Case:** Health check from Tasker or monitoring

**Tasker Example:**
```
Task: "Check Worker Status"
Action: Send Intent
  - Action: net.xrxss15.PING
  - Target: Broadcast Receiver
```

**Response:** See [Event 7: Pong](#7-pong-health-check-response)

---

### 5. REQUEST_HISTORY (Internal Use Only)
**Action:** `net.xrxss15.REQUEST_HISTORY`

**Description:**
- Internal action used by MainActivity to request event history
- Worker responds with internal broadcasts (not receivable by Tasker)
- History events are sent with `setPackage()` to MainActivity only

**Use Case:** MainActivity displays cached events on reopen

**Note:** This is for internal app communication only. External apps cannot receive history responses.

---

## Broadcast Events (Notifications)

These events are broadcast **FROM** the app and can be received by external tools like Tasker.

**Common Action for All Events:** `net.xrxss15.GARMIN_ACTIVITY_LISTENER_EVENT`

All events include:
- `type` (String) - Event type identifier
- `receive_time` (Long) - When app received/processed the event (milliseconds since epoch)

### 1. Worker Created
**Sent when:** Background worker starts

**Extras:**
- `type`: `"Created"` (String)
- `timestamp`: Worker creation time (Long, milliseconds)
- `device_count`: Number of devices connected (Int)
- `worker_start_time`: Worker start timestamp (Long, milliseconds)
- `receive_time`: Message receive time (Long, milliseconds)

**Tasker Variable Examples:**
- `%type` = `"Created"`
- `%device_count` = `2`
- `%worker_start_time` = `1728648123456`

---

### 2. Worker Terminated
**Sent when:** Background worker stops

**Extras:**
- `type`: `"Terminated"` (String)
- `reason`: Reason for termination (String)
- `receive_time`: Message receive time (Long, milliseconds)

**Tasker Variable Examples:**
- `%type` = `"Terminated"`
- `%reason` = `"Worker cancelled"` or `"Tasker terminate"`

---

### 3. Activity Started
**Sent when:** User starts an activity on their Garmin device

**Extras:**
- `type`: `"Started"` (String)
- `device`: Device name (String)
- `time`: Activity start time from watch (Long, **Unix timestamp in SECONDS**)
- `activity`: Activity type like `"running"`, `"cycling"` (String)
- `duration`: Always `0` for start events (Int)
- `receive_time`: When app received message (Long, milliseconds)

**Tasker Variable Examples:**
- `%type` = `"Started"`
- `%device` = `"fenix 7S"`
- `%activity` = `"running"`
- `%time` = `1728648123` (Unix seconds)

**Important:** `time` is in **seconds** (Unix timestamp), while `receive_time` is in **milliseconds**.

---

### 4. Activity Stopped
**Sent when:** User stops an activity on their Garmin device

**Extras:**
- `type`: `"Stopped"` (String)
- `device`: Device name (String)
- `time`: Activity start time from watch (Long, **Unix timestamp in SECONDS**)
- `activity`: Activity type (String)
- `duration`: Activity duration in seconds (Int)
- `receive_time`: When app received message (Long, milliseconds)

**Tasker Variable Examples:**
- `%type` = `"Stopped"`
- `%device` = `"fenix 7S"`
- `%activity` = `"running"`
- `%duration` = `3615` (1 hour, 15 seconds)

---

### 5. Device Connected
**Sent when:** A Garmin device connects via Bluetooth

**Extras:**
- `type`: `"Connected"` (String)
- `device`: Device name (String)
- `receive_time`: When connection detected (Long, milliseconds)

**Tasker Variable Examples:**
- `%type` = `"Connected"`
- `%device` = `"fenix 7S"`

---

### 6. Device Disconnected
**Sent when:** A Garmin device disconnects

**Extras:**
- `type`: `"Disconnected"` (String)
- `device`: Device name (String)
- `receive_time`: When disconnection detected (Long, milliseconds)

**Tasker Variable Examples:**
- `%type` = `"Disconnected"`
- `%device` = `"fenix 7S"`

---

### 7. Pong (Health Check Response)
**Sent when:** App receives a PING action

**Extras:**
- `type`: `"Pong"` (String)
- `worker_start_time`: Worker start timestamp (Long, milliseconds)
- `receive_time`: Current time (Long, milliseconds)

**Tasker Variable Examples:**
- `%type` = `"Pong"`
- `%worker_start_time` = `1728648123456`
- `%receive_time` = `1728651723456`

**Calculate Uptime:**
```
Uptime in seconds = (%receive_time - %worker_start_time) / 1000
```

---

## Tasker Integration Examples

### Example 1: Listen for All Events
```
Profile: "Garmin Event Listener"
  Event > Intent Received
    - Action: net.xrxss15.GARMIN_ACTIVITY_LISTENER_EVENT

Task: "Handle Garmin Events"
  Variable Set: %event_type To %type

  If %event_type ~ Started
    Flash: "Activity started on %device: %activity"
  End If

  If %event_type ~ Stopped
    Variable Set: %hours To %duration / 3600
    Variable Set: %mins To (%duration %% 3600) / 60
    Flash: "%device completed %activity (%hours:%mins)"
  End If

  If %event_type ~ Connected
    Flash: "%device connected"
  End If

  If %event_type ~ Disconnected
    Flash: "%device disconnected"
  End If

  If %event_type ~ Pong
    Variable Set: %uptime To (%receive_time - %worker_start_time) / 1000
    Flash: "Worker uptime: %uptime seconds"
  End If
```

---

### Example 2: Ping Worker Every 5 Minutes
```
Profile: "Worker Health Check"
  Time: Every 5 minutes

Task: "Ping Garmin Listener"
  Send Intent
    - Action: net.xrxss15.PING
    - Target: Broadcast Receiver
```

Then listen for Pong:
```
Profile: "Pong Response"
  Event > Intent Received
    - Action: net.xrxss15.GARMIN_ACTIVITY_LISTENER_EVENT

Task: "Check Pong"
  If %type ~ Pong
    Flash: "Worker is healthy!"
  End If
```

---

### Example 3: Auto-Start on Boot
```
Profile: "Auto-Start Garmin Listener"
  Event > Device Boot

Task: "Start Garmin Listener"
  Launch App: Garmin Activity Listener
  Wait: 5 seconds
  Send Intent
    - Action: net.xrxss15.CLOSE_GUI
    - Target: Broadcast Receiver
```

---

### Example 4: Log Activity Duration to File
```
Profile: "Log Activities"
  Event > Intent Received
    - Action: net.xrxss15.GARMIN_ACTIVITY_LISTENER_EVENT

Task: "Log to File"
  If %type ~ Stopped
    Variable Set: %timestamp To %TIMES
    Variable Set: %hours To %duration / 3600
    Variable Set: %mins To (%duration %% 3600) / 60
    Variable Set: %secs To %duration %% 60
    Variable Set: %log_entry To [%timestamp] %device - %activity - %hours:%mins:%secs
    Write File: %log_entry To: Tasker/garmin_log.txt Append: true
  End If
```

---

### Example 5: Control App Remotely
```
Task: "Start Monitoring"
  Launch App: Garmin Activity Listener
  Wait: 2 seconds
  Send Intent
    - Action: net.xrxss15.CLOSE_GUI
    - Target: Broadcast Receiver

Task: "Stop Monitoring"
  Send Intent
    - Action: net.xrxss15.TERMINATE
    - Target: Broadcast Receiver

Task: "Show GUI"
  Send Intent
    - Action: net.xrxss15.OPEN_GUI
    - Target: Broadcast Receiver
```

---

## Technical Notes

### Timestamp Formats
- **`receive_time`**: Always in milliseconds since epoch (System.currentTimeMillis())
- **`time`**: Activity start time in **seconds** since epoch (Unix timestamp from watch)
- **`worker_start_time`**: Milliseconds since epoch
- **`timestamp`**: Same as receive_time (milliseconds since epoch)

### Converting Timestamps
```
Milliseconds to seconds: divide by 1000
Seconds to milliseconds: multiply by 1000
Unix timestamp to human readable: use SimpleDateFormat
```

### Security Considerations
- All control actions use `RECEIVER_EXPORTED` (no package restriction needed)
- Worker receiver is registered at runtime (not in manifest)
- Event broadcasts are public (FLAG_INCLUDE_STOPPED_PACKAGES) for Tasker compatibility
- History responses are internal-only (setPackage, only MainActivity receives)

### Broadcast Delivery
- All event broadcasts use `FLAG_INCLUDE_STOPPED_PACKAGES`
- This ensures Tasker receives events even when in stopped state
- No special permissions required to receive broadcasts
- Broadcasts are asynchronous and may have slight delays

### Worker Lifecycle
- Worker registers control receiver on startup with `RECEIVER_EXPORTED`
- Control actions work from any external app
- If worker is stopped, control actions will be silently ignored
- Send PING periodically to verify worker is running

### Error Handling
- If control action fails, check logcat for error messages
- Worker logs all received actions with tag `GarminActivityListener.Worker`
- MainActivity logs with tag `MainActivity`
- Use `adb logcat | grep GarminActivityListener` to monitor

### Background Restrictions
- Android 10+ restricts background activity launches
- OPEN_GUI shows notification instead of direct launch
- Requires EXPAND_STATUS_BAR permission for auto-expand
- User must tap notification to open app

---

## Troubleshooting

### Control Actions Not Working
1. Verify worker is running: Send PING and listen for Pong
2. Check Target is set to "Broadcast Receiver" in Tasker
3. Check logcat for error messages
4. Ensure app is not battery optimized

### Not Receiving Events in Tasker
1. Check Intent Filter action is exactly: `net.xrxss15.GARMIN_ACTIVITY_LISTENER_EVENT`
2. Verify Tasker has monitoring enabled
3. Test with a simple Flash action to verify reception
4. Check battery optimization settings (may affect event delivery)

### Pong Not Received
1. Verify PING action is correct: `net.xrxss15.PING`
2. Check worker is running (GUI should show status)
3. Check Tasker profile is listening for correct action
4. Use logcat to verify Pong is sent: `Pong sent: worker_start_time=...`

### OPEN_GUI Not Working
1. Notification should appear - check if notification permission is granted
2. Tap the notification to open app (cannot auto-open on Android 10+)
3. Check EXPAND_STATUS_BAR permission in manifest
4. Notification shade should expand automatically (may require manual swipe on some devices)

---

## Appendix: All Intent Actions

### Control Actions (Send TO App)
- `net.xrxss15.TERMINATE` - Stop worker and exit
- `net.xrxss15.OPEN_GUI` - Show notification to open MainActivity
- `net.xrxss15.CLOSE_GUI` - Close MainActivity
- `net.xrxss15.PING` - Health check
- `net.xrxss15.REQUEST_HISTORY` - Request event history (internal only)

### Event Broadcasts (Receive FROM App)
All use action: `net.xrxss15.GARMIN_ACTIVITY_LISTENER_EVENT`

Event types (check `%type` extra):
- `Created` - Worker started
- `Terminated` - Worker stopped
- `Started` - Activity started
- `Stopped` - Activity stopped
- `Connected` - Device connected
- `Disconnected` - Device disconnected
- `Pong` - Health check response

---

## Version History

- **v1.1** (2025-10-11): Updated for RECEIVER_EXPORTED
  - Removed package name requirement
  - Updated OPEN_GUI to use notification
  - Added EXPAND_STATUS_BAR permission
  - Complete Tasker integration examples

- **v1.0** (2025-10-11): Initial API documentation

---

## Support

For issues or questions:
1. Check logcat: `adb logcat | grep GarminActivityListener`
2. Verify worker is running in app GUI
3. Test individual intents from Tasker
4. Review Tasker profile configuration

---

**End of Documentation**