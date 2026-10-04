package android.content;

import android.net.Uri;
import android.os.Bundle;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Controllable, local-only Xiaomi provider fixture; there is no Android IPC in these tests. */
public final class ContentResolver {
    public volatile Integer focusProtocol;
    public volatile Bundle focusResult;
    public volatile RuntimeException protocolFailure, focusFailure;
    public volatile int focusCalls, protocolCalls;
    public volatile Uri lastUri;
    public volatile String lastMethod, lastArg;
    public volatile Bundle lastExtras;
    public volatile QueryGate focusGate;

    public int getSystemInt(String name, int fallback) {
        if (!"notification_focus_protocol".equals(name)) throw new AssertionError("Unexpected system setting " + name);
        ++protocolCalls;
        RuntimeException failure = protocolFailure;
        if (failure != null) throw failure;
        Integer value = focusProtocol;
        return value == null ? fallback : value;
    }

    public Bundle call(Uri uri, String method, String arg, Bundle extras) {
        if (uri == null || !"content://miui.statusbar.notification.public".equals(uri.toString())
                || !"canShowFocus".equals(method)) throw new AssertionError("Unexpected content provider query");
        lastUri = uri; lastMethod = method; lastArg = arg;
        lastExtras = extras == null ? null : new Bundle(extras);
        ++focusCalls;
        // Snapshot before blocking so a delayed query delivers the observation it began with.
        Bundle result = focusResult;
        RuntimeException failure = focusFailure;
        QueryGate gate = focusGate;
        if (gate != null) {
            gate.entered.countDown();
            try {
                if (!gate.release.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Test did not release blocked focus query");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted test focus query", interrupted);
            }
        }
        if (failure != null) throw failure;
        return result == null ? null : new Bundle(result);
    }

    public QueryGate blockFocusQuery() {
        QueryGate gate = new QueryGate();
        focusGate = gate;
        return gate;
    }

    public static final class QueryGate {
        public final CountDownLatch entered = new CountDownLatch(1);
        public final CountDownLatch release = new CountDownLatch(1);
    }
}
