package com.agentmonitor.live;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.SystemClock;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Debug-only, app-private, bounded metadata. No URL, text, headers, or identity is accepted. */
public final class WebDiagnostics {
    public enum Phase { STARTED, COMMITTED, FINISHED, NETWORK_ERROR, HTTP_ERROR, SSL_ERROR }
    private static final JSONArray events = new JSONArray();
    private WebDiagnostics() {}

    // mainFrame: -1 means the platform callback does not provide that information (SSL).
    public static synchronized void write(Context context, Phase phase, long loadId, int mainFrame,
            boolean trustedOrigin, boolean documentMatch, boolean offline, int code) {
        if ((context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) return;
        AtomicFile file = new AtomicFile(new File(context.getFilesDir(), "webview-status.json"));
        FileOutputStream output = null;
        try {
            JSONObject event = new JSONObject().put("phase", phase.name().toLowerCase(java.util.Locale.ROOT))
                    .put("loadId", loadId).put("mainFrame", mainFrame).put("trustedOrigin", trustedOrigin)
                    .put("documentMatch", documentMatch).put("offline", offline).put("code", code)
                    .put("elapsedRealtime", SystemClock.elapsedRealtime());
            if (events.length() >= 16) events.remove(0);
            events.put(event);
            output = file.startWrite();
            output.write(new JSONObject().put("events", events).toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(output);
        } catch (Exception ignored) { if (output != null) file.failWrite(output); }
    }
}
