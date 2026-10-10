package com.agentmonitor.live;
import android.content.Context;
import android.net.Uri;
import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;
/** Records handoff and cleanup requests without granting real installer or file access. */
final class UpdateInstallProvider {
    static final AtomicInteger discardCalls=new AtomicInteger(),grants=new AtomicInteger();
    static void prune(Context c) {
    }
    static boolean discardUnshared(Context c,File f) {
        discardCalls.incrementAndGet();
        return false;
    }
    static Uri grant(Context c,UpdatePackage.Verified v) {
        grants.incrementAndGet();
        return Uri.parse("content://synthetic-local-test/candidate");
    }
}
