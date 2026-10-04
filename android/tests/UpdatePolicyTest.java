package com.agentmonitor.live;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/** Pure JVM checks: no Android classes, filesystem access, network or installation. */
public final class UpdatePolicyTest {
    private static final String TAG = "v0.7.7-native-test";
    private static final String APK = "monitor-0.7.7-native-test.apk";
    private static final String SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String URL = "https://github.com/Makabaka-zxh/agent-monitor/releases/download/" + TAG + "/" + APK;
    private static final String CDN = "https://release-assets.githubusercontent.com/github-production-release-asset/123456/abcdef-1234";
    private static int checks;

    private static void check(boolean value, String message) {
        checks++; if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        assetUrls(); redirectUrls(); manifestValidation(); eligibility(); metadataBounds();
        System.out.println("UpdatePolicyTest: " + checks + " checks passed");
    }

    private static void assetUrls() {
        check(UpdatePolicy.LATEST_RELEASE_API.equals("https://api.github.com/repos/Makabaka-zxh/agent-monitor/releases/latest"), "fixed release API");
        check(UpdatePolicy.allowedAssetUrl(URL, TAG, APK), "exact release asset");
        check(UpdatePolicy.allowedAssetUrl(URL.replace("github.com/", "github.com:443/"), TAG, APK), "explicit default HTTPS port");
        check(UpdatePolicy.allowedAssetUrl(URL.replace(APK, "android-update.json"), TAG, "android-update.json"), "metadata assets may use exact JSON name");
        for (String bad : new String[]{null, "", "http" + URL.substring(5), URL.replace("github.com", "github.com.evil.test"),
                URL.replace("github.com", "evil.test@github.com"), URL.replace("github.com", "github.com@evil.test"),
                URL.replace("github.com", "github.com:444"), URL.replace("github.com", "github.com:"),
                URL.replace("github.com", "github.com:0443"), URL.replace("github.com", "github.com."),
                URL.replace("github.com", "github%2ecom"), URL.replace("github.com", "GITHUB.COM"),
                URL.replace("Makabaka-zxh", "other"), URL.replace("agent-monitor", "agent-monitor-other"),
                URL.replace(TAG, "v0.0.1"), URL + ".bak", URL + "?download=1", URL + "?", URL + "#fragment", URL + "#",
                URL.replace("/releases/", "/other/../releases/"), URL.replace("/download/", "/download//"),
                URL.replace(TAG, "%2e%2e"), URL.replace(APK, "%6donitor-0.7.7-native-test.apk"),
                URL.replace("/" + APK, "/..%2f" + APK), URL.replace("/" + APK, "\\" + APK),
                " " + URL, URL + "\n", "//github.com/Makabaka-zxh/agent-monitor/releases/download/" + TAG + "/" + APK})
            check(!UpdatePolicy.allowedAssetUrl(bad, TAG, APK), "reject ambiguous or foreign asset URL");
        for (String bad : new String[]{null, "", ".", "..", "../release", "tag/sub", "tag%2fsub", "tag?query", "tag#fragment", "tag\\sub", "tag\n", repeat('a', 181)}) {
            check(!UpdatePolicy.allowedAssetUrl(URL, bad, APK), "reject unsafe tag");
            check(!UpdatePolicy.allowedAssetUrl(URL, TAG, bad), "reject unsafe expected filename");
        }
    }

    private static void redirectUrls() {
        check(UpdatePolicy.allowedRedirect(URL, TAG, APK), "exact repository redirect");
        check(UpdatePolicy.allowedRedirect(CDN, TAG, APK), "release asset CDN");
        check(UpdatePolicy.allowedRedirect(CDN + "?sp=r&sig=abc%2F123%3D&se=2026-10-04T12%3A00%3A00Z", TAG, APK), "signed CDN query retained");
        check(UpdatePolicy.allowedRedirect(CDN.replace(".com/", ".com:443/"), TAG, APK), "CDN explicit HTTPS port");
        for (String bad : new String[]{null, "", "http" + CDN.substring(5), CDN.replace(".com/", ".com.evil.test/"),
                CDN.replace("https://", "https://user@"), CDN.replace(".com/", ".com@evil.test/"),
                CDN.replace(".com/", ".com:8443/"), CDN.replace(".com/", ".com:/"), CDN + "#token",
                "https://release-assets.githubusercontent.com", "https://release-assets.githubusercontent.com/",
                CDN + "/..", CDN + "/.", CDN + "//more", CDN + "/%2e%2e", CDN + "/%2fsecret", CDN + "/\\secret",
                "https://objects.githubusercontent.com/file?sig=value", "https://github.com/other/repo/releases/download/x/file.apk",
                URL.replace(TAG, "other-release"), URL.replace(APK, "another.apk"), URL + "?redirect=1"})
            check(!UpdatePolicy.allowedRedirect(bad, TAG, APK), "reject unsafe redirect");
        check(!UpdatePolicy.allowedRedirect(CDN, "../tag", APK), "CDN still requires valid original tag");
        check(!UpdatePolicy.allowedRedirect(CDN, TAG, "../file.apk"), "CDN still requires valid original asset name");
    }

    private static UpdatePolicy.Manifest manifest(int schema, String packageName, String versionName, int versionCode,
                                                   int minSdk, String apkName, String sha256, long size) {
        return new UpdatePolicy.Manifest(schema, packageName, versionName, versionCode, minSdk, apkName, sha256, size);
    }

    private static void rejected(int schema, String packageName, String versionName, int versionCode, int minSdk,
                                 String apkName, String sha256, long size) {
        try { manifest(schema, packageName, versionName, versionCode, minSdk, apkName, sha256, size); throw new AssertionError("Unsafe manifest accepted"); }
        catch (IllegalArgumentException expected) { check(expected.getMessage().equals("Invalid update manifest"), "validation failure never echoes supplied metadata"); }
    }

    private static void manifestValidation() {
        UpdatePolicy.Manifest valid = manifest(1, UpdatePolicy.PACKAGE_NAME, "0.7.7-native-test", 29, 26, APK, SHA.toUpperCase(java.util.Locale.ROOT), 1);
        check(valid.schema == 1 && valid.versionCode == 29 && valid.minSdk == 26, "numeric fields retained");
        check(valid.packageName.equals(UpdatePolicy.PACKAGE_NAME) && valid.versionName.equals("0.7.7-native-test") && valid.apkName.equals(APK), "identity fields retained");
        check(valid.sha256.equals(SHA) && valid.size == 1, "digest normalized and minimum asset size accepted");
        for (Field field : UpdatePolicy.Manifest.class.getDeclaredFields()) check(Modifier.isFinal(field.getModifiers()), "manifest fields are immutable");
        for (int invalid : new int[]{-1, 0, 2, Integer.MAX_VALUE}) rejected(invalid, UpdatePolicy.PACKAGE_NAME, "1.0", 1, 26, APK, SHA, 1);
        for (String invalid : new String[]{null, "", "com.other.app", UpdatePolicy.PACKAGE_NAME + " ", "COM.AGENTMONITOR.LIVE"}) rejected(1, invalid, "1.0", 1, 26, APK, SHA, 1);
        for (String invalid : new String[]{null, "", " ", " 1.0", "1.0 ", "1.0\n", "1.0\u0000", "../1.0", "<b>1.0</b>", repeat('a', 81)}) rejected(1, UpdatePolicy.PACKAGE_NAME, invalid, 1, 26, APK, SHA, 1);
        for (int invalid : new int[]{0, -1, Integer.MIN_VALUE}) {
            rejected(1, UpdatePolicy.PACKAGE_NAME, "1.0", invalid, 26, APK, SHA, 1);
            rejected(1, UpdatePolicy.PACKAGE_NAME, "1.0", 1, invalid, APK, SHA, 1);
        }
        for (String invalid : new String[]{null, "", ".apk", "../app.apk", "dir/app.apk", "dir\\app.apk", "app.apk.exe", "app.APK", "app.apk?x", "app.apk#x", "app%2eapk", " app.apk", "app.apk\n", repeat('a', 181) + ".apk"}) {
            check(!UpdatePolicy.validApkName(invalid), "APK basename policy");
            rejected(1, UpdatePolicy.PACKAGE_NAME, "1.0", 1, 26, invalid, SHA, 1);
        }
        for (String invalid : new String[]{null, "", repeat('a', 63), repeat('a', 65), repeat('g', 64), SHA + "\n"}) rejected(1, UpdatePolicy.PACKAGE_NAME, "1.0", 1, 26, APK, invalid, 1);
        for (long invalid : new long[]{Long.MIN_VALUE, -1, 0, UpdatePolicy.MAX_APK_BYTES + 1, Long.MAX_VALUE}) rejected(1, UpdatePolicy.PACKAGE_NAME, "1.0", 1, 26, APK, SHA, invalid);
        check(manifest(1, UpdatePolicy.PACKAGE_NAME, "1.0+build", 1, 26, APK, SHA, UpdatePolicy.MAX_APK_BYTES).size == 150L * 1024 * 1024, "150 MiB inclusive cap");
    }

    private static void eligibility() {
        UpdatePolicy.Manifest update = manifest(1, UpdatePolicy.PACKAGE_NAME, "0.7.7", 29, 26, APK, SHA, 4096);
        check(update.newerThan(28) && update.newerThan(0), "strictly newer version code");
        check(!update.newerThan(29) && !update.newerThan(30) && !update.newerThan(-1), "equal, older and invalid installed codes excluded");
        check(update.supportsSdk(26) && update.supportsSdk(36) && !update.supportsSdk(25) && !update.supportsSdk(0), "SDK eligibility independent of version");
        check(update.eligible(28, 26) && !update.eligible(29, 26) && !update.eligible(28, 25), "both eligibility conditions required");
        check(update.versionCode == 29, "same-version checks never invalidate metadata");
    }

    private static void metadataBounds() {
        check(UpdatePolicy.MAX_METADATA_BYTES == 1024 * 1024, "metadata cap is one MiB");
        check(UpdatePolicy.validMetadataSize(1) && UpdatePolicy.validMetadataSize(UpdatePolicy.MAX_METADATA_BYTES), "metadata size inclusive bounds");
        for (long size : new long[]{Long.MIN_VALUE, -1, 0, UpdatePolicy.MAX_METADATA_BYTES + 1L, Long.MAX_VALUE})
            check(!UpdatePolicy.validMetadataSize(size), "reject invalid metadata size");
    }

    private static String repeat(char value, int count) {
        char[] result = new char[count]; java.util.Arrays.fill(result, value); return new String(result);
    }
}
