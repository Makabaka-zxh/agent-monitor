package com.agentmonitor.live;

import java.io.File;
import java.nio.file.Files;

/** Saved hints are validated again; no Android, network, real package or install operation. */
public final class UpdateRecoveryPolicyTest {
    private static final long COMPLETED = 1800000000000L;
    private static final String NAME = "update-synthetic_123.apk";
    private static final String SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static int checks;
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private static UpdatePolicy.Manifest manifest() { return new UpdatePolicy.Manifest(1, UpdatePolicy.PACKAGE_NAME, "1.0.1", 30, 26, "monitor-1.0.1.apk", SHA, 3); }
    private static String[] saved() { return UpdateRecoveryPolicy.capture(NAME, manifest(), COMPLETED).encode(true); }
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new AssertionError("Supply an isolated generated test directory");
        roundTrip(); invalidHints(); expiry(); resume(); grants(); fixedFiles(new File(args[0], "recovery-cache"));
        System.out.println("UpdateRecoveryPolicyTest: " + checks + " checks passed");
    }
    private static void roundTrip() {
        UpdateRecoveryPolicy.Candidate candidate = UpdateRecoveryPolicy.decode(saved());
        check(candidate != null && candidate.basename.equals(NAME), "only a candidate basename survives saved state");
        check(candidate.completedAt == COMPLETED && candidate.awaitingPermission, "completed time and permission explanation survive rebuilding");
        check(candidate.manifest.versionCode == 30 && candidate.manifest.size == 3 && candidate.manifest.sha256.equals(SHA), "manifest is reconstructed through its validating constructor");
        check(candidate.manifest.packageName.equals(UpdatePolicy.PACKAGE_NAME) && candidate.manifest.minSdk == 26, "package and SDK identity retained");
        String[] first = candidate.encode(false), second = candidate.encode(false); first[1] = "../other.apk";
        check(second[1].equals(NAME) && candidate.basename.equals(NAME), "saved array mutation cannot change the accepted candidate");
        check(!UpdateRecoveryPolicy.decode(second).awaitingPermission, "permission flag can clear without changing the completion timestamp");
        for (java.lang.reflect.Field field : UpdateRecoveryPolicy.Candidate.class.getDeclaredFields())
            check(java.lang.reflect.Modifier.isFinal(field.getModifiers()), "candidate fields are immutable hints");
    }
    private static void rejected(int field, String value) { String[] bad = saved(); bad[field] = value; check(UpdateRecoveryPolicy.decode(bad) == null, "invalid saved field rejected without a partial candidate"); }
    private static void invalidHints() {
        check(UpdateRecoveryPolicy.decode(null) == null, "absent state is harmless");
        check(UpdateRecoveryPolicy.decode(new String[11]) == null && UpdateRecoveryPolicy.decode(new String[13]) == null, "truncated and expanded records rejected");
        rejected(0, "2"); rejected(0, null);
        for (String name : new String[]{"../" + NAME, "/" + NAME, "C:\\cache\\" + NAME, "nested/" + NAME, "update-test.part", "monitor.apk", "update-.apk", NAME + "\n"}) rejected(1, name);
        for (String time : new String[]{null, "0", "-1", "+1", "1.5", "1e3", "9223372036854775808", "1800000000000 "}) rejected(2, time);
        for (String flag : new String[]{null, "TRUE", "1", " true", ""}) rejected(3, flag);
        rejected(4, "0"); rejected(4, "2"); rejected(4, "2147483648");
        rejected(5, "other.package"); rejected(6, "<script>"); rejected(7, "0"); rejected(7, "2147483648");
        rejected(8, "0"); rejected(9, "../monitor.apk"); rejected(10, "not-a-digest");
        rejected(11, "0"); rejected(11, Long.toString(UpdatePolicy.MAX_APK_BYTES + 1));
    }
    private static void expiry() {
        UpdateRecoveryPolicy.Candidate candidate = UpdateRecoveryPolicy.decode(saved());
        check(UpdateRecoveryPolicy.MAX_AGE_MS == 3600000, "restore lifetime matches private-download pruning");
        check(candidate.fresh(COMPLETED), "newly completed download can be restored");
        check(candidate.fresh(COMPLETED + 3599999), "last millisecond before expiry accepted");
        check(!candidate.fresh(COMPLETED + 3600000) && !candidate.fresh(COMPLETED + 86400000), "expiry is inclusive");
        check(!candidate.fresh(COMPLETED - 1) && !candidate.fresh(0), "future completion or clock regression fails closed");
        check(UpdateRecoveryPolicy.decode(candidate.encode(false)).completedAt == COMPLETED, "repeated rotations never renew the lifetime");
    }
    private static void resume() {
        UpdateRecoveryPolicy.Candidate candidate = UpdateRecoveryPolicy.decode(saved());
        check(UpdateRecoveryPolicy.resumeAction(null, 30, 36, COMPLETED) == UpdateRecoveryPolicy.ResumeAction.NONE, "no candidate does not claim an installed update");
        check(UpdateRecoveryPolicy.resumeAction(candidate, 29, 36, COMPLETED) == UpdateRecoveryPolicy.ResumeAction.VERIFY, "return with older installed version requires revalidation");
        check(UpdateRecoveryPolicy.resumeAction(UpdateRecoveryPolicy.decode(candidate.encode(false)), 29, 36, COMPLETED + 1) == UpdateRecoveryPolicy.ResumeAction.VERIFY, "saved candidate without permission or handoff flags still requires revalidation");
        check(UpdateRecoveryPolicy.resumeAction(candidate, 30, 36, COMPLETED) == UpdateRecoveryPolicy.ResumeAction.ALREADY_INSTALLED, "successful install cannot return READY for the same version");
        check(UpdateRecoveryPolicy.resumeAction(candidate, 31, 36, COMPLETED) == UpdateRecoveryPolicy.ResumeAction.ALREADY_INSTALLED, "a newer installed version also contains the candidate update");
        check(UpdateRecoveryPolicy.resumeAction(candidate, 30, 36, COMPLETED + 3600000) == UpdateRecoveryPolicy.ResumeAction.ALREADY_INSTALLED, "an installed update is recognized even when its old download has expired");
        check(UpdateRecoveryPolicy.resumeAction(candidate, 29, 36, COMPLETED + 3600000) == UpdateRecoveryPolicy.ResumeAction.EXPIRED, "uninstalled expired candidate cannot be reused");
        check(UpdateRecoveryPolicy.resumeAction(candidate, 29, 36, COMPLETED - 1) == UpdateRecoveryPolicy.ResumeAction.EXPIRED, "clock rollback cannot revive a saved candidate");
        check(UpdateRecoveryPolicy.resumeAction(candidate, 29, 25, COMPLETED) == UpdateRecoveryPolicy.ResumeAction.UNSUPPORTED, "unsupported device never offers restored installation");
        boolean rejected = false;
        try { UpdateRecoveryPolicy.resumeAction(candidate, 0, 36, COMPLETED); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, "missing installed identity cannot imply a completed update");
    }
    private static void grants() {
        check(UpdateRecoveryPolicy.liveGrant(NAME, NAME, COMPLETED + 3600000, COMPLETED), "new installer grant has its own one-hour lifetime");
        check(UpdateRecoveryPolicy.liveGrant(NAME, NAME, COMPLETED + 1, COMPLETED), "grant remains protected until exact expiry");
        check(!UpdateRecoveryPolicy.liveGrant(NAME, NAME, COMPLETED, COMPLETED), "exact grant expiry releases protection");
        check(!UpdateRecoveryPolicy.liveGrant(NAME, NAME, COMPLETED - 1, COMPLETED), "expired grant cannot retain candidate forever");
        check(!UpdateRecoveryPolicy.liveGrant(NAME, NAME, COMPLETED + 3600001, COMPLETED), "clock rollback or invalid future grant fails closed");
        check(!UpdateRecoveryPolicy.liveGrant(NAME, "update-other.apk", COMPLETED + 1, COMPLETED), "another file's grant never protects the candidate");
        check(!UpdateRecoveryPolicy.liveGrant(null, NAME, COMPLETED + 1, COMPLETED), "absent candidate is never considered shared");
        check(!UpdateRecoveryPolicy.liveGrant(NAME, NAME, Long.MAX_VALUE, COMPLETED), "extreme expiry cannot overflow into a valid grant");
    }
    private static void fixedFiles(File cache) throws Exception {
        File updates = new File(cache, "updates"); check(updates.mkdirs() || updates.isDirectory(), "isolated updates directory created");
        UpdateRecoveryPolicy.Candidate candidate = UpdateRecoveryPolicy.decode(saved());
        File owned = new File(updates, NAME), other = new File(updates, "update-other.apk"), outside = new File(cache, NAME);
        Files.write(owned.toPath(), new byte[]{1, 2, 3}); Files.write(other.toPath(), new byte[]{4}); Files.write(outside.toPath(), new byte[]{5});
        check(candidate.file(cache).equals(owned.getCanonicalFile()), "saved basename maps only to fixed app cache directory");
        candidate.discard(cache);
        check(!owned.exists() && other.isFile() && outside.isFile(), "cancel/failed verification deletes only its own fixed-location candidate");
        candidate.discard(cache); check(!owned.exists(), "cleanup of an absent candidate is harmless");
        check(owned.mkdir(), "synthetic non-file created"); candidate.discard(cache); check(owned.isDirectory(), "cleanup never recursively removes a directory");
        check(owned.delete() && other.delete() && outside.delete(), "generated test items cleaned without recursive deletion");
        // Path traversal rejection is platform independent; symlink creation itself may need
        // Windows privilege, so canonical-path escape is additionally enforced in file().
    }
}
