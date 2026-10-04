package com.agentmonitor.live;

import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.SharedPreferences;

/** Exercises the production builders without permissions, requests or UI simulation. */
public final class UsageAlertNotificationTest {
    private static int checks;
    private static void check(boolean value, String label) { ++checks; if (!value) throw new AssertionError(label); }
    private static final class RenderingOnlyContext extends Context {
        @Override public SharedPreferences getSharedPreferences(String name, int mode) {
            throw new AssertionError("Notification rendering must never read/write reminder dedup, enabled or quota preferences");
        }
        @Override public <T> T getSystemService(Class<T> type) {
            throw new AssertionError("Notification rendering must never schedule jobs, change permissions or query network");
        }
    }
    private static void ordinary(Notification n, String brand, int icon) {
        check("monitor_usage_v1".equals(n.getChannelId()), "same actual quota channel");
        check(brand.contentEquals(n.contentTitle) && n.smallIcon == icon, "correct provider title and icon");
        check(n.visibility == Notification.VISIBILITY_SECRET, "hidden from lockscreen");
        check((n.flags & Notification.FLAG_AUTO_CANCEL) != 0, "can dismiss by opening");
        check((n.flags & (Notification.FLAG_NO_CLEAR | Notification.FLAG_ONGOING_EVENT)) == 0, "ordinary, freely dismissible notice");
        check(n.actions == null && n.deleteIntent == null, "cannot stop tracking or affect another notification");
        check(!n.requestPromotedOngoing && n.shortCriticalText == null, "test does not claim live tracking");
        check(brand.contentEquals(n.publicVersion.contentTitle) && n.publicVersion.smallIcon == icon, "public fallback remains brand only");
        check(n.publicVersion.contentText == null && n.publicVersion.bigText == null, "public fallback contains no test or quota content");
        check(n.contentIntent != null && "com.agentmonitor.live.OPEN_USAGE".equals(n.contentIntent.intent.getAction()), "opens existing usage screen");
        check((n.contentIntent.flags & PendingIntent.FLAG_IMMUTABLE) != 0, "immutable notification intent");
    }
    public static void main(String[] args) {
        PendingIntent.reset(); Context context = new RenderingOnlyContext();
        Notification real = UsageAlertNotification.real(context, "monitor_usage_v1", "claude", "实际额度内容", "来源电脑", false);
        ordinary(real, "Claude Code", R.drawable.ic_claude);
        check("实际额度内容".contentEquals(real.contentText) && real.bigText.toString().contains("来源电脑"), "real notification content unchanged");
        check(real.timeoutAfter == 0, "real notification does not adopt test expiry");
        check((real.flags & Notification.FLAG_ONLY_ALERT_ONCE) == 0, "a later reset reason may alert while the earlier low-balance notice still exists");
        Notification reset = UsageAlertNotification.real(context, "monitor_usage_v1", "claude", "预计 15 分钟后重置", "来源电脑", false);
        check((reset.flags & Notification.FLAG_ONLY_ALERT_ONCE) == 0, "independently deduplicated reset reason is not silenced by notification rendering");
        check(reset.contentIntent == real.contentIntent, "new reason keeps the same usage navigation intent");
        Notification lockedReal = UsageAlertNotification.real(context, "monitor_usage_v1", "claude", "额度剩余 5%", "来源电脑 5 小时", true);
        ordinary(lockedReal, "Claude Code", R.drawable.ic_claude);
        check("额度剩余 5%".contentEquals(lockedReal.contentText), "real notice created while locked retains its reason for unlock");
        check("额度剩余 5%\n来源电脑 5 小时".contentEquals(lockedReal.bigText), "real notice created while locked retains source detail for unlock");
        check((lockedReal.flags & Notification.FLAG_ONLY_ALERT_ONCE) == 0, "locked construction does not silence a new real reason");
        check("Claude Code".contentEquals(lockedReal.publicVersion.contentTitle)
                && lockedReal.publicVersion.contentText == null && lockedReal.publicVersion.bigText == null,
                "public payload contains brand only, without balance, reset or source details");
        Notification test = UsageAlertNotification.test(context, "monitor_usage_v1", false);
        ordinary(test, "Codex", R.drawable.ic_codex);
        check("测试提醒".contentEquals(test.contentText), "collapsed test explicitly identified");
        check(test.bigText.toString().equals("测试提醒\n用于检查通知显示，不代表真实额度。"), "expanded test never fabricates usage or reset values");
        check(!test.bigText.toString().contains("%") && !test.bigText.toString().contains("Token"), "no synthetic quota or usage number");
        check(test.timeoutAfter == 120000, "test expires after two minutes");
        check((test.flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0, "repeated manual test does not repeatedly sound while its notice remains active");
        check(UsageAlertNotification.TEST_TAG.startsWith("usage:") && "usage:test".equals(UsageAlertNotification.TEST_TAG), "fixed test identity is included in existing account cleanup");
        check("实际额度内容".contentEquals(real.contentText) && real.timeoutAfter == 0, "building test cannot mutate already-built real alert");
        Notification repeated = UsageAlertNotification.test(context, "monitor_usage_v1", false);
        check(repeated.contentIntent == test.contentIntent, "repeated test reuses existing immutable navigation intent");
        Notification locked = UsageAlertNotification.test(context, "monitor_usage_v1", true);
        ordinary(locked, "Codex", R.drawable.ic_codex);
        check("测试提醒".contentEquals(locked.contentText)
                && "测试提醒\n用于检查通知显示，不代表真实额度。".contentEquals(locked.bigText),
                "locked test stays hidden by SECRET visibility and remains identifiable after unlock");
        check(locked.timeoutAfter == 120000, "locked test remains bounded");
        check((locked.flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0, "locked test retains once-only alert behavior");
        System.out.println("UsageAlertNotificationTest: " + checks + " checks passed");
    }
}
