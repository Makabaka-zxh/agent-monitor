package com.agentmonitor.live;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;

/** A verified package is produced only after content, identity, version and signer checks. */
final class UpdatePackage {
    static final class Installed {
        final String name;
        final int code;
        Installed(String name, int code) { this.name = name; this.code = code; }
    }
    static final class Verified {
        final File file;
        final UpdatePolicy.Manifest manifest;
        private Verified(File file, UpdatePolicy.Manifest manifest) { this.file = file; this.manifest = manifest; }
    }
    static Installed installed(Context context) throws Exception {
        PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        return new Installed(info.versionName == null ? "未知版本" : info.versionName, version(info));
    }
    static Verified verify(Context context, File file, UpdatePolicy.Manifest expected, UpdateClient.Operation operation) throws Exception {
        operation.check();
        File directory = new File(context.getCacheDir(), "updates").getCanonicalFile();
        if (!file.getCanonicalFile().getParentFile().equals(directory) || !file.isFile() || file.length() != expected.size)
            throw new IOException("安装包大小或位置无效");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[32768]; int read;
            while ((read = input.read(buffer)) != -1) { operation.check(); digest.update(buffer, 0, read); }
        }
        if (!UpdateClient.hex(digest.digest()).equalsIgnoreCase(expected.sha256)) throw new IOException("安装包校验失败，请重新下载");
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageManager manager = context.getPackageManager();
        PackageInfo archive = manager.getPackageArchiveInfo(file.getAbsolutePath(), flags);
        PackageInfo current = manager.getPackageInfo(context.getPackageName(), flags);
        if (archive == null || archive.applicationInfo == null || !UpdatePolicy.PACKAGE_NAME.equals(context.getPackageName())
                || !expected.packageName.equals(archive.packageName) || version(archive) != expected.versionCode
                || !expected.versionName.equals(archive.versionName) || !expected.eligible(version(current), Build.VERSION.SDK_INT)
                || archive.applicationInfo.minSdkVersion != expected.minSdk)
            throw new IOException("安装包版本或应用身份不一致");
        Set<String> installedSigners = signers(current), packageSigners = signers(archive);
        if (installedSigners.isEmpty() || !installedSigners.equals(packageSigners)) throw new IOException("安装包签名与当前应用不一致");
        operation.check(); return new Verified(file, expected);
    }
    private static int version(PackageInfo info) throws IOException {
        long code = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        if (code <= 0 || code > Integer.MAX_VALUE) throw new IOException("应用版本无效"); return (int) code;
    }
    private static Set<String> signers(PackageInfo info) throws Exception {
        Signature[] signatures = Build.VERSION.SDK_INT >= 28
                ? info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners() : info.signatures;
        Set<String> result = new HashSet<>();
        if (signatures != null) for (Signature signature : signatures)
            result.add(UpdateClient.hex(MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())));
        return result;
    }
    private UpdatePackage() { }
}
