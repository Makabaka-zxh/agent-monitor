package com.agentmonitor.live;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runs the production cleanup entry point, with real private files and synthetic grant reads. */
public final class UpdateInstallProviderTest {
    private static int checks, symlinkCasesSkipped, sequence;
    private static File root, cache, updates;
    private static Context context;
    private static final List<File> owned = new ArrayList<>();
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new AssertionError("Supply an isolated generated test directory");
        File parent = new File(args[0]);
        check(parent.mkdirs() || parent.isDirectory(), "isolated test parent available");
        root = Files.createTempDirectory(parent.toPath(), "provider-cleanup-").toFile();
        cache = new File(root, "cache"); updates = new File(cache, "updates");
        check(updates.mkdirs(), "isolated cache created");
        context = new Context(cache);
        try { grantLifetime(); scope(); synchronizedGrant(); symlinks(); }
        finally { for (File item : owned) if (item.exists()) check(item.delete(), "only generated test item removed"); }
        System.out.println("UpdateInstallProviderTest: " + checks + " checks passed; " + symlinkCasesSkipped + " symlink cases unavailable on host");
    }
    private static File file(File parent, String name) throws Exception {
        File value = new File(parent, name); check(value.createNewFile(), "fresh generated fixture"); owned.add(value);
        Files.write(value.toPath(), new byte[]{1, 2, 3}); return value;
    }
    private static File candidate() throws Exception { return file(updates, "update-owned_" + (++sequence) + ".apk"); }
    private static void grantLifetime() throws Exception {
        long now = System.currentTimeMillis();
        File shared = candidate(); check(shared.setLastModified(now - 7200000), "old download timestamp set");
        context.setSyntheticGrant(shared.getName(), now + 60000);
        check(!UpdateInstallProvider.discardUnshared(context, shared) && shared.isFile(), "active installer grant protects even an old candidate");
        Files.write(shared.toPath(), new byte[]{9});
        check(!UpdateInstallProvider.discardUnshared(context, shared) && shared.isFile(), "failed candidate verification cannot delete an active installer file");
        context.setSyntheticGrant(shared.getName(), now - 1);
        check(UpdateInstallProvider.discardUnshared(context, shared) && !shared.exists(), "expired grant permits exact-file cleanup");

        File other = candidate(), removable = candidate();
        context.setSyntheticGrant(other.getName(), now + 60000);
        check(UpdateInstallProvider.discardUnshared(context, removable) && !removable.exists() && other.isFile(), "grant for another file does not prevent candidate cleanup");
        context.setSyntheticGrant(other.getName(), now + 7200000);
        check(UpdateInstallProvider.discardUnshared(context, other) && !other.exists(), "invalid future grant cannot retain file indefinitely");
        context.setSyntheticGrant("", 0);
        check(!UpdateInstallProvider.discardUnshared(context, shared), "missing candidate is a harmless no-op");
    }
    private static void scope() throws Exception {
        File outside = file(root, "update-outside.apk");
        int reads = context.preferenceReads;
        check(!UpdateInstallProvider.discardUnshared(context, outside) && outside.isFile(), "outside-cache file is never deleted");
        check(context.preferenceReads == reads, "invalid path rejected before reading grant preferences");
        File foreign = file(updates, "unrelated.txt");
        check(!UpdateInstallProvider.discardUnshared(context, foreign) && foreign.isFile(), "unrelated filename is never deleted");
        File folder = new File(updates, "update-directory.apk"); check(folder.mkdir(), "owned directory fixture created"); owned.add(folder);
        check(!UpdateInstallProvider.discardUnshared(context, folder) && folder.isDirectory(), "cleanup never removes a directory");
        check(!UpdateInstallProvider.discardUnshared(context, null), "null file is a harmless no-op");
        File canonical = candidate();
        Context alias = new Context(new File(cache, "."));
        check(UpdateInstallProvider.discardUnshared(alias, new File(new File(cache, "./updates"), canonical.getName()))
                && !canonical.exists(), "legitimate cache-root aliases still resolve to the same fixed child");
    }
    private static void synchronizedGrant() throws Exception {
        File candidate = candidate(); context.setSyntheticGrant("", 0);
        AtomicBoolean removed = new AtomicBoolean(true);
        Thread cleanup = new Thread(() -> removed.set(UpdateInstallProvider.discardUnshared(context, candidate)));
        synchronized (UpdateInstallProvider.class) {
            cleanup.start(); long deadline = System.nanoTime() + 2000000000L;
            while (cleanup.getState() != Thread.State.BLOCKED && cleanup.isAlive() && System.nanoTime() < deadline) Thread.yield();
            check(cleanup.getState() == Thread.State.BLOCKED, "cleanup serializes with production grant and prune monitor");
            context.setSyntheticGrant(candidate.getName(), System.currentTimeMillis() + 60000);
        }
        cleanup.join(2000);
        check(!cleanup.isAlive() && !removed.get() && candidate.isFile(), "grant established before lock release protects file from waiting cleanup");
    }
    private static void symlinks() throws Exception {
        File target = file(root, "update-target.apk");
        Path link = new File(updates, "update-link.apk").toPath();
        if (link(link, target.toPath())) {
            try { check(!UpdateInstallProvider.discardUnshared(context, link.toFile()) && target.isFile() && Files.isSymbolicLink(link), "file symlink redirect is not followed or deleted"); }
            finally { Files.delete(link); }
        }
        File linkedCache = new File(root, "linked-cache"); check(linkedCache.mkdir(), "owned redirected-cache fixture created");
        Path linkedUpdates = new File(linkedCache, "updates").toPath();
        if (link(linkedUpdates, root.toPath())) {
            try { check(!UpdateInstallProvider.discardUnshared(new Context(linkedCache), new File(linkedUpdates.toFile(), target.getName())) && target.isFile(), "updates directory symlink cannot redirect cleanup outside fixed cache"); }
            finally { Files.delete(linkedUpdates); }
        }
    }
    private static boolean link(Path link, Path target) throws Exception {
        try { Files.createSymbolicLink(link, target); return true; }
        catch (IOException | UnsupportedOperationException | SecurityException unavailable) { symlinkCasesSkipped++; return false; }
    }
}
