package com.agentmonitor.live;

import android.app.Application;
import android.content.res.Configuration;

/** Applies the saved server before any Activity, JobService or tracking service starts. */
public final class MonitorApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        ServerSettings.initialize(this);
        MonitorWidgetBridge.setListener(MonitorWidgetUpdater::refreshAll);
    }

    @Override public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        MonitorWidgetUpdater.refreshAll(this);
    }
}
