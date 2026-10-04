package com.agentmonitor.live;

import android.app.Notification;
import android.content.Context;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.service.notification.StatusBarNotification;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import org.json.JSONObject;

/** Offline recording stubs; no device, account, network, notification renderer or OEM authorization. */
public final class NotificationDiagnosticsTest {
    private static int checks;
    private static final String PRIVATE = "private-title-result-task-token-https://private.example/?token=secret";
    private static void check(boolean condition, String message) { checks++; if (!condition) throw new AssertionError(message); }
    private static JSONObject object(JSONObject parent, String key) { return parent.optJSONObject(key); }
    private static Object value(JSONObject parent, String key) { return parent.opt(key); }
    private static boolean yes(JSONObject parent, String key) { return Boolean.TRUE.equals(value(parent, key)); }
    private static int number(JSONObject parent, String key) { return ((Number) value(parent, key)).intValue(); }
    private static JSONObject write(Context context, Notification notification, String phase, String reason) throws Exception {
        JSONObject.lastSerialized = null;
        NotificationDiagnostics.write(context, phase, true, notification, reason);
        JSONObject result = JSONObject.lastSerialized;
        check(result != null, "diagnostic survives collection");
        String text = new String(Files.readAllBytes(new File(context.directory, "notification-status.json").toPath()), StandardCharsets.UTF_8);
        check(!text.contains("private-") && !text.contains("https://") && !text.contains("token=secret"), "private inputs and exception details never enter file");
        check(text.length() < 6000, "output is fixed-size metadata");
        check("app_notification_metadata_only".equals(value(result, "evidenceScope")), "file explicitly limits evidence to app metadata");
        return result;
    }
    private static JSONObject xiaomi(JSONObject root, String scope) { return object(object(root, scope), "xiaomi"); }
    private static StatusBarNotification status(Context context, int id, Notification notification) {
        return new StatusBarNotification(context.getPackageName(), context.app.uid, id, null, notification);
    }
    private static JSONObject payload(Object protocol) {
        JSONObject image = new JSONObject().put("pic", XiaomiLiveNotification.TOOL_ICON).put("picDark", XiaomiLiveNotification.TOOL_ICON);
        return new JSONObject().put("param_v2", new JSONObject().put("protocol", protocol)
                .put("aodPic", XiaomiLiveNotification.TOOL_ICON).put("aodTitle", PRIVATE)
                .put("picInfo", image).put("baseInfo", new JSONObject().put("title", PRIVATE).put("content", PRIVATE))
                .put("param_island", new JSONObject().put("smallIslandArea", new JSONObject().put("picInfo", image))));
    }
    private static Notification notification(Context context, String input, JSONObject decoded, Icon icon) {
        JSONObject.fixture(input, decoded);
        Notification result = new Notification();
        result.flags = 34;
        result.small = new Icon(Icon.TYPE_RESOURCE, 7, context.getPackageName());
        result.shortText = PRIVATE;
        result.extras.put(XiaomiLiveNotification.PARAM, input)
                .put(XiaomiLiveNotification.PICS, new Bundle().put(XiaomiLiveNotification.TOOL_ICON, icon)
                        .put("private-arbitrary-picture", new Object() { public String toString() { throw new AssertionError("Unknown picture read"); } }))
                .put("android.title", PRIVATE).put("android.text", PRIVATE)
                .put("private-task", new Object() { public String toString() { throw new AssertionError("Unknown extra read"); } });
        return result;
    }
    private static void onlyKeys(Bundle bundle, String... permitted) {
        HashSet<String> allowed = new HashSet<>(Arrays.asList(permitted));
        for (String key : bundle.readKeys) check(allowed.contains(key), "read only an allowed metadata key");
    }
    public static void main(String[] args) throws Exception {
        Context context = new Context();
        context.directory = Files.createTempDirectory("monitor-oem-diagnostics-").toFile();
        try {
            context.app.flags = 0;
            NotificationDiagnostics.write(context, PRIVATE, true, new Notification(), PRIVATE);
            check(context.directory.list().length == 0 && context.manager.activeCalls == 0, "release builds neither collect nor persist");
            NotificationDiagnostics.write(null, PRIVATE, true, null, PRIVATE);
            check(context.directory.list().length == 0, "missing context is harmless");
            context.app.flags = android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE;
            for (int failure : new int[]{1, 2, 3}) {
                context.appInfoFailure = failure;
                NotificationDiagnostics.write(context, PRIVATE, true, new Notification(), PRIVATE);
                check(context.directory.list().length == 0 && context.manager.activeCalls == 0,
                        "missing or failing app metadata never propagates, collects or writes");
            }
            context.appInfoFailure = 0;

            String input = "{fixture:中文-" + PRIVATE + "}";
            JSONObject decoded = payload(1);
            Notification outgoing = notification(context, input, decoded, new Icon(2, R.drawable.codex, context.getPackageName()));
            outgoing.publicVersion = notification(context, "fixture-public", payload(1), new Icon(2, R.drawable.claude, context.getPackageName()));
            outgoing.publicVersion.visibility = 1;
            outgoing.publicVersion.publicVersion = outgoing; // Unexpected cycles never recurse.
            Notification active = new Notification(); active.flags = 2; active.extras = null;
            StatusBarNotification target = status(context, 15, active);
            StatusBarNotification otherId = status(context, 23, outgoing);
            StatusBarNotification otherPackage = new StatusBarNotification("private-other-app", context.app.uid, 15, null, outgoing);
            StatusBarNotification otherUid = new StatusBarNotification(context.getPackageName(), 2222, 15, null, outgoing);
            StatusBarNotification tagged = new StatusBarNotification(context.getPackageName(), context.app.uid, 15, "private-tag", outgoing);
            context.manager.active = new StatusBarNotification[]{null, otherPackage, otherUid, otherId, tagged, target};
            XiaomiLiveCapabilities.value.protocolVersion = 3;
            XiaomiLiveCapabilities.value.island = XiaomiLiveCapabilities.State.ENABLED;
            XiaomiLiveCapabilities.value.focusPermission = XiaomiLiveCapabilities.State.DISABLED;
            context.app.metaData = new Bundle().put("com.xiaomi.xms.APP_ID", "private-synthetic-app-id");
            JSONObject report = write(context, outgoing, PRIVATE, PRIVATE);
            check("unknown".equals(value(report, "phase")) && "other".equals(value(report, "endReason")), "freeform phase and reason are filtered");
            check(number(report, "schemaVersion") == 2, "schema version present");
            check(yes(report, "activeSnapshotMayLag"), "immediate active snapshot is not a rejection/rendering receipt");
            check(yes(report, "xiaomiAppIdConfigured"), "configured boolean recorded without APP_ID");
            check(number(report, "xiaomiCachedSystemProtocol") == 3 && "enabled".equals(value(report, "xiaomiCachedIsland"))
                    && "disabled".equals(value(report, "xiaomiCachedFocusSwitch")), "independent cached observations remain distinct");
            check(number(report, "activeNotificationCount") == 3 && number(report, "activeTrackingCount") == 1, "count own notifications but match exact tracking slot");
            check(target.reads == 1 && otherId.reads == 0 && otherPackage.reads == 0 && otherUid.reads == 0 && tagged.reads == 0,
                    "never inspect another package, UID, notification ID, or tagged slot");
            JSONObject metadata = xiaomi(report, "outgoing");
            check(yes(metadata, "inspectionSucceeded") && yes(metadata, "paramKeyPresent") && yes(metadata, "paramString"), "submitted Xiaomi payload presence recorded");
            check(number(metadata, "paramUtf8Bytes") == input.getBytes(StandardCharsets.UTF_8).length, "measure UTF-8 bytes rather than character count");
            check(yes(metadata, "paramJsonObject") && yes(metadata, "paramV2") && number(metadata, "payloadProtocol") == 1, "payload protocol distinct from system protocol");
            check(yes(metadata, "aodImageReferencesTool") && yes(metadata, "expandedImagesReferenceTool") && yes(metadata, "smallIslandImageReferencesTool"), "fixed image references identified");
            check(yes(metadata, "picturesBundle") && yes(metadata, "toolImageKeyPresent") && yes(metadata, "toolResourceIcon") && yes(metadata, "toolBundledResourceIcon"), "bundled tool icon metadata present");
            check(!yes(xiaomi(report, "activeTracking"), "paramKeyPresent"), "active snapshot is separate and may lack submitted extras");
            check(yes(object(object(report, "outgoing"), "publicVersion"), "present")
                    && !yes(object(object(report, "activeTracking"), "publicVersion"), "present"), "public version presence recorded without contents");
            check(number(object(object(report, "outgoing"), "publicVersion"), "visibility") == 1, "public visibility recorded");
            onlyKeys(outgoing.extras, Notification.EXTRA_TEMPLATE, XiaomiLiveNotification.PARAM, XiaomiLiveNotification.PICS);
            Bundle pictures = (Bundle) outgoing.extras.get(XiaomiLiveNotification.PICS);
            onlyKeys(pictures, XiaomiLiveNotification.TOOL_ICON);
            JSONObject v2 = decoded.optJSONObject("param_v2");
            check(!v2.readKeys.contains("aodTitle") && !v2.readKeys.contains("baseInfo"), "diagnostics never read OEM text");

            context.app.metaData = null;
            context.manager.active = null;
            report = write(context, null, "stopped", "user_stop");
            check(!yes(report, "activeQuerySucceeded") && !yes(object(report, "outgoing"), "present"), "null active snapshot and outgoing treated as unavailable");
            check(!yes(report, "xiaomiAppIdConfigured"), "absence remains false, not authorized");
            check("user_stop".equals(value(report, "endReason")), "fixed stop reason preserved");
            context.manager.throwActive = true;
            report = write(context, outgoing, "updated", "");
            check(!yes(report, "activeQuerySucceeded") && yes(xiaomi(report, "outgoing"), "paramKeyPresent"), "active IPC failure does not erase outgoing evidence");
            context.manager.throwActive = false; context.manager.active = new StatusBarNotification[0];
            for (Object version : new Object[]{"1", PRIVATE, 1.5, 65, -1, Long.MAX_VALUE, new JSONObject().put("secret", PRIVATE)}) {
                Notification value = notification(context, "fixture-protocol-" + checks, payload(version), null);
                report = write(context, value, "updated", "");
                check(number(xiaomi(report, "outgoing"), "payloadProtocol") == -1, "noninteger or unbounded protocols never enter output");
            }
            Icon uriIcon = new Icon(4, 99, PRIVATE);
            Notification unknownIcon = notification(context, "fixture-uri", payload(1), uriIcon);
            report = write(context, unknownIcon, "updated", "");
            check(!yes(xiaomi(report, "outgoing"), "toolResourceIcon") && !uriIcon.resourceRead, "URI/bitmap icons are never dereferenced");
            unknownIcon = notification(context, "fixture-foreign", payload(1), new Icon(2, R.drawable.codex, "private-foreign"));
            report = write(context, unknownIcon, "updated", "");
            check(yes(xiaomi(report, "outgoing"), "toolResourceIcon") && !yes(xiaomi(report, "outgoing"), "toolBundledResourceIcon"), "foreign resource not misidentified as bundled artwork");

            Notification malformed = new Notification();
            malformed.extras.put(XiaomiLiveNotification.PARAM, "invalid-json-" + PRIVATE).put(XiaomiLiveNotification.PICS, PRIVATE);
            report = write(context, malformed, "updated", "");
            check(!yes(xiaomi(report, "outgoing"), "paramJsonObject") && yes(xiaomi(report, "outgoing"), "inspectionSucceeded"), "malformed JSON records shape failure without source/error text");
            String deep = new String(new char[3000]).replace('\0', '[') + PRIVATE
                    + new String(new char[3000]).replace('\0', ']');
            JSONObject.overflowFixture(deep);
            malformed.extras.put(XiaomiLiveNotification.PARAM, deep);
            report = write(context, malformed, "updated", "");
            check(!yes(xiaomi(report, "outgoing"), "paramJsonObject") && yes(xiaomi(report, "outgoing"), "inspectionSucceeded")
                    && number(xiaomi(report, "outgoing"), "paramUtf8Bytes") == deep.getBytes(StandardCharsets.UTF_8).length,
                    "decoder recursion failure cannot interrupt tracking or serialize its private input/message");
            malformed.extras.put(XiaomiLiveNotification.PARAM, new Object() { public String toString() { throw new AssertionError("Do not stringify payload"); } });
            report = write(context, malformed, "updated", "");
            check(yes(xiaomi(report, "outgoing"), "paramKeyPresent") && !yes(xiaomi(report, "outgoing"), "paramString"), "wrong payload type preserves presence without coercion");
            malformed.extras.broken = true;
            report = write(context, malformed, "updated", "");
            check(!yes(xiaomi(report, "outgoing"), "inspectionSucceeded"), "unreadable Bundle retains a safe incomplete snapshot");
            malformed.extras = new Bundle().put(XiaomiLiveNotification.PARAM, new String(new char[20000]).replace('\0', 'x'));
            report = write(context, malformed, "updated", "");
            check(yes(xiaomi(report, "outgoing"), "paramSizeLimitExceeded") && number(xiaomi(report, "outgoing"), "paramUtf8Bytes") == -1, "oversized input is bounded before encoding/parsing");
            malformed.extras.put(XiaomiLiveNotification.PARAM, new String(new char[8000]).replace('\0', '中'));
            report = write(context, malformed, "updated", "");
            check(yes(xiaomi(report, "outgoing"), "paramSizeLimitExceeded") && number(xiaomi(report, "outgoing"), "paramUtf8Bytes") == 24000, "byte bound also covers multibyte strings");
            XiaomiLiveCapabilities.value = null;
            android.os.Build.VERSION.SDK_INT = 28;
            report = write(context, new Notification(), "started", "");
            check(number(report, "xiaomiCachedSystemProtocol") == -1 && "unknown".equals(value(report, "xiaomiCachedIsland"))
                    && "unknown".equals(value(report, "xiaomiCachedFocusSwitch")), "missing cache is unknown without a provider query");
            check(!yes(report, "systemAllowsPromotion"), "older Android does not claim platform promotion");
            System.out.println("NotificationDiagnosticsTest: " + checks + " checks passed (offline metadata filtering; no OEM rendering or authorization claims)");
        } finally {
            for (File file : context.directory.listFiles()) Files.deleteIfExists(file.toPath());
            Files.deleteIfExists(context.directory.toPath());
        }
    }
}
