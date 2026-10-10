package com.agentmonitor.live;
import android.content.Context;
import java.io.*;
/** Deterministic transport boundary with local fixtures and no network implementation. */
final class UpdateClient {
    interface Progress {
        void downloaded(long bytes,long total);
    }
    static final class Operation implements AutoCloseable {
        volatile boolean closed;
        Operation(long b) {
        }
        void check() throws IOException {
            if(closed||Thread.currentThread().isInterrupted())throw new IOException("操作已取消");
        }
        public void close() {
            closed=true;
        }
    }
    static final class Release {
        final UpdatePolicy.Manifest manifest;
        final String tag,apkUrl,notes;
        Release(UpdatePolicy.Manifest m,String t,String u,String n) {
            manifest=m;
            tag=t;
            apkUrl=u;
            notes=n;
        }
    }
    static Release selected;
    static File downloadFile;
    static Release check(Operation o) throws Exception {
        o.check();
        return selected;
    }
    static File download(Context c,Release r,Operation o,Progress p) throws Exception {
        o.check();
        p.downloaded(downloadFile.length(),downloadFile.length());
        return downloadFile;
    }
    static String hex(byte[] b) {
        StringBuilder s=new StringBuilder();
        for(byte v:b)s.append(String.format(java.util.Locale.ROOT,"%02x",v&255));
        return s.toString();
    }
}
