package com.agentmonitor.live;

import android.content.Context;
import android.content.ContentValues;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.IOException;
import java.net.Socket;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;

/**
 * Exercises production package verification and install grants using generated host files.
 * Package metadata, signer bytes, preferences and descriptors use synthetic Android boundaries;
 * this does not exercise APK parsing, Binder permissions or an Android system installer.
 */
public final class UpdatePackageProviderIntegrationTest {
    private static int checks, sequence;
    private static File root;
    private static final byte[] PAYLOAD = new byte[]{9, 8, 7, 6, 5, 4, 3};
    private static final byte[] SIGNER = new byte[]{11, 22, 33, 44}; // synthetic bytes, never a real key/certificate
    private interface Action { void run() throws Exception; }
    private static void check(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    private static void rejected(Action action, String message, String label) throws Exception {
        try { action.run(); } catch (IOException expected) {
            check(message == null || message.equals(expected.getMessage()), label + " fixed rejection message"); return;
        }
        throw new AssertionError(label);
    }
    private static String sha(byte[] value) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value); StringBuilder out = new StringBuilder();
        for (byte item : bytes) out.append(String.format(Locale.ROOT, "%02x", item & 255)); return out.toString();
    }
    private static PackageInfo info(String name, int code, byte[]... signerBytes) {
        PackageInfo result = new PackageInfo(); result.packageName = UpdatePolicy.PACKAGE_NAME; result.versionName = name;
        result.versionCode = code; result.longVersionCode = code; result.applicationInfo = new ApplicationInfo(); result.applicationInfo.minSdkVersion = 26;
        result.signatures = new Signature[signerBytes.length];
        for (int i=0; i<signerBytes.length; i++) result.signatures[i] = new Signature(signerBytes[i]);
        result.signingInfo = new SigningInfo(); result.signingInfo.signers = result.signatures; return result;
    }
    private static final class Fixture {
        final Context context; final File file; final UpdatePolicy.Manifest manifest; final UpdateInstallProvider provider;
        Fixture() throws Exception {
            File cache = new File(root, "fixture-" + (++sequence)); File updates = new File(cache, "updates");
            if (!updates.mkdirs()) throw new AssertionError("Fresh isolated cache");
            context = new Context(cache); context.manager.installed = info("1.0.1", 30, SIGNER); context.manager.archive = info("1.0.2", 31, SIGNER);
            file = new File(updates, "update-synthetic.apk"); Files.write(file.toPath(), PAYLOAD);
            manifest = new UpdatePolicy.Manifest(1, UpdatePolicy.PACKAGE_NAME, "1.0.2", 31, 26, "monitor-1.0.2.apk", sha(PAYLOAD), PAYLOAD.length);
            provider = new UpdateInstallProvider(); provider.attachForTest(context); check(provider.onCreate(), "provider synthetic boundary initialized");
        }
        UpdatePackage.Verified verified() throws Exception {
            try (UpdateClient.Operation task = new UpdateClient.Operation(30000)) { return UpdatePackage.verify(context, file, manifest, task); }
        }
        Uri grant() throws Exception { return UpdateInstallProvider.grant(context, verified()); }
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new AssertionError("Expected isolated fixture root");
        File parent = new File(args[0]).getCanonicalFile();
        if (!parent.mkdirs() && !parent.isDirectory()) throw new AssertionError("Isolated test parent unavailable");
        root = Files.createTempDirectory(parent.toPath(), "package-provider-").toFile();
        // Any accidental transport call fails before a socket exists. Tests use no network responses.
        Socket.setSocketImplFactory(() -> { throw new AssertionError("Network forbidden in package/provider host test"); });
        verification(); cancellation(); grantsAndReads(); prune(); failurePaths();
        System.out.println("UpdatePackageProviderIntegrationTest: " + checks + " checks passed; production verification/grant/openFile/prune; synthetic Android boundaries; no device/network");
    }
    private static void verification() throws Exception {
        Build.VERSION.SDK_INT = 36;
        Fixture good = new Fixture(); UpdatePackage.Verified result = good.verified();
        check(result.file.equals(good.file) && result.manifest == good.manifest, "verified result identifies exact validated candidate");
        check(good.context.manager.archivePath.equals(good.file.getAbsolutePath()), "real candidate path reaches synthetic PackageManager");
        check(good.context.manager.lastFlags == PackageManager.GET_SIGNING_CERTIFICATES, "modern signer API selected");
        UpdatePackage.Installed installed = UpdatePackage.installed(good.context);
        check(installed.code == 30 && "1.0.1".equals(installed.name), "installed version read from manager boundary");
        good.context.manager.installed.versionName = null;
        check("未知版本".equals(UpdatePackage.installed(good.context).name), "absent installed name handled");
        for (int variant = 0; variant < 17; variant++) {
            Fixture fixture = new Fixture(); String message = "安装包版本或应用身份不一致";
            switch (variant) {
                case 0: Files.write(fixture.file.toPath(), new byte[]{9,8,7,6,5,4,0}); message="安装包校验失败，请重新下载"; break;
                case 1: Files.write(fixture.file.toPath(), new byte[]{1}); message="安装包大小或位置无效"; break;
                case 2: fixture.context.manager.archive=null; break;
                case 3: fixture.context.manager.archive.applicationInfo=null; break;
                case 4: fixture.context.packageName="other.package"; break;
                case 5: fixture.context.manager.archive.packageName="other.package"; break;
                case 6: fixture.context.manager.archive.longVersionCode=32; break;
                case 7: fixture.context.manager.archive.versionName="wrong"; break;
                case 8: fixture.context.manager.installed.longVersionCode=31; break;
                case 9: fixture.context.manager.archive.applicationInfo.minSdkVersion=27; break;
                case 10: fixture.context.manager.archive.signingInfo.signers=new Signature[]{new Signature(new byte[]{99})}; message="安装包签名与当前应用不一致"; break;
                case 11: fixture.context.manager.installed.signingInfo.signers=new Signature[0]; message="安装包签名与当前应用不一致"; break;
                case 12: fixture.context.manager.archive.signingInfo=null; message="安装包签名与当前应用不一致"; break;
                case 13: fixture.context.manager.archive.longVersionCode=0; message="应用版本无效"; break;
                case 14: fixture.context.manager.archive.longVersionCode=2147483648L; message="应用版本无效"; break;
                case 15: fixture.context.manager.installed.longVersionCode=0; message="应用版本无效"; break;
                case 16: Build.VERSION.SDK_INT=25; break;
                default: throw new AssertionError();
            }
            rejected(fixture::verified, message, "invalid candidate variant " + variant + " rejected");
            if (variant < 2) check(fixture.context.manager.archiveReads == 0, "corrupt bytes rejected before platform identity parsing");
            Build.VERSION.SDK_INT=36;
        }
        Fixture outside = new Fixture(); File outsideFile = new File(outside.context.getCacheDir(), "update-outside.apk"); Files.write(outsideFile.toPath(), PAYLOAD);
        try (UpdateClient.Operation task = new UpdateClient.Operation(30000)) {
            rejected(() -> UpdatePackage.verify(outside.context, outsideFile, outside.manifest, task), "安装包大小或位置无效", "outside candidate rejected");
        }
        check(outside.context.manager.archiveReads == 0, "outside scope rejected before PackageManager read");
        Fixture multi = new Fixture(); byte[] second = {7,7,7};
        multi.context.manager.installed=info("1.0.1",30,SIGNER,second);multi.context.manager.archive=info("1.0.2",31,second,SIGNER);
        check(multi.verified() != null, "equal multiple signer sets accepted independent of order");
        multi.context.manager.archive=info("1.0.2",31,SIGNER);
        rejected(multi::verified,"安装包签名与当前应用不一致","missing signer rejected");
        Build.VERSION.SDK_INT=27; Fixture legacy = new Fixture(); check(legacy.verified()!=null,"legacy signature path accepts matching bytes");
        check(legacy.context.manager.lastFlags==PackageManager.GET_SIGNATURES,"legacy signer API selected");
        legacy.context.manager.archive.signatures=new Signature[]{new Signature(new byte[]{99})};
        rejected(legacy::verified,"安装包签名与当前应用不一致","legacy signer mismatch rejected"); Build.VERSION.SDK_INT=36;
    }
    private static void cancellation() throws Exception {
        Fixture before = new Fixture(); try(UpdateClient.Operation task=new UpdateClient.Operation(30000)) {
            task.close(); rejected(() -> UpdatePackage.verify(before.context,before.file,before.manifest,task),"操作已取消","early cancellation rejected");
        }
        check(before.context.manager.archiveReads==0,"early cancellation reaches no PackageManager");
        Fixture late = new Fixture(); try(UpdateClient.Operation task=new UpdateClient.Operation(30000)) {
            late.context.manager.onArchive=task::cancel;
            rejected(() -> UpdatePackage.verify(late.context,late.file,late.manifest,task),"操作已取消","final cancellation gate must reject before returning Verified");
        }
        check(late.context.manager.archiveReads==1 && late.context.manager.installedReads==1,"late cancellation occurred after byte validation and before verification completion");
    }
    private static void grantsAndReads() throws Exception {
        Fixture fixture=new Fixture(); long before=System.currentTimeMillis(); Uri first=fixture.grant(); long after=System.currentTimeMillis();
        Map<String,Object> values=fixture.context.prefs.values;
        check(first.toString().matches("content://com.agentmonitor.live.updates/verified/[0-9a-f-]{36}/monitor\\.apk"),"exact scoped URI with UUID-shaped grant identifier");
        check(values.keySet().equals(new HashSet<>(Arrays.asList("uri","file","sha256","size","expires"))),"only five expected grant fields stored");
        check(first.toString().equals(values.get("uri")) && fixture.file.getName().equals(values.get("file")),"grant binds URI to exact basename");
        check(sha(PAYLOAD).equals(values.get("sha256")) && Long.valueOf(PAYLOAD.length).equals(values.get("size")),"grant stores independently computed digest and size");
        long expires=(Long)values.get("expires");check(expires>=before+3600000 && expires<=after+3600000,"grant lifetime bounded to one hour at creation");
        try(ParcelFileDescriptor fd=fixture.provider.openFile(first,"r")){check(Arrays.equals(PAYLOAD,fd.readAll()),"provider hands off exact real fixture bytes through read-only boundary");}
        UpdateInstallProvider recreated=new UpdateInstallProvider();recreated.attachForTest(fixture.context);
        try(ParcelFileDescriptor fd=recreated.openFile(first,"r")){check(Arrays.equals(PAYLOAD,fd.readAll()),"recreated provider reads grant from same synthetic preference store");}
        check("application/vnd.android.package-archive".equals(recreated.getType(first)),"valid grant reports APK MIME");
        MatrixCursor row=(MatrixCursor)recreated.query(first,null,null,null,null);
        check(row.row.equals(Arrays.asList("monitor.apk",(long)PAYLOAD.length)),"provider exposes fixed display name and measured size only");
        MatrixCursor projected=(MatrixCursor)recreated.query(first,new String[]{OpenableColumns.SIZE,"foreign"},null,null,null);
        check(projected.row.equals(Arrays.asList((long)PAYLOAD.length,null)),"unknown projected columns expose no data");
        int opened=ParcelFileDescriptor.opens;
        rejected(() -> recreated.openFile(first,"w"),"Read only","write mode rejected");
        rejected(() -> recreated.openFile(Uri.parse(first.toString().replace("monitor.apk","other.apk")),"r"),null,"different URI rejected");
        check(ParcelFileDescriptor.opens==opened,"invalid accesses never reach descriptor boundary");
        Files.write(fixture.file.toPath(),new byte[]{9,8,7,6,5,4,0});
        rejected(() -> recreated.openFile(first,"r"),"Package unavailable","same-size post-grant tamper rejected");
        check(ParcelFileDescriptor.opens==opened,"same-size tamper produces no descriptor");Files.write(fixture.file.toPath(),PAYLOAD);
        check(!UpdateInstallProvider.discardUnshared(fixture.context,fixture.file)&&fixture.file.isFile(),"active real production grant protects candidate cleanup");
        Uri second=fixture.grant(); check(!first.toString().equals(second.toString()),"repeat grant rotates exact URI");
        check(fixture.context.revoked.equals(Collections.singletonList(first.toString())),"previous exact permission revoked once");
        check(fixture.file.isFile(),"same-file regrant preserves bytes");
        rejected(() -> recreated.openFile(first,"r"),null,"old URI denied after regrant");
        File next=new File(fixture.file.getParentFile(),"update-next.apk");Files.write(next.toPath(),PAYLOAD);
        UpdatePackage.Verified nextVerified;try(UpdateClient.Operation task=new UpdateClient.Operation(30000)){nextVerified=UpdatePackage.verify(fixture.context,next,fixture.manifest,task);}
        Uri third=UpdateInstallProvider.grant(fixture.context,nextVerified);
        check(!fixture.file.exists()&&next.isFile(),"replacing grant deletes previous owned APK only");
        check(fixture.context.revoked.contains(second.toString()),"replacing candidate revokes old permission");
        try(ParcelFileDescriptor fd=recreated.openFile(third,"r")){check(Arrays.equals(PAYLOAD,fd.readAll()),"new candidate remains readable");}
        fixture.context.prefs.values.put("expires",System.currentTimeMillis()-1);
        rejected(() -> recreated.openFile(third,"r"),null,"expired grant cannot reopen");
        check(recreated.getType(third)==null&&recreated.query(third,null,null,null,null)==null,"expired grant metadata hidden");
        fixture.context.prefs.values.put("expires",System.currentTimeMillis()+7200000);
        rejected(() -> recreated.openFile(third,"r"),null,"invalid future grant cannot reopen");
    }
    private static File extra(File directory,String name,long age) throws Exception {
        File file=new File(directory,name);Files.write(file.toPath(),new byte[]{4,2});check(file.setLastModified(System.currentTimeMillis()-age),"fixture age applied");return file;
    }
    private static void prune() throws Exception {
        Fixture fixture=new Fixture();Uri uri=fixture.grant();File dir=fixture.file.getParentFile();
        check(fixture.file.setLastModified(System.currentTimeMillis()-7200000),"active fixture made old");
        File oldApk=extra(dir,"update-abandoned.apk",7200000),oldPart=extra(dir,"update-abandoned.part",7200000),young=extra(dir,"update-young.apk",1000),foreign=extra(dir,"unrelated.txt",7200000);
        File outside=extra(fixture.context.getCacheDir(),"update-outside.apk",7200000);
        UpdateInstallProvider.prune(fixture.context);
        check(fixture.file.isFile()&&!oldApk.exists()&&!oldPart.exists(),"prune preserves active grant but removes abandoned old update files");
        check(young.isFile()&&foreign.isFile()&&outside.isFile(),"prune preserves recent, unrelated and outside files");
        check(uri.toString().equals(fixture.context.prefs.values.get("uri"))&&fixture.context.revoked.isEmpty(),"active grant remains unrevoked");
        fixture.context.prefs.values.put("expires",System.currentTimeMillis()-1);UpdateInstallProvider.prune(fixture.context);
        check(fixture.context.prefs.values.isEmpty()&&fixture.context.revoked.equals(Collections.singletonList(uri.toString())),"expired prune clears grant and revokes exact old URI");
        check(!fixture.file.exists()&&young.isFile()&&foreign.isFile()&&outside.isFile(),"expired old candidate deleted without collateral deletion");
        UpdateInstallProvider.prune(fixture.context);check(fixture.context.revoked.size()==1,"repeated prune does not repeat old revocation");
    }
    private static void failurePaths() throws Exception {
        Fixture missing=new Fixture();UpdatePackage.Verified v=missing.verified();check(missing.file.delete(),"missing-file fixture removed");
        rejected(() -> UpdateInstallProvider.grant(missing.context,v),"安装包已失效，请重新下载","grant rejects file lost after verification");
        check(missing.context.prefs.commits==0,"invalid grant writes nothing");
        Fixture commit=new Fixture();UpdatePackage.Verified ready=commit.verified();commit.context.prefs.failCommit=true;
        rejected(() -> UpdateInstallProvider.grant(commit.context,ready),"无法准备安装包","grant persistence failure reaches caller");
        Fixture noContext=new Fixture();UpdateInstallProvider detached=new UpdateInstallProvider();
        rejected(() -> detached.openFile(Uri.parse("content://com.agentmonitor.live.updates/verified/x/monitor.apk"),"r"),"Unavailable","unattached provider refuses file read");
        boolean refused=false;try{noContext.provider.insert(null,new ContentValues());}catch(UnsupportedOperationException expected){refused=true;}check(refused,"insert unsupported");
        refused=false;try{noContext.provider.delete(null,null,null);}catch(UnsupportedOperationException expected){refused=true;}check(refused,"delete unsupported");
        refused=false;try{noContext.provider.update(null,new ContentValues(),null,null);}catch(UnsupportedOperationException expected){refused=true;}check(refused,"update unsupported");
    }
}
