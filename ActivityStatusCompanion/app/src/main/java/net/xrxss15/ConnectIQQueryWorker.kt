package net.xrxss15

import android.content.Context
import android.content.Intent
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.ListenableWorker.Result

class ConnectIQQueryWorker(appContext: Context, params: WorkerParameters) : Worker(appContext, params) {

    override fun doWork(): Result {
        val ctx = applicationContext
        val svc = ConnectIQService.getInstance()

        val res = svc.queryActivityStatus(
            context = ctx,
            selected = null,
            showUiIfInitNeeded = false
        )

        val names = svc.getConnectedRealDevices(ctx).joinToString(", ") { it.friendlyName ?: "Unnamed" }
        svc.log("[WORKER] Devices found: $names")

        val out = Intent(ActivityStatusCheckReceiver.ACTION_RESULT).apply {
            putExtra(ActivityStatusCheckReceiver.EXTRA_SUCCESS, res.success)
            putExtra(ActivityStatusCheckReceiver.EXTRA_PAYLOAD, res.payload)
            putExtra(ActivityStatusCheckReceiver.EXTRA_DEBUG, res.debug)
            putExtra(ActivityStatusCheckReceiver.EXTRA_DEVICE_COUNT, res.connectedRealDevices)
        }
        ctx.sendBroadcast(out)

        return Result.success()
    }
}