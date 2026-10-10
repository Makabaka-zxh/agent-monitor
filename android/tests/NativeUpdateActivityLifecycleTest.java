package com.agentmonitor.live;

import android.content.Context;
import android.content.Intent;
import android.content.pm.*;
import android.os.*;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/**
 * Host-only regression coverage for the production update Activity and verification policies.
 * Android lifecycle events, package metadata, transport and installer grants are synthetic;
 * hashing uses real isolated fixture bytes. This is not Android runtime or device acceptance.
 * No intents leave the Activity stub and the transport stub has no network implementation.
 */
public final class NativeUpdateActivityLifecycleTest {
    private static int checks, scenarios;
    private static final int CURRENT_CODE = 100, TARGET_CODE = 101;
    private static final String CURRENT_VERSION = "1.0.0", TARGET_VERSION = "1.1.0";
    private static final PackageManager pm = PackageManager.instance;
    private static File base, file;
    private static final byte[] BYTES = new byte[]{1,2,3,4,5};
    private static UpdatePolicy.Manifest manifest;
    private static final List<NativeUpdateActivity> pages = new ArrayList<>();
    private static void check(boolean ok,String message) { checks++; if(!ok)throw new AssertionError(message); }
    private static Object get(Object o,String field) throws Exception {Field f=o.getClass().getDeclaredField(field);f.setAccessible(true);return f.get(o);}
    private static void call(Object o,String method) throws Exception {Method m=o.getClass().getDeclaredMethod(method);m.setAccessible(true);try{m.invoke(o);}catch(InvocationTargetException e){throw new AssertionError("call failed: "+method,e.getCause());}}
    private static String state(NativeUpdateActivity p) throws Exception {return get(p,"state").toString();}
    private static String message(NativeUpdateActivity p) throws Exception {return (String)get(p,"message");}
    private static void awaitWorker(NativeUpdateActivity p) throws Exception {((ExecutorService)get(p,"worker")).submit(()->{}).get(5,TimeUnit.SECONDS);}
    private static void deliver(NativeUpdateActivity p) throws Exception {awaitWorker(p);Handler.drain();}
    private static void noLaunch(NativeUpdateActivity p,String where) {check(p.launched.isEmpty(),where+": no automatic installer or settings launch");}
    private static PackageInfo info(int code) {
        PackageInfo i=new PackageInfo();i.versionCode=code;i.versionName=code==CURRENT_CODE?CURRENT_VERSION:TARGET_VERSION;i.packageName=UpdatePolicy.PACKAGE_NAME;
        i.applicationInfo=new ApplicationInfo();i.applicationInfo.minSdkVersion=26;
        i.signingInfo=new SigningInfo();i.signingInfo.values=new Signature[]{new Signature(new byte[]{77,88,99})};i.signatures=i.signingInfo.values;return i;
    }
    private static void fixture() throws Exception {
        scenarios++;check(Handler.pending()==0,"no callback leaks between scenarios");pm.reset();pm.current=info(CURRENT_CODE);pm.archive=info(TARGET_CODE);
        Context.cache=new File(base,"scenario-"+scenarios);File updates=new File(Context.cache,"updates");check(updates.mkdirs(),"fresh isolated cache");
        file=new File(updates,"update-local_fixture.apk");Files.write(file.toPath(),BYTES);
        String sha=UpdateClient.hex(MessageDigest.getInstance("SHA-256").digest(BYTES));
        manifest=new UpdatePolicy.Manifest(1,UpdatePolicy.PACKAGE_NAME,TARGET_VERSION,TARGET_CODE,26,"monitor-"+TARGET_VERSION+".apk",sha,BYTES.length);
        UpdateClient.selected=new UpdateClient.Release(manifest,"host-fixture","not-a-network-request","host fixture");UpdateClient.downloadFile=file;
    }
    private static Bundle saved(boolean waiting,long completed) {
        Bundle b=new Bundle();b.putStringArray("update_candidate",UpdateRecoveryPolicy.capture(file.getName(),manifest,completed).encode(waiting));return b;
    }
    private static NativeUpdateActivity create(Bundle b) throws Exception {
        NativeUpdateActivity p=new NativeUpdateActivity();pages.add(p);p.onCreate(b);awaitWorker(p);return p;
    }
    private static void close(NativeUpdateActivity p) throws Exception {
        if(!p.destroyed){awaitWorker(p);p.onPause();p.onDestroy();Handler.drain();}
    }
    private static boolean button(NativeUpdateActivity p,String label) throws Exception {return contains((View)get(p,"content"),label);}
    private static boolean contains(View v,String label) {
        if(v instanceof TextView){TextView t=(TextView)v;if(t.click!=null&&label.equals(t.text))return true;}
        if(v instanceof LinearLayout)for(View child:((LinearLayout)v).children)if(contains(child,label))return true;
        return false;
    }
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new AssertionError("Supply a new isolated fixture directory");
        base = new File(args[0]);
        check(base.mkdirs(), "unique fixture root created");
        // A stuck worker must fail CI, never leave a success line followed by a hung JVM.
        Thread timeout = new Thread(() -> {
            try { Thread.sleep(30000); }
            catch (InterruptedException finished) { return; }
            System.err.println("NativeUpdateActivityLifecycleTest timed out");
            Runtime.getRuntime().halt(1);
        }, "lifecycle-test-timeout");
        timeout.setDaemon(true);
        timeout.start();
        Throwable failure = null;
        try {
            noCandidate(); staleVerifiedAndChangedInstalled(); restorePermissionAndSave();
            invalidBytesOnReturn(); expiredAndUnavailable(); inflightSaveAndRecreation();
            pausedCallbackAndResume(); explicitCancellation(); destroyedCallback();
            permissionThenInstaller(); latestAndLateDownload();
        } catch (Throwable failed) {
            failure = failed;
        } finally {
            for (NativeUpdateActivity page : pages) {
                try {
                    if (!page.destroyed) page.onDestroy();
                    ExecutorService executor = (ExecutorService) get(page, "worker");
                    if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                        executor.shutdownNow();
                        throw new AssertionError("onDestroy must terminate its worker");
                    }
                } catch (Throwable cleanupFailure) {
                    if (failure == null) failure = cleanupFailure;
                    else failure.addSuppressed(cleanupFailure);
                }
            }
            try { Handler.drain(); }
            catch (Throwable callbackFailure) {
                if (failure == null) failure = callbackFailure;
                else failure.addSuppressed(callbackFailure);
            }
            timeout.interrupt();
        }
        if (failure != null) {
            failure.printStackTrace();
            System.exit(1);
            return;
        }
        System.out.println("NativeUpdateActivityLifecycleTest: " + checks
                + " assertions passed in " + scenarios + " isolated scenarios");
    }
    private static void noCandidate() throws Exception {
        fixture();NativeUpdateActivity p=create(null);p.onResume();check(state(p).equals("IDLE"),"fresh page remains IDLE");
        check(pm.installedReads==1,"fresh page reads installed identity once");check(pm.archiveReads==0,"fresh page does not verify phantom candidate");
        Bundle b=new Bundle();p.onSaveInstanceState(b);check(b.getStringArray("update_candidate")==null,"no candidate saved without completed download");noLaunch(p,"fresh page");close(p);
    }
    private static void staleVerifiedAndChangedInstalled() throws Exception {
        fixture();NativeUpdateActivity p=create(saved(false,System.currentTimeMillis()));p.onResume();deliver(p);check(state(p).equals("READY"),"valid fixture restores READY");
        Object before=get(p,"ready");check(before!=null,"actual production verifier created Verified object");int reads=pm.identityReads;
        p.onPause();p.onResume();check(get(p,"ready")==null,"onResume clears stale Verified before asynchronous revalidation");
        check(pm.identityReads==reads+1,"onResume re-reads installed identity");deliver(p);check(get(p,"ready")!=before,"resume obtains a new verified object");
        pm.current=info(TARGET_CODE);p.onPause();int archives=pm.archiveReads;p.onResume();
        check(((UpdatePackage.Installed)get(p,"installed")).code==TARGET_CODE,"resume sees changed installed code rather than cached fixture code");
        check(state(p).equals("INSTALLED")&&get(p,"ready")==null&&get(p,"candidate")==null,"successful external install removes candidate and offers installed state");
        check(pm.archiveReads==archives,"already installed branch does not verify or offer same-version APK");
        check(!button(p,"安装更新"),"already installed does not render installation control");noLaunch(p,"changed installed return");close(p);
    }
    private static void restorePermissionAndSave() throws Exception {
        fixture();pm.permission=false;long completed=System.currentTimeMillis();NativeUpdateActivity p=create(saved(true,completed));p.onResume();deliver(p);
        check(state(p).equals("READY")&&message(p).contains("安装权限尚未开启"),"permission-not-granted return explains state after revalidation");
        check(!(Boolean)get(p,"awaitingPermission"),"permission-wait hint cleared after delivery");
        Bundle b=new Bundle();p.onSaveInstanceState(b);String[] fields=b.getStringArray("update_candidate");
        check(fields!=null&&fields.length==12&&fields[1].equals(file.getName()),"save contains only fixed candidate metadata schema");
        check(Long.parseLong(fields[2])==completed,"save and restore do not refresh candidate lifetime");
        check(fields[3].equals("false"),"cleared permission explanation is reflected in later saved metadata");noLaunch(p,"restored permission denial");close(p);
    }
    private static void invalidBytesOnReturn() throws Exception {
        fixture();NativeUpdateActivity p=create(saved(false,System.currentTimeMillis()));p.onResume();deliver(p);check(state(p).equals("READY"),"fixture initially verifies");
        Files.write(file.toPath(),new byte[]{5,4,3,2,1});p.onPause();p.onResume();check(get(p,"ready")==null,"return invalidates cached verified object before changed-file check");deliver(p);
        check(state(p).equals("ERROR")&&message(p).equals("安装包校验失败，请重新下载"),"real digest verification rejects changed bytes after return");
        check(get(p,"candidate")==null&&get(p,"ready")==null,"verification failure clears candidate and Verified");noLaunch(p,"changed candidate file");close(p);
    }
    private static void expiredAndUnavailable() throws Exception {
        fixture();NativeUpdateActivity p=create(saved(false,System.currentTimeMillis()-3600001L));p.onResume();
        check(state(p).equals("ERROR")&&message(p).contains("过期"),"expired saved candidate rejected on resume");
        check(pm.archiveReads==0&&get(p,"candidate")==null,"expired candidate is discarded without package verification");noLaunch(p,"expiry");close(p);
        fixture();p=create(saved(false,System.currentTimeMillis()));p.onResume();deliver(p);pm.failInstalled=true;p.onPause();p.onResume();
        check(state(p).equals("ERROR")&&get(p,"installed")==null&&get(p,"ready")==null,"installed lookup failure does not retain stale identity or Verified");
        noLaunch(p,"installed identity unavailable");close(p);
    }
    private static void inflightSaveAndRecreation() throws Exception {
        fixture();long completed=System.currentTimeMillis();NativeUpdateActivity old=create(saved(true,completed));old.onResume();
        check(state(old).equals("VERIFYING"),"resume enters verifying until main delivery");Bundle b=new Bundle();old.onSaveInstanceState(b);
        check(b.getStringArray("update_candidate")!=null,"completed candidate is saved even while restore verification is in flight");
        awaitWorker(old);old.onPause();old.onDestroy();Handler.drain();
        NativeUpdateActivity fresh=create(b);fresh.onResume();deliver(fresh);
        check(state(fresh).equals("READY")&&get(fresh,"ready")!=null,"recreated activity verifies saved hint anew");
        check(((UpdateRecoveryPolicy.Candidate)get(fresh,"candidate")).completedAt==completed,"recreation does not renew candidate timestamp");
        check(message(fresh).contains("已允许安装"),"granted permission return stays on explicit action page");noLaunch(old,"destroyed original");noLaunch(fresh,"recreated activity");close(fresh);
    }
    private static void pausedCallbackAndResume() throws Exception {
        fixture();NativeUpdateActivity p=create(saved(false,System.currentTimeMillis()));p.onResume();awaitWorker(p);check(Handler.pending()>0,"real worker queues result before pause");
        p.onPause();Handler.drain();check(get(p,"ready")==null&&!state(p).equals("READY"),"queued verification callback cannot populate paused activity");
        p.onResume();deliver(p);check(state(p).equals("READY"),"fresh generation can restore after previous paused result was ignored");noLaunch(p,"pause/resume");close(p);
    }
    private static void explicitCancellation() throws Exception {
        fixture();NativeUpdateActivity p=create(saved(false,System.currentTimeMillis()));p.onResume();awaitWorker(p);call(p,"cancelByUser");Handler.drain();
        check(state(p).equals("IDLE")&&get(p,"candidate")==null&&get(p,"ready")==null,"cancellation revokes queued verification and clears candidate");
        check(message(p).contains("已取消"),"late callback cannot overwrite cancellation message");noLaunch(p,"explicit cancellation");close(p);
    }
    private static void destroyedCallback() throws Exception {
        fixture();NativeUpdateActivity p=create(saved(false,System.currentTimeMillis()));p.onResume();awaitWorker(p);p.finish();Bundle b=new Bundle();p.onSaveInstanceState(b);
        check(b.getStringArray("update_candidate")==null,"finishing activity does not persist candidate");int cleanup=UpdateInstallProvider.discardCalls.get();p.onDestroy();Handler.drain();
        check(get(p,"ready")==null&&get(p,"candidate")==null&&get(p,"content")==null,"destroyed page cannot be restored by queued callback");
        check(UpdateInstallProvider.discardCalls.get()==cleanup+1,"finishing delegates one cleanup request to provider");noLaunch(p,"destroyed callback");
    }
    private static void permissionThenInstaller() throws Exception {
        fixture();pm.permission=false;long completed=System.currentTimeMillis();NativeUpdateActivity p=create(saved(false,completed));p.onResume();deliver(p);noLaunch(p,"before explicit install");
        call(p,"install");check(p.launched.size()==1&&p.launched.get(0).action.equals("unknown_sources"),"explicit install may open required source permission page once");
        Bundle waiting=new Bundle();p.onSaveInstanceState(waiting);check(waiting.getStringArray("update_candidate")[3].equals("true"),"permission waiting state survives save");
        p.onPause();pm.permission=true;p.onResume();deliver(p);
        check(state(p).equals("READY")&&p.launched.size()==1,"permission return verifies but never automatically opens installer");
        check(message(p).contains("已允许安装"),"permission grant tells user to click again");
        call(p,"install");awaitWorker(p);check(p.launched.size()==1,"installer not launched before main-thread delivery");Handler.drain();
        check(p.launched.size()==2&&p.launched.get(1).action.equals(Intent.ACTION_VIEW),"second explicit install action verifies then launches installer");
        check(p.launched.get(1).packageName.equals("synthetic.system.installer"),"handoff selects system-resolved installer");
        Bundle handed=new Bundle();p.onSaveInstanceState(handed);check(handed.getStringArray("update_candidate")!=null,"candidate metadata is saved after installer handoff");
        p.onPause();int reads=pm.archiveReads;p.onResume();deliver(p);
        check(state(p).equals("READY")&&pm.archiveReads>reads&&p.launched.size()==2,"cancelled installation return re-verifies and waits without relaunching");
        check(((UpdateRecoveryPolicy.Candidate)get(p,"candidate")).completedAt==completed,"installer handoff and return do not extend candidate lifetime");close(p);
        pm.current=info(TARGET_CODE);NativeUpdateActivity rebuilt=create(handed);rebuilt.onResume();
        check(state(rebuilt).equals("INSTALLED")&&!button(rebuilt,"安装更新"),"rebuilding saved post-handoff page recognizes installed target");noLaunch(rebuilt,"post-handoff rebuild");close(rebuilt);
    }
    private static void latestAndLateDownload() throws Exception {
        fixture();pm.current=info(TARGET_CODE);NativeUpdateActivity p=create(null);p.onResume();call(p,"check");deliver(p);
        check(state(p).equals("CURRENT")&&!button(p,"安装更新")&&!button(p,"下载更新 · 0.0 MB"),"current version check never offers same-version update");noLaunch(p,"latest");close(p);
        fixture();p=create(null);p.onResume();call(p,"check");deliver(p);check(state(p).equals("AVAILABLE"),"isolated older-version check yields available candidate");
        call(p,"download");awaitWorker(p);Bundle b=new Bundle();p.onSaveInstanceState(b);check(b.getStringArray("update_candidate")==null,"uncommitted worker result is not saved as completed candidate");
        p.onPause();Handler.drain();check(!file.exists()&&get(p,"ready")==null&&get(p,"candidate")==null,"late verified download is discarded after page pause");
        noLaunch(p,"late download");close(p);
    }
}
