package com.agentmonitor.live;

import java.net.URI;
import java.util.Locale;

/** Pure release-metadata and URL checks. No networking, installation or trust overrides. */
final class UpdatePolicy {
    static final String LATEST_RELEASE_API = "https://api.github.com/repos/Makabaka-zxh/agent-monitor/releases/latest";
    static final String PACKAGE_NAME = "com.agentmonitor.live";
    static final int MAX_METADATA_BYTES = 1024 * 1024;
    static final long MAX_APK_BYTES = 150L * 1024 * 1024;
    private static final String RELEASE_PATH = "/Makabaka-zxh/agent-monitor/releases/download/";

    private UpdatePolicy() { }

    private static boolean safeSegment(String value) {
        return value != null && value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,179}");
    }

    static boolean validApkName(String name) {
        return safeSegment(name) && name.endsWith(".apk");
    }

    static boolean validMetadataSize(long size) {
        return size > 0 && size <= MAX_METADATA_BYTES;
    }

    private static URI httpsUri(String url) {
        if (url == null || url.isEmpty() || url.length() > 16384) return null;
        try {
            URI parsed = new URI(url);
            if (!"https".equals(parsed.getScheme()) || parsed.isOpaque() || parsed.getHost() == null
                    || parsed.getRawUserInfo() != null || parsed.getRawFragment() != null
                    || (parsed.getPort() != -1 && parsed.getPort() != 443)) return null;
            // Reject alternative spellings of authority, including empty ports and encoded hosts.
            String host = parsed.getHost(), authority = parsed.getRawAuthority();
            if (!host.equals(authority) && !(host + ":443").equals(authority)) return null;
            return parsed;
        } catch (Exception invalid) { return null; }
    }

    /** Browser-download links must name the exact repository, release tag and expected asset. */
    static boolean allowedAssetUrl(String url, String tag, String assetName) {
        if (!safeSegment(tag) || !safeSegment(assetName)) return false;
        URI parsed = httpsUri(url);
        return parsed != null && "github.com".equals(parsed.getHost()) && parsed.getRawQuery() == null
                && (RELEASE_PATH + tag + "/" + assetName).equals(parsed.getRawPath());
    }

    /** CDN queries are signed by GitHub and may remain intact; repository redirects stay exact. */
    static boolean allowedRedirect(String url, String tag, String assetName) {
        if (!safeSegment(tag) || !safeSegment(assetName)) return false;
        if (allowedAssetUrl(url, tag, assetName)) return true;
        URI parsed = httpsUri(url);
        if (parsed == null || !"release-assets.githubusercontent.com".equals(parsed.getHost())) return false;
        String path = parsed.getRawPath();
        if (path == null || !path.matches("/[A-Za-z0-9][A-Za-z0-9._/-]*")) return false;
        for (String segment : path.substring(1).split("/", -1))
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) return false;
        return true;
    }

    static final class Manifest {
        final int schema, versionCode, minSdk;
        final String packageName, versionName, apkName, sha256;
        final long size;

        Manifest(int schema, String packageName, String versionName, int versionCode, int minSdk,
                 String apkName, String sha256, long size) {
            if (schema != 1 || !PACKAGE_NAME.equals(packageName) || versionCode <= 0 || minSdk <= 0
                    || versionName == null || !versionName.matches("[A-Za-z0-9][A-Za-z0-9._+\\-]{0,79}")
                    || !validApkName(apkName) || sha256 == null || !sha256.matches("[0-9a-fA-F]{64}")
                    || size <= 0 || size > MAX_APK_BYTES)
                throw new IllegalArgumentException("Invalid update manifest");
            this.schema = schema; this.packageName = packageName; this.versionName = versionName;
            this.versionCode = versionCode; this.minSdk = minSdk; this.apkName = apkName;
            this.sha256 = sha256.toLowerCase(Locale.ROOT); this.size = size;
        }

        boolean newerThan(int currentVersionCode) {
            return currentVersionCode >= 0 && versionCode > currentVersionCode;
        }

        boolean supportsSdk(int deviceSdk) {
            return deviceSdk > 0 && minSdk <= deviceSdk;
        }

        boolean eligible(int currentVersionCode, int deviceSdk) {
            return newerThan(currentVersionCode) && supportsSdk(deviceSdk);
        }
    }
}
