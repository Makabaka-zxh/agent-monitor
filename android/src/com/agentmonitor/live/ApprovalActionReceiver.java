package com.agentmonitor.live;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Dispatches explicit one-tap notification approval/denial back to the active TrackingService. */
public final class ApprovalActionReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        TrackingService.requestNotificationApproval(
                context,
                intent.getLongExtra("generation", -1),
                intent.getStringExtra(TrackingService.EXTRA_SESSION),
                intent.getStringExtra("request_id"),
                intent.getStringExtra("revision"),
                intent.getStringExtra("decision"));
    }
}
