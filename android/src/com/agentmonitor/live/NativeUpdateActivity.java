package com.agentmonitor.live;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Account-independent native update page. Installation always requires a foreground user action. */
public final class NativeUpdateActivity extends Activity {
    private enum State { IDLE, CHECKING, AVAILABLE, DOWNLOADING, VERIFYING, READY, CURRENT, UNSUPPORTED, ERROR }
    private final ExecutorService worker = Executors.newSingleThreadExecutor(action -> new Thread(action, "MonitorUpdate"));
    private final Handler main = new Handler(Looper.getMainLooper());
    private NativeUi ui;
    private LinearLayout content;
    private TextView progressView;
    private UpdatePackage.Installed installed;
    private UpdateClient.Release release;
    private UpdatePackage.Verified ready;
    private UpdateRecoveryPolicy.Candidate candidate;
    private UpdateClient.Operation operation;
    private State state = State.IDLE;
    private String message = "检查 GitHub 上的最新正式版本。";
    private long generation;
    private boolean resumed, handedToInstaller, awaitingPermission;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); ui = new NativeUi(this);
        LinearLayout root = ui.column(); root.setBackground(ui.background());
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
        getWindow().setStatusBarColor(Color.TRANSPARENT); getWindow().setNavigationBarColor(ui.bg);
        if (!ui.dark) root.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout heading = ui.row(); heading.setPadding(ui.dp(6), ui.dp(6), ui.dp(18), ui.dp(4));
        heading.addView(ui.iconButton("back", "返回", this::finish)); heading.addView(ui.text("应用更新", 22, true)); root.addView(heading);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); scroll.setVerticalScrollBarEnabled(false);
        content = ui.column(); content.setPadding(ui.dp(18), ui.dp(16), ui.dp(18), ui.dp(24)); scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1)); setContentView(root); root.requestApplyInsets();
        try { installed = UpdatePackage.installed(this); }
        catch (Exception unavailable) { state = State.ERROR; message = "无法读取当前版本，请重新打开页面。"; }
        if (saved != null) {
            try { candidate = UpdateRecoveryPolicy.decode(saved.getStringArray("update_candidate")); }
            catch (RuntimeException invalid) { candidate = null; }
            if (candidate != null) awaitingPermission = candidate.awaitingPermission;
        }
        Context app = getApplicationContext(); worker.execute(() -> UpdateInstallProvider.prune(app));
        render();
    }
    private void render() {
        if (content == null || ui == null) return;
        content.removeAllViews(); progressView = null;
        LinearLayout current = ui.card(); current.addView(ui.text("Monitor", 26, true)); current.addView(ui.space(10));
        current.addView(ui.text(installed == null ? "当前版本未知" : "当前版本 " + installed.name, 17)); content.addView(current, wrap());
        content.addView(ui.space(20));
        String heading = state == State.CHECKING ? "正在检查更新" : state == State.DOWNLOADING ? "正在下载更新"
                : state == State.VERIFYING ? "正在校验安装包" : state == State.READY ? "可以安装了"
                : state == State.CURRENT ? "已是最新版本" : state == State.AVAILABLE ? "发现新版本"
                : state == State.UNSUPPORTED ? "此版本需要更新的 Android" : state == State.ERROR ? "暂时无法完成" : "让 Monitor 保持更新";
        content.addView(ui.text(heading, 22, true), wrap()); content.addView(ui.space(12));
        progressView = ui.text(message, 16); progressView.setTextColor(ui.muted); progressView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(progressView, wrap()); content.addView(ui.space(20));
        if (busy()) addButton("取消", false, this::cancelByUser);
        else if (state == State.READY && ready != null) {
            addButton(canInstall() ? "安装更新" : "允许安装此应用的更新", true, this::install);
            addButton("重新检查", false, this::check);
        } else if (release != null && installed != null && release.manifest.eligible(installed.code, Build.VERSION.SDK_INT)) {
            addButton(state == State.ERROR ? "重试下载" : "下载更新 · " + size(release.manifest.size), true, this::download);
            addButton("重新检查", false, this::check);
        } else if (installed != null) addButton(state == State.ERROR ? "重试检查" : "检查更新", true, this::check);
        if (release != null) {
            content.addView(ui.space(16)); LinearLayout notes = ui.card();
            notes.addView(ui.text("版本 " + release.manifest.versionName, 19, true)); notes.addView(ui.space(12));
            TextView body = ui.text(release.notes, 16); body.setTextIsSelectable(true); notes.addView(body, wrap()); content.addView(notes, wrap());
        }
        content.addView(ui.space(18)); TextView detail = ui.text("更新来自项目的 GitHub 正式发布。安装由系统确认；不会自动安装。", 15);
        detail.setTextColor(ui.muted); content.addView(detail, wrap());
        content.addView(ui.space(18));
        TextView license = ui.text("本应用使用 MiSans 字体，由小米提供，按 MiSans 字体知识产权许可协议授权。Monitor 源码采用 MIT；第三方组件和商标遵循各自条款。", 15);
        license.setTextColor(ui.muted); content.addView(license, wrap()); content.addView(ui.space(12));
        addButton("开源与许可", false, () -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Makabaka-zxh/agent-monitor/blob/main/THIRD_PARTY_NOTICES.md"))
                    .addCategory(Intent.CATEGORY_BROWSABLE)); }
            catch (RuntimeException unavailable) { message = "暂时无法打开浏览器。"; render(); }
        });
    }
    private void addButton(String label, boolean primary, Runnable action) {
        content.addView(ui.button(label, primary, action), wrap()); content.addView(ui.space(10));
    }
    private static LinearLayout.LayoutParams wrap() { return new LinearLayout.LayoutParams(-1, -2); }
    private boolean busy() { return state == State.CHECKING || state == State.DOWNLOADING || state == State.VERIFYING; }
    private void check() {
        if (!resumed || installed == null || busy()) return;
        discardReady(); release = null; state = State.CHECKING; message = "正在读取最新正式版本…";
        UpdateClient.Operation task = start(45000); long id = generation;
        WeakReference<NativeUpdateActivity> target = new WeakReference<>(this); Handler delivery = main;
        worker.execute(() -> {
            UpdateClient.Release result = null; String error = null;
            try { result = UpdateClient.check(task); } catch (Exception failed) { error = failure(failed); }
            finally { task.close(); }
            final UpdateClient.Release found = result; final String problem = error;
            delivery.post(() -> {
                NativeUpdateActivity page = current(target, id); if (page == null) return;
                page.operation = null;
                if (found == null) { page.state = State.ERROR; page.message = problem; }
                else {
                    page.release = found;
                    if (!found.manifest.newerThan(page.installed.code)) { page.state = State.CURRENT; page.message = "当前版本已包含最新正式发布的更新。"; }
                    else if (!found.manifest.supportsSdk(Build.VERSION.SDK_INT)) { page.state = State.UNSUPPORTED; page.message = "当前手机系统暂不支持此版本。升级手机系统后可重新检查。"; }
                    else { page.state = State.AVAILABLE; page.message = "版本 " + found.manifest.versionName + " · " + size(found.manifest.size); }
                }
                page.render();
            });
        });
    }
    private void download() {
        if (!resumed || release == null || installed == null || busy() || !release.manifest.eligible(installed.code, Build.VERSION.SDK_INT)) return;
        discardReady(); state = State.DOWNLOADING; message = "正在下载，离开此页面会取消。";
        UpdateClient.Operation task = start(5 * 60 * 1000L); long id = generation;
        Context app = getApplicationContext(); UpdateClient.Release selected = release;
        WeakReference<NativeUpdateActivity> target = new WeakReference<>(this); Handler delivery = main;
        worker.execute(() -> {
            File file = null; UpdatePackage.Verified verified = null; String error = null; int[] lastPercent = {-1};
            try {
                file = UpdateClient.download(app, selected, task, (bytes, total) -> {
                    int percent = (int) (bytes * 100 / total); if (percent == lastPercent[0]) return; lastPercent[0] = percent;
                    delivery.post(() -> { NativeUpdateActivity page = current(target, id);
                        if (page != null && page.progressView != null) page.progressView.setText("已下载 " + percent + "% · " + size(bytes) + " / " + size(total)); });
                });
                verified = UpdatePackage.verify(app, file, selected.manifest, task);
            } catch (Exception failed) { error = failure(failed); }
            finally { task.close(); if (verified == null && file != null) file.delete(); }
            final UpdatePackage.Verified result = verified; final String problem = error;
            delivery.post(() -> {
                NativeUpdateActivity page = current(target, id);
                if (page == null) { if (result != null) result.file.delete(); return; }
                page.operation = null; page.ready = result; page.state = result == null ? State.ERROR : State.READY;
                if (result != null) page.candidate = UpdateRecoveryPolicy.capture(result.file.getName(), result.manifest, System.currentTimeMillis());
                page.message = result == null ? problem : "下载完成，文件和应用签名已校验。"; page.render();
            });
        });
    }
    private boolean canInstall() { return Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls(); }
    private void restoreCandidate() {
        if (!resumed || installed == null || candidate == null || ready != null || busy()) return;
        if (!candidate.fresh(System.currentTimeMillis())) {
            discardReady(); state = State.ERROR; message = "下载的更新已过期，请重新检查。"; render(); return;
        }
        state = State.VERIFYING; message = "正在恢复并重新校验已下载的更新…";
        UpdateClient.Operation task = start(45000); long id = generation;
        Context app = getApplicationContext(); UpdateRecoveryPolicy.Candidate selected = candidate;
        WeakReference<NativeUpdateActivity> target = new WeakReference<>(this); Handler delivery = main;
        worker.execute(() -> {
            UpdatePackage.Verified result = null; String error = null;
            try { result = UpdatePackage.verify(app, selected.file(app.getCacheDir()), selected.manifest, task); }
            catch (Exception failed) { error = failure(failed); } finally { task.close(); }
            final UpdatePackage.Verified verified = result; final String problem = error;
            delivery.post(() -> {
                NativeUpdateActivity page = current(target, id); if (page == null) return;
                page.operation = null;
                if (verified == null) { page.discardReady(); page.state = State.ERROR; page.message = problem; }
                else {
                    page.ready = verified; page.state = State.READY;
                    page.message = "版本 " + verified.manifest.versionName + " 已恢复并重新校验。"
                            + (page.awaitingPermission ? page.canInstall() ? "已允许安装，请点安装更新继续。" : "安装权限尚未开启，可以稍后再试。"
                            : "请点安装更新继续。");
                    page.awaitingPermission = false;
                }
                // Restoring an Activity never launches the installer or the permission page.
                page.render();
            });
        });
    }
    private void install() {
        if (!resumed || ready == null || busy()) return;
        if (candidate == null || !candidate.fresh(System.currentTimeMillis())) {
            discardReady(); state = State.ERROR; message = "下载的更新已过期，请重新检查。"; render(); return;
        }
        if (!canInstall()) {
            awaitingPermission = true;
            try { startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()))); }
            catch (RuntimeException unavailable) { awaitingPermission = false; message = "请在系统设置中允许 Monitor 安装应用，然后返回重试。"; render(); }
            return;
        }
        state = State.VERIFYING; message = "再次核对安装文件…"; UpdateClient.Operation task = start(45000); long id = generation;
        Context app = getApplicationContext(); UpdatePackage.Verified candidate = ready;
        WeakReference<NativeUpdateActivity> target = new WeakReference<>(this); Handler delivery = main;
        worker.execute(() -> {
            UpdatePackage.Verified result = null; String error = null;
            try { result = UpdatePackage.verify(app, candidate.file, candidate.manifest, task); }
            catch (Exception failed) { error = failure(failed); } finally { task.close(); }
            final UpdatePackage.Verified verified = result; final String problem = error;
            delivery.post(() -> {
                NativeUpdateActivity page = current(target, id); if (page == null) return;
                page.operation = null;
                if (verified == null) { page.discardReady(); page.state = State.ERROR; page.message = problem; page.render(); return; }
                page.state = State.READY; page.ready = verified; page.launchInstaller();
            });
        });
    }
    private void launchInstaller() {
        if (!resumed || ready == null || !canInstall()) { message = "请确认安装权限后，再点安装更新。"; render(); return; }
        try {
            Uri uri = UpdateInstallProvider.grant(this, ready);
            Intent install = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            install.setClipData(ClipData.newRawUri("Monitor update", uri));
            java.util.List<ResolveInfo> installers = getPackageManager().queryIntentActivities(install, PackageManager.MATCH_DEFAULT_ONLY | PackageManager.MATCH_SYSTEM_ONLY);
            ResolveInfo system = installers.isEmpty() ? null : installers.get(0);
            if (system == null || system.activityInfo == null) throw new IllegalStateException();
            install.setClassName(system.activityInfo.packageName, system.activityInfo.name);
            startActivity(install); handedToInstaller = true;
            message = "请在系统界面确认安装。若取消，可返回后再试。";
        } catch (Exception unavailable) { message = "暂时无法打开系统安装界面，请稍后重试。"; }
        render();
    }
    private UpdateClient.Operation start(long budget) {
        cancelOperation(); operation = new UpdateClient.Operation(budget); render(); return operation;
    }
    private void cancelOperation() {
        generation++; if (operation != null) { operation.close(); operation = null; }
    }
    private void cancelByUser() {
        if (!busy()) return; cancelOperation();
        if (ready == null && candidate != null) discardReady();
        state = ready != null ? State.READY : release != null ? State.AVAILABLE : State.IDLE;
        message = "操作已取消，可以随时重试。"; render();
    }
    private void discardReady() {
        if (!handedToInstaller) {
            if (candidate != null) candidate.discard(getCacheDir());
            else if (ready != null) ready.file.delete();
        }
        ready = null; candidate = null; handedToInstaller = false; awaitingPermission = false;
    }
    private static NativeUpdateActivity current(WeakReference<NativeUpdateActivity> reference, long id) {
        NativeUpdateActivity page = reference.get();
        return page != null && page.resumed && !page.isFinishing() && !page.isDestroyed() && page.generation == id ? page : null;
    }
    private static String failure(Exception error) {
        // Transport exception text can include remote URLs. Only our own fixed messages are shown.
        String message = error.getMessage();
        if (error.getClass() == java.io.IOException.class && message != null) switch (message) {
            case "请求超时，请重试": case "操作已取消": case "当前没有可用的正式版本":
            case "更新信息不符合要求": case "更新信息下载不完整": case "安装包与更新信息不一致":
            case "无法保存安装包": case "安装包大小不一致": case "安装包超出预期大小":
            case "安装包校验失败，请重新下载": case "无法保存已校验的安装包": case "更新信息过大":
            case "更新地址不受信任": case "更新地址跳转异常": case "还没有可用的正式版本，请稍后再试":
            case "GitHub 暂时限制检查，请稍后重试": case "暂时无法读取更新，请重试": case "更新文件编码不受支持":
            case "发布文件列表不符合要求": case "发布文件重复或尚未就绪": case "该版本尚未提供应用内更新文件":
            case "更新信息字段无效": case "更新信息数字无效": case "安装包大小或位置无效":
            case "安装包版本或应用身份不一致": case "安装包签名与当前应用不一致": case "应用版本无效": return message;
            default: break;
        }
        return "网络或更新文件暂不可用，请重试。";
    }
    private static String size(long bytes) { return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / 1048576.0); }
    @Override protected void onResume() {
        super.onResume(); resumed = true;
        if (candidate != null && ready == null && installed != null) { restoreCandidate(); return; }
        if (awaitingPermission) { awaitingPermission = false; message = canInstall() ? "已允许安装。请点安装更新继续。" : "安装权限尚未开启，可以稍后再试。"; }
        render();
    }
    @Override protected void onSaveInstanceState(Bundle saved) {
        // Save completed candidate metadata even while a later verification is in flight.
        // Do not save Verified objects, partial downloads, account state or installer grants.
        if (candidate != null && !handedToInstaller && !isFinishing()) saved.putStringArray("update_candidate", candidate.encode(awaitingPermission));
        super.onSaveInstanceState(saved);
    }
    @Override protected void onPause() {
        resumed = false;
        if (busy()) { cancelOperation(); state = ready != null ? State.READY : release != null ? State.AVAILABLE : State.IDLE; message = "操作已暂停，返回后可重试。"; }
        super.onPause();
    }
    @Override protected void onDestroy() {
        resumed = false; cancelOperation(); worker.shutdownNow();
        if (isFinishing()) discardReady();
        // Worker callbacks hold only a WeakReference and dispose any late download themselves.
        content = null; progressView = null; ui = null; super.onDestroy();
    }
}
