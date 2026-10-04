package com.agentmonitor.live;

import android.content.Context;
import android.os.Build;

public final class LivePreviewModelTest {
    private static int checks;
    private static void check(boolean good, String label) {
        checks++;
        if (!good) throw new AssertionError(label);
    }
    private static LivePresentation.Options selection(int mask) {
        return new LivePresentation.Options((mask & 1) != 0, (mask & 2) != 0, (mask & 4) != 0,
                (mask & 8) != 0, (mask & 16) != 0, (mask & 32) != 0);
    }
    private static int mask(LivePresentation.Options options) {
        return (options.title ? 1 : 0) | (options.computer ? 2 : 0) | (options.elapsed ? 4 : 0)
                | (options.session ? 8 : 0) | (options.today ? 16 : 0) | (options.quota ? 32 : 0);
    }
    private static void rejects(Runnable action, String label) {
        boolean rejected = false;
        try { action.run(); } catch (IllegalArgumentException expected) { rejected = true; }
        check(rejected, label);
    }
    public static void main(String[] args) {
        LivePresentation.Options simple = LivePresentationPreferences.presetOptions(LivePresentationPreferences.Preset.SIMPLE);
        LivePresentation.Options balanced = LivePresentationPreferences.presetOptions(LivePresentationPreferences.Preset.BALANCED);
        LivePresentation.Options rich = LivePresentationPreferences.presetOptions(LivePresentationPreferences.Preset.RICH);
        check(mask(simple) == 0, "simple hides all six optional fields");
        check(mask(balanced) == 5, "balanced retains the established title and elapsed defaults");
        check(mask(rich) == 63, "rich includes all six fields");
        rejects(() -> LivePresentationPreferences.presetOptions(LivePresentationPreferences.Preset.CUSTOM), "custom cannot overwrite user choices as a preset");
        rejects(() -> LivePresentationPreferences.presetOptions(null), "missing preset cannot silently reset choices");

        Context context = new Context();
        Build.MANUFACTURER = "Xiaomi";
        check(mask(LivePresentationPreferences.options(context)) == 5, "existing non-OPPO default remains balanced");
        Build.MANUFACTURER = "OPPO";
        check(mask(LivePresentationPreferences.options(context)) == 63, "existing OPPO default remains rich");
        check(context.preferences.applies == 0 && TrackingService.refreshes == 0, "reading and matching defaults writes nothing and starts no work");
        context.preferences.values.put(LivePresentationPreferences.TITLE, false);
        context.preferences.values.put(LivePresentationPreferences.SESSION_TOKENS, false);
        check(mask(LivePresentationPreferences.options(context)) == 54, "partial legacy OPPO preferences preserve saved overrides and missing-key defaults");
        check(LivePresentationPreferences.matchingPreset(LivePresentationPreferences.options(context)) == LivePresentationPreferences.Preset.CUSTOM,
                "legacy custom choices are identified without conversion");
        context.preferences.values.put("unrelated_existing_preference", true);
        for (int choice = 0; choice < 64; choice++) {
            int applies = context.preferences.applies, edits = context.preferences.edits, refreshes = TrackingService.refreshes;
            LivePresentationPreferences.save(context, selection(choice));
            check(context.preferences.applies == applies + 1 && context.preferences.edits == edits + 1
                    && context.preferences.lastAppliedKeys == 6 && TrackingService.refreshes == refreshes + 1,
                    "batch saves all keys with one apply and refresh: " + choice);
            check("live_presentation_v1".equals(context.requestedFile), "existing storage namespace retained: " + choice);
            Build.MANUFACTURER = "OPPO";
            check(mask(LivePresentationPreferences.options(context)) == choice, "full existing choices survive OPPO defaults: " + choice);
            Build.MANUFACTURER = "Xiaomi";
            check(mask(LivePresentationPreferences.options(context)) == choice, "full existing choices survive manufacturer changes: " + choice);
            LivePresentationPreferences.Preset expected = choice == 0 ? LivePresentationPreferences.Preset.SIMPLE
                    : choice == 5 ? LivePresentationPreferences.Preset.BALANCED
                    : choice == 63 ? LivePresentationPreferences.Preset.RICH : LivePresentationPreferences.Preset.CUSTOM;
            check(LivePresentationPreferences.matchingPreset(LivePresentationPreferences.options(context)) == expected,
                    "only an exact complete selection matches a preset: " + choice);
            for (String tool : new String[]{"codex", "claude"}) {
                LivePreviewModel.Preview locked = LivePreviewModel.render(tool, selection(choice), true);
                check(("claude".equals(tool) ? "Claude Code" : "Codex").equals(locked.title) && locked.body.isEmpty(),
                        "every option combination keeps all task/computer/numbers off the lock screen: " + tool + "/" + choice);
            }
        }
        check(context.preferences.values.get("unrelated_existing_preference"), "saving options retains unrelated existing preferences");
        int applies = context.preferences.applies, refreshes = TrackingService.refreshes;
        LivePresentationPreferences.set(context, LivePresentationPreferences.COMPUTER, false);
        check(mask(LivePresentationPreferences.options(context)) == 61, "single switch still changes exactly its original key");
        check(context.preferences.applies == applies + 1 && TrackingService.refreshes == refreshes + 1, "single switch preserves its apply and refresh behavior");
        applies = context.preferences.applies; refreshes = TrackingService.refreshes;
        LivePresentationPreferences.set(context, "unknown_key", true);
        rejects(() -> LivePresentationPreferences.save(context, null), "null selection fails before editing preferences");
        check(context.preferences.applies == applies && TrackingService.refreshes == refreshes, "invalid inputs do not save or refresh");

        LivePreviewModel.Preview minimal = LivePreviewModel.render("codex", simple, false);
        check("Codex".equals(minimal.title) && "Codex · 执行中".equals(minimal.body), "simple preview includes only tool and activity state");
        LivePreviewModel.Preview medium = LivePreviewModel.render("claude", balanced, false);
        check(medium.title.contains("示例") && medium.body.contains("已跟踪2分") && medium.body.contains("Claude Code"), "balanced preview uses a labelled synthetic task and fixed elapsed time");
        check(!medium.body.contains("示例电脑") && !medium.body.contains("本次") && !medium.body.contains("今日") && !medium.body.contains("余"), "balanced preview omits all disabled details");
        LivePreviewModel.Preview full = LivePreviewModel.render("codex", rich, false);
        check(full.body.contains("示例电脑") && full.body.contains("本次1.2万") && full.body.contains("今日234.5万"), "rich preview contains only the fixed sample computer and numbers");
        check(full.body.contains("5时余8%·30分重置") && full.body.contains("7天余72%·1天重置"), "both synthetic quota windows have repeatable reset times");
        check(full.body.split("\n").length == 5, "rich preview retains the real notification line structure");
        LivePreviewModel.Preview again = LivePreviewModel.render("codex", rich, false);
        check(full.title.equals(again.title) && full.body.equals(again.body), "preview is independent of wall-clock time");
        LivePreviewModel.Preview missing = LivePreviewModel.render("codex", rich, false, false);
        check(missing.title.equals(full.title) && missing.body.contains("示例电脑") && missing.body.contains("已跟踪2分"),
                "missing numeric data retains the selected task and context fields");
        check(!missing.body.contains("本次") && !missing.body.contains("今日") && !missing.body.contains("余")
                        && !missing.body.contains("重置") && !missing.body.contains("0"),
                "enabled numeric fields with no sample data disappear instead of inventing zero values");
        check(LivePreviewModel.render("claude", rich, true, false).body.isEmpty(), "missing-data mode preserves lock-screen privacy");
        check(LivePreviewModel.render("codex", rich, false).body.equals(full.body), "missing-data examples do not mutate later full examples");
        for (int numbers = 0; numbers < 8; numbers++) {
            LivePreviewModel.Preview preview = LivePreviewModel.render("codex", selection(numbers << 3), false);
            check(preview.body.contains("本次") == ((numbers & 1) != 0), "omitted session numbers produce no placeholder: " + numbers);
            check(preview.body.contains("今日") == ((numbers & 2) != 0), "omitted today numbers produce no placeholder: " + numbers);
            check(preview.body.contains("重置") == ((numbers & 4) != 0), "omitted quota numbers produce no placeholder: " + numbers);
            check(!preview.body.contains("本次0") && !preview.body.contains("今日0") && !preview.body.contains("余0%"), "absent numeric choices never become zero: " + numbers);
        }
        LivePreviewModel.Preview invalidTool = LivePreviewModel.render("private arbitrary text", simple, false);
        check("Codex".equals(invalidTool.title) && !invalidTool.body.contains("private"), "tool input cannot inject arbitrary preview text");
        check("Codex".equals(LivePreviewModel.render(null, null, true).title), "lock-screen rendering never needs private options or arbitrary names");
        rejects(() -> LivePreviewModel.render("codex", null, false), "expanded preview requires an explicit selection");
        check(context.preferences.applies == applies && TrackingService.refreshes == refreshes, "rendering previews never saves preferences or refreshes tracking");
        System.out.println("LivePreviewModelTest: " + checks + " checks passed");
    }
}
