package com.agentmonitor.live;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** User-requested settings guidance; never changes system settings or infers live display. */
final class LiveActivitySettings {
    private final Activity activity;
    private final Consumer<View> applyFont;
    private final DeviceBrand deviceBrand = DeviceBrand.detect(Build.MANUFACTURER, Build.BRAND);
    private Dialog dialog;

    LiveActivitySettings(Activity activity, Consumer<View> applyFont) {
        this.activity = activity;
        this.applyFont = applyFont;
    }

    void show() {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        dismiss();
        List<String> choices = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        choices.add("显示内容"); actions.add(this::showPresentation);
        if (deviceBrand == DeviceBrand.XIAOMI) {
            choices.add("小米超级岛"); actions.add(this::showXiaomiHelp);
            choices.add("妙享背屏"); actions.add(this::showXiaomiBackScreenHelp);
        } else if (deviceBrand.isOppoFamily()) {
            choices.add("流体云"); actions.add(this::showOppoHelp);
        }
        if (deviceBrand == DeviceBrand.XIAOMI || deviceBrand.isOppoFamily()) {
            choices.add("后台运行"); actions.add(this::showBackgroundHelp);
        }
        choices.add("通知权限"); actions.add(this::openNotificationPermissions);
        choices.add("任务跟踪通知"); actions.add(this::openTrackingChannelSettings);
        if (Build.VERSION.SDK_INT >= 36) {
            choices.add("系统实况设置"); actions.add(this::openPromotionSettings);
            if (deviceBrand == DeviceBrand.SAMSUNG) {
                choices.add("三星实况帮助"); actions.add(this::showSamsungHelp);
            }
        }
        choices.add("清理时保留实况"); actions.add(this::showClearAllHelp);
        LivePresentationSheet current = new LivePresentationSheet(activity, deviceBrand.settingsTitle());
        for (int i = 0; i < choices.size(); i++) current.action(choices.get(i), actions.get(i));
        showSheet(current);
    }

    private void showPresentation() {
        LivePresentationSheet current = new LivePresentationSheet(activity, "显示内容");
        String help = "预览使用示例数据，不会启动跟踪。显示内容会自动保存，实际卡片样式由系统决定。\n\n锁屏只显示工具名称和图标，暂无数据的项目自动隐藏。";
        if (deviceBrand == DeviceBrand.XIAOMI) {
            XiaomiOnboarding.State onboarding = XiaomiOnboarding.state(activity);
            help += "\n\n" + (onboarding == XiaomiOnboarding.State.UNCONFIGURED
                    ? "这些选项用于任务跟踪通知。小米专属实况仍待接入。"
                    : onboarding == XiaomiOnboarding.State.CONFIGURED
                    ? "这些选项用于任务跟踪通知，小米专属实况仅显示工具名称和图标。"
                    : "这些选项用于任务跟踪通知，小米接入状态暂无法确认。");
        } else if (deviceBrand.isOppoFamily()) {
            help += "\n\n流体云显示空间由系统决定，部分内容可能折叠。这是通知内容预览，不代表厂商模板已经开通。";
        }
        LivePresentationEditor editor = new LivePresentationEditor(activity, help);
        current.pin(editor.preview); current.body(editor.controls);
        showSheet(current);
    }

    private void showBackgroundHelp() {
        LivePresentationSheet current = new LivePresentationSheet(activity, "后台运行");
        if (deviceBrand == DeviceBrand.OPPO) {
            current.note("实况需要允许 Monitor 在后台运行。若更新时间停住，请打开 Monitor 设置 → 耗电管理，选择「完全允许后台行为」，再重新开始跟踪。\n\n系统冻结应用时，锁屏可能保留旧的实况内容。");
        } else {
            current.note("若任务状态长时间不更新，请打开 Monitor 的应用信息，检查通知权限和后台运行限制。设置名称会随系统版本不同。\n\n允许后台运行可能增加耗电；系统冻结应用时，锁屏可能保留旧的实况内容。");
        }
        current.action("打开 Monitor 设置", this::openApplicationSettings);
        showSheet(current);
    }

    private void showOppoHelp() {
        LivePresentationSheet current = new LivePresentationSheet(activity, "流体云");
        current.note("Monitor 使用任务跟踪通知申请实况展示。流体云的样式与是否显示由当前系统决定；「显示内容」中可选择任务、用量和额度。\n\n若内容停止更新，请检查后台运行设置。");
        current.action("显示内容", this::showPresentation);
        current.action("通知权限", this::openNotificationPermissions);
        current.action("后台运行", this::showBackgroundHelp);
        showSheet(current);
    }

    private void showXiaomiHelp() {
        LivePresentationSheet loading = new LivePresentationSheet(activity, "小米超级岛");
        loading.note("正在读取本机的实况设置…");
        showSheet(loading);
        Handler main = new Handler(Looper.getMainLooper());
        Runnable timedOut = () -> {
            if (dialog == loading && loading.isShowing() && !activity.isFinishing() && !activity.isDestroyed())
                showXiaomiResult(null);
        };
        main.postDelayed(timedOut, 5000);
        // Xiaomi documents canShowFocus as a potentially slow provider call.
        XiaomiLiveCapabilities.refresh(activity, capabilities -> {
            main.removeCallbacks(timedOut);
            if (dialog != loading || !loading.isShowing() || activity.isFinishing() || activity.isDestroyed()) return;
            showXiaomiResult(capabilities);
        });
    }

    private void showXiaomiResult(XiaomiLiveCapabilities value) {
        LivePresentationSheet current = new LivePresentationSheet(activity, "小米超级岛");
        current.status("设备支持", xiaomiDeviceStatus(value));
        current.status("实况开关", value == null ? "暂无法确认"
                : value.focusPermission == XiaomiLiveCapabilities.State.ENABLED ? "已允许"
                : value.focusPermission == XiaomiLiveCapabilities.State.DISABLED ? "未允许" : "暂无法确认");
        XiaomiOnboarding.State onboarding = XiaomiOnboarding.state(activity);
        current.status("应用接入", onboarding == XiaomiOnboarding.State.UNCONFIGURED ? "待接入"
                : onboarding == XiaomiOnboarding.State.CONFIGURED ? "待验证" : "暂无法确认");
        current.note(onboarding == XiaomiOnboarding.State.UNCONFIGURED
                ? "小米专属实况尚未开通；系统实况按本机支持和授权显示。"
                : "应用接入与实际展示仍需验证，通知开关不代表接入已完成。");
        current.action("重新检查", this::showXiaomiHelp);
        current.action("通知权限", this::openNotificationPermissions);
        showSheet(current);
    }

    private static String xiaomiDeviceStatus(XiaomiLiveCapabilities value) {
        if (value == null || value.protocolVersion < 0 || value.island == XiaomiLiveCapabilities.State.UNKNOWN)
            return "暂无法确认";
        if (value.protocolVersion == 0 || value.island == XiaomiLiveCapabilities.State.DISABLED)
            return "未报告支持";
        return value.protocolVersion == 3 ? "已支持" : "尚待适配";
    }

    private void showXiaomiBackScreenHelp() {
        LivePresentationSheet current = new LivePresentationSheet(activity, "妙享背屏");
        XiaomiOnboarding.State onboarding = XiaomiOnboarding.state(activity);
        current.status("背屏实况", onboarding == XiaomiOnboarding.State.UNCONFIGURED ? "待接入"
                : onboarding == XiaomiOnboarding.State.CONFIGURED ? "待验证" : "暂无法确认");
        current.note(onboarding == XiaomiOnboarding.State.UNCONFIGURED
                ? "需先完成小米超级岛接入，再由系统适配背屏。当前版本尚不能保证背屏展示。"
                : "背屏由小米系统适配，需在支持的机型上确认实际展示。");
        current.action("查看超级岛状态", this::showXiaomiHelp);
        if (onboarding == XiaomiOnboarding.State.CONFIGURED) {
            current.action("背屏系统设置", () -> {
                try { activity.startActivity(new Intent(Settings.ACTION_SETTINGS)); }
                catch (Exception ignored) { notice("请手动打开系统设置，查找妙享背屏"); }
            });
        }
        showSheet(current);
    }

    private void openApplicationSettings() {
        try {
            activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:" + activity.getPackageName())));
        } catch (Exception unavailable) { notice("请在系统设置中打开 Monitor 的应用信息"); }
    }

    private void showSheet(LivePresentationSheet current) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        dismiss(); dialog = current;
        current.setOnDismissListener(target -> { if (dialog == current) dialog = null; });
        current.show();
    }

    private void showSamsungHelp() {
        showDialog(builder().setTitle("三星实况通知")
                .setMessage("若已允许通知仍不显示，请在开发者选项开启「所有应用程序的实时通知」。\n\n"
                        + "此选项作用于全机符合条件的通知。")
                .setNeutralButton("通知权限", (target, which) -> openNotificationPermissions())
                .setNegativeButton("取消", null)
                .setPositiveButton("开发者选项", (target, which) -> openDevelopmentSettings()));
    }

    private void showClearAllHelp() {
        showDialog(builder().setTitle("清理时保留实况")
                .setMessage("一键清除后恢复正在跟踪的实况；单独划走或点「停止跟踪」仍会关闭。\n\n"
                        + "需要通知访问权限。系统授权范围较广，Monitor 的代码只处理自己的实况通知，不保存或上传其他应用的通知。")
                .setNegativeButton("取消", null)
                .setPositiveButton("打开授权设置", (target, which) -> openClearAllSettings()));
    }

    private void openClearAllSettings() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                ComponentName component = new ComponentName(activity, TrackingRemovalListener.class);
                activity.startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                        .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component.flattenToString()));
                return;
            } catch (Exception ignored) { }
        }
        try { activity.startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); }
        catch (Exception ignored) { notice("请在系统设置中搜索通知访问权限，选择 Monitor"); }
    }

    private void openDevelopmentSettings() {
        boolean disabled = false;
        try {
            disabled = Settings.Global.getInt(activity.getContentResolver(), Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 0;
        } catch (SecurityException ignored) { }
        if (disabled) {
            showDevelopmentHelp("请先开启系统开发者选项，再查找「所有应用程序的实时通知」。\n\n"
                    + "可在系统设置中搜索“开发者选项”。若系统未提供该选项，Monitor 无法自行开启。");
            return;
        }
        try {
            activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
        } catch (Exception ignored) {
            showDevelopmentHelp("系统暂未提供可直接打开的开发者选项。\n\n"
                    + "请在系统设置中搜索“开发者选项”，再查找「所有应用程序的实时通知」。若系统未提供该选项，Monitor 无法自行开启。");
        }
    }

    private void showDevelopmentHelp(String message) {
        showDialog(builder().setTitle("开发者选项").setMessage(message)
                .setNegativeButton("关闭", null)
                .setPositiveButton("打开系统设置", (target, which) -> {
                    try { activity.startActivity(new Intent(Settings.ACTION_SETTINGS)); }
                    catch (Exception ignored) { notice("请手动打开系统设置，查找开发者选项"); }
                }));
    }

    private void openPromotionSettings() {
        if (Build.VERSION.SDK_INT >= 36) {
            try {
                activity.startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName()));
                return;
            } catch (Exception ignored) { }
        }
        notice("系统暂未提供单独的实况设置");
        openNotificationPermissions();
    }

    void openTrackingChannelSettings() {
        try {
            NotificationManager manager = activity.getSystemService(NotificationManager.class);
            // A fresh installation has no tracking channel until the first session starts.
            if (manager != null && manager.getNotificationChannel(TrackingService.CHANNEL) != null) {
                activity.startActivity(new Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName())
                        .putExtra(Settings.EXTRA_CHANNEL_ID, TrackingService.CHANNEL));
                return;
            }
        } catch (Exception ignored) { }
        openNotificationPermissions();
    }

    private void openNotificationPermissions() {
        try {
            activity.startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, activity.getPackageName()));
        } catch (Exception unavailable) {
            try { activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:" + activity.getPackageName()))); }
            catch (Exception ignored) { notice("请在系统设置中打开 Monitor 的通知权限"); }
        }
    }

    private AlertDialog.Builder builder() {
        boolean dark = (activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        return new AlertDialog.Builder(activity, dark ? android.R.style.Theme_Material_Dialog_Alert : android.R.style.Theme_Material_Light_Dialog_Alert);
    }

    private void showDialog(AlertDialog.Builder builder) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        dismiss();
        AlertDialog current = builder.create();
        dialog = current;
        current.setOnDismissListener(target -> { if (dialog == current) dialog = null; });
        current.show();
        applyFont.accept(current.getWindow().getDecorView());
    }

    void dismiss() { if (dialog != null) dialog.dismiss(); }

    private void notice(String message) {
        if (!activity.isDestroyed()) Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
    }
}
