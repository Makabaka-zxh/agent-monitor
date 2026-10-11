package com.agentmonitor.live;
import android.content.Context;
import android.os.Handler;
import java.io.*;
/** Deterministic transport boundary with local fixtures and no network implementation. */
final class UpdateClient {
    static final class RateLimitedException extends IOException {
        RateLimitedException() {super("GitHub 暂时限制请求，请稍后重试");}
    }
    static volatile int checkCalls,downloadCalls;
    static volatile long retryDeadline,checkRetryMillis,downloadRetryMillis;
    static void reset() {
        checkCalls=0;downloadCalls=0;retryDeadline=0;checkRetryMillis=0;downloadRetryMillis=0;
    }
    static void setRetryRemainingMillis(long millis) {retryDeadline=Handler.uptime()+Math.max(0,millis);}
    static long retryRemainingMillis() {return Math.max(0,retryDeadline-Handler.uptime());}
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
        checkCalls++;
        o.check();
        if(checkRetryMillis>0) {setRetryRemainingMillis(checkRetryMillis);throw new RateLimitedException();}
        return selected;
    }
    static File download(Context c,Release r,Operation o,Progress p) throws Exception {
        downloadCalls++;
        o.check();
        if(downloadRetryMillis>0) {setRetryRemainingMillis(downloadRetryMillis);throw new RateLimitedException();}
        p.downloaded(downloadFile.length(),downloadFile.length());
        return downloadFile;
    }
    static String hex(byte[] b) {
        StringBuilder s=new StringBuilder();
        for(byte v:b)s.append(String.format(java.util.Locale.ROOT,"%02x",v&255));
        return s.toString();
    }
}
