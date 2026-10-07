package com.agentmonitor.live;

import java.io.File;
import java.io.IOException;

/** Saved-instance hints only. A restored candidate is never a verified package. */
final class UpdateRecoveryPolicy {
    // Match private-download pruning; installer grants keep their existing separate policy.
    static final long MAX_AGE_MS = 60 * 60 * 1000L;
    static final class Candidate {
        final String basename;
        final UpdatePolicy.Manifest manifest;
        final long completedAt;
        final boolean awaitingPermission;
        private Candidate(String basename, UpdatePolicy.Manifest manifest, long completedAt, boolean awaitingPermission) {
            if (basename == null || !basename.matches("update-[A-Za-z0-9_-]{1,100}\\.apk") || manifest == null || completedAt <= 0)
                throw new IllegalArgumentException("Invalid saved update");
            this.basename = basename; this.manifest = manifest; this.completedAt = completedAt; this.awaitingPermission = awaitingPermission;
        }
        boolean fresh(long now) { return now >= completedAt && now - completedAt < MAX_AGE_MS; }
        String[] encode(boolean waiting) {
            return new String[]{"1", basename, Long.toString(completedAt), Boolean.toString(waiting),
                    Integer.toString(manifest.schema), manifest.packageName, manifest.versionName,
                    Integer.toString(manifest.versionCode), Integer.toString(manifest.minSdk), manifest.apkName,
                    manifest.sha256, Long.toString(manifest.size)};
        }
        File file(File cacheDir) throws IOException {
            File directory = new File(cacheDir.getCanonicalFile(), "updates");
            File file = new File(directory, basename);
            // Neither a saved path nor a symlink may redirect restore/cleanup outside our fixed cache location.
            if (!directory.equals(directory.getCanonicalFile()) || !file.equals(file.getCanonicalFile()))
                throw new IOException("安装包大小或位置无效");
            return file;
        }
        void discard(File cacheDir) { try { File file = file(cacheDir); if (file.isFile()) file.delete(); } catch (IOException | RuntimeException ignored) { } }
    }
    static Candidate capture(String basename, UpdatePolicy.Manifest manifest, long completedAt) {
        return new Candidate(basename, manifest, completedAt, false);
    }
    static Candidate decode(String[] values) {
        if (values == null || values.length != 12) return null;
        try {
            if (!"1".equals(values[0]) || !("true".equals(values[3]) || "false".equals(values[3]))) return null;
            UpdatePolicy.Manifest manifest = new UpdatePolicy.Manifest(integer(values[4]), values[5], values[6],
                    integer(values[7]), integer(values[8]), values[9], values[10], number(values[11]));
            return new Candidate(values[1], manifest, number(values[2]), Boolean.parseBoolean(values[3]));
        } catch (IllegalArgumentException invalid) { return null; }
    }
    private static long number(String value) {
        if (value == null || !value.matches("[0-9]{1,19}")) throw new IllegalArgumentException("Invalid saved update");
        return Long.parseLong(value);
    }
    private static int integer(String value) {
        long result = number(value); if (result > Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid saved update"); return (int) result;
    }
    private UpdateRecoveryPolicy() { }
}
