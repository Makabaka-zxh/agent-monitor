package com.agentmonitor.live;

import android.app.job.JobParameters;
import android.app.job.JobService;
import org.json.JSONObject;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;

/** System-scheduled, bounded HTTPS check using the existing native credential. */
public final class UsageAlertJobService extends JobService {
    private final ConcurrentHashMap<JobParameters, AtomicBoolean> runs = new ConcurrentHashMap<>();
    @Override public boolean onStartJob(JobParameters params) {
        AtomicBoolean cancel = new AtomicBoolean();
        AtomicBoolean previous = runs.put(params, cancel); if (previous != null) previous.set(true);
        new Thread(() -> {
            try {
                String token = UsageAlerts.connectedToken(this);
                if (!cancel.get() && UsageAlerts.enabled(this)) {
                    JSONObject usage = NativeApi.call("GET", "/api/native/usage", null, token);
                    if (!cancel.get() && token.equals(UsageAlerts.connectedToken(this))) UsageAlerts.observe(this, usage, token);
                }
            } catch (NativeApi.Failure failure) {
                // Never retry writes or log credentials. A future periodic check retries reads.
            } finally { runs.remove(params, cancel); if (!cancel.get()) jobFinished(params, false); }
        }, "MonitorUsageCheck").start();
        return true;
    }
    @Override public boolean onStopJob(JobParameters params) {
        AtomicBoolean run = runs.remove(params); if (run != null) run.set(true); return false;
    }
    @Override public void onDestroy() {
        for (AtomicBoolean run : runs.values()) run.set(true);
        runs.clear(); super.onDestroy();
    }
}
