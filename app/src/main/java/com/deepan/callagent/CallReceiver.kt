package com.deepan.callagent

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log

/** Catches phone state changes and passes them to the running agent service. */
class CallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        if (!CallAgentService.running) return   // agent is not started

        val svc = Intent(context, CallAgentService::class.java).apply {
            action = CallAgentService.ACTION_CALL
            putExtra("state", intent.getStringExtra(TelephonyManager.EXTRA_STATE))
            putExtra("number", intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER))
        }
        try {
            context.startService(svc)
        } catch (e: Exception) {
            Log.e("CallReceiver", "Could not reach service", e)
        }
    }
}
