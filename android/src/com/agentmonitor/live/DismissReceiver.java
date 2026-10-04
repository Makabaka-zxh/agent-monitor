package com.agentmonitor.live;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** A matching dismissal waits for an authorized system reason, or ends phone tracking. */
public final class DismissReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent != null) TrackingService.requestDismiss(context, intent.getLongExtra("generation", -1),
                intent.getStringExtra(TrackingService.EXTRA_SESSION), intent.getStringExtra(TrackingService.EXTRA_PRESENTATION));
    }
}
