package com.agentmonitor.live;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
public final class StopReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent != null) TrackingService.requestNotificationStop(context, intent.getLongExtra("generation", -1),
                intent.getStringExtra(TrackingService.EXTRA_SESSION));
    }
}
