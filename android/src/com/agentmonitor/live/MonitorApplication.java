package com.agentmonitor.live;

import android.app.Application;

/** Applies the saved server before any Activity, JobService or tracking service starts. */
public final class MonitorApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        ServerSettings.initialize(this);
    }
}
