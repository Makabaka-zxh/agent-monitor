package com.agentmonitor.live;

import android.app.Activity;
import android.app.Dialog;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Toast;
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
        showSection(LiveSettingsSections.Section.DISPLAY);
    }

    void showNotificationSettings() {
        showSection(LiveSettingsSections.Section.NOTIFICATIONS);
    }

    void showSystemHelp() {
        showSection(LiveSettingsSections.Section.SYSTEM_HELP);
    }

    private void showSection(LiveSettingsSections.Section section) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        LivePresentationSheet current = new LivePresentationSheet(activity, section.title);
        for (LiveSettingsSections.Entry entry : LiveSettingsSections.entries(section, deviceBrand, Build.VERSION.SDK_INT)) {
            current.action(entry.label, actionFor(entry));
        }
        showSheet(current);
    }

    private Runnable actionFor(LiveSettingsSections.Entry entry) {
        switch (entry) {
            case PRESENTATION: return this::showPresentation;
            case SYSTEM_LIVE: return this::openPromotionSettings;
            case NOTIFICATION_PERMISSION: return this::openNotificationPermissions;
            case TRACKING_CHANNEL: return this::openTrackingChannelSettings;
            case BACKGROUND: return this::showBackgroundHelp;
            case XIAOMI_ISLAND: return this::showXiaomiHelp;
            case XIAOMI_BACK_SCREEN: return this::showXiaomiBackScreenHelp;
            case OPPO_LIVE: return this::showOppoHelp;
            case SAMSUNG_LIVE: return this::showSamsungHelp;
            case CLEAR_ALL: return this::showClearAllHelp;
            default: throw new IllegalArgumentException("Unknown settings entry");
        }
    }

    void showPresentation() {
        LivePresentationSheet current = new LivePresentationSheet(activity, "显示内容");
        String help = "预览使用示例数据，不会启动跟踪\n顶部显示工具及所选电脑名称；收起时看任务和状态，展开后查看所选用量和额度\n点击工具名称可切换示例\n展开「自定义内容」可分别选择六项信息；折叠只隐藏选项，不改变选择\n内容预设不会改变锁屏详情开关\n选择会自动保存，实际卡片样式由系统决定\n\n锁屏默认显示工具和任务状态，已跟踪时长随上方选择显示\n开启「锁屏显示任务详情」后，所选详情会作为锁屏公开内容；关闭可隐藏详情，系统仍可禁止通知显示\n暂无数据的项目自动隐藏";
        if (deviceBrand == DeviceBrand.XIAOMI) {
            XiaomiOnboarding.State onboarding = XiaomiOnboarding.state(activity);
            help += "\n\n" + (onboarding == XiaomiOnboarding.State.UNCONFIGURED
                    ? "这些选项用于任务跟踪通知\n小米专属实况仍待接入"
                    : onboarding == XiaomiOnboarding.State.CONFIGURED
                    ? "这些选项用于任务跟踪通知\n小米专属实况显示工具和执行状态，并按选择显示已跟踪时长，不含任务详情"
                    : "这些选项用于任务跟踪通知，小米接入状态暂无法确认");
        } else if (deviceBrand.isOppoFamily()) {
            help += "\n\n流体云显示空间由系统决定，部分内容可能折叠\n这是通知内容预览，不代表厂商模板已经开通";
        }
        LivePresentationEditor editor = new LivePresentationEditor(activity, help);
        current.pin(editor.preview); current.body(editor.controls);
        showSheet(current);
    }

    void showPrivacySettings() {
        LivePresentationSheet current = new LivePresentationSheet(activity, "锁屏隐私");
        NativeUi ui = new NativeUi(activity);
        android.widget.Switch details = new android.widget.Switch(activity);
        details.setText("锁屏显示任务详情"); details.setTextColor(ui.text); details.setTextSize(15); details.setTypeface(ui.regular);
        details.setShowText(false); details.setSwitchPadding(ui.dp(16)); details.setMinHeight(ui.dp(52));
        details.setPadding(0, ui.dp(8), 0, ui.dp(8));
        // Read first, then attach the existing setter: opening this page writes nothing.
        details.setChecked(LivePresentationPreferences.lockscreenDetails(activity));
        details.setOnCheckedChangeListener((button, checked) -> LivePresentationPreferences.setLockscreenDetails(activity, checked));
        current.body(details);
        current.note("默认仅显示工具、状态和所选时长\n开启后，所选详情会作为锁屏公开内容；关闭可隐藏详情\n系统仍可禁止通知显示");
        showSheet(current);
    }

    void showBackgroundHelp() {
        LivePresentationSheet current = new LivePresentationSheet(activity, "后台运行");
        if (deviceBrand == DeviceBrand.OPPO) {
            current.note("实况需要允许 Monitor 在后台运行\n若更新时间停住，请打开 Monitor 设置 → 耗电管理，选择「完全允许后台行为」，再重新开始跟踪\n\n系统冻结应用时，锁屏可能保留旧的实况内容");
        } else {
            current.note("若任务状态长时间不更新，请打开 Monitor 的应用信息，检查通知权限和后台运行限制\n设置名称会随系统版本不同\n\n允许后台运行可能增加耗电；系统冻结应用时，锁屏可能保留旧的实况内容");
        }
        current.action("打开 Monitor 设置", this::openApplicationSettings);
        showSheet(current);
    }

    private void showOppoHelp() {
        LivePresentationSheet current = new LivePresentationSheet(activity, "流体云");
        current.note("Monitor 使用任务跟踪通知申请实况展示\n流体云的样式与是否显示由当前系统决定；「显示内容」中可选择任务、用量和额度\n\n若内容停止更新，请检查后台运行设置");
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
                ? "小米专属实况尚未开通；系统实况按本机支持和授权显示"
                : "应用接入与实际展示仍需验证，通知开关不代表接入已完成");
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
                ? "需先完成小米超级岛接入，再由系统适配背屏\n当前版本尚不能保证背屏展示"
                : "背屏由小米系统适配，需在支持的机型上确认实际展示");
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
        LivePresentationSheet sheet = new LivePresentationSheet(activity, "三星实况通知");
        sheet.note("若已允许通知仍不显示，请在开发者选项开启「所有应用程序的实时通知」。\n\n此选项作用于全机符合条件的通知。");
        sheet.action("打开开发者选项", this::openDevelopmentSettings);
        sheet.action("检查通知权限", this::openNotificationPermissions);
        showSheet(sheet);
    }

    private void showClearAllHelp() {
        LivePresentationSheet sheet = new LivePresentationSheet(activity, "清理时保留实况");
        sheet.note("一键清除后恢复正在跟踪的实况；单独划走或点「停止追踪」仍会关闭。\n\n需要通知访问权限。系统授权范围较广，Monitor 的代码只处理自己的实况通知，不保存或上传其他应用的通知。");
        sheet.action("打开授权设置", this::openClearAllSettings);
        showSheet(sheet);
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
            showDevelopmentHelp("请先开启系统开发者选项，再查找「所有应用程序的实时通知」\n\n"
                    + "可在系统设置中搜索“开发者选项”\n若系统未提供该选项，Monitor 无法自行开启");
            return;
        }
        try {
            activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
        } catch (Exception ignored) {
            showDevelopmentHelp("系统暂未提供可直接打开的开发者选项\n\n"
                    + "请在系统设置中搜索“开发者选项”，再查找「所有应用程序的实时通知」\n若系统未提供该选项，Monitor 无法自行开启");
        }
    }

    private void showDevelopmentHelp(String message) {
        LivePresentationSheet sheet = new LivePresentationSheet(activity, "开发者选项");
        sheet.note(message);
        sheet.action("打开系统设置", () -> {
            try { activity.startActivity(new Intent(Settings.ACTION_SETTINGS)); }
            catch (Exception ignored) { notice("请手动打开系统设置，查找开发者选项"); }
        });
        showSheet(sheet);
    }

    void openPromotionSettings() {
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

    void dismiss() { if (dialog != null) dialog.dismiss(); }
    boolean isShowing() { return dialog != null && dialog.isShowing(); }

    private void notice(String message) {
        if (!activity.isDestroyed()) Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
    }
}
