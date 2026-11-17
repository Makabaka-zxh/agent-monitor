package com.agentmonitor.live;

import org.json.JSONArray;
import org.json.JSONObject;
import com.agentmonitor.live.NativeSessionControlPolicy.CatalogState;

/** Synthetic protocol cases only: no accounts, tasks, preferences or live transport. */
public final class NativeSessionControlPolicyTest {
    private static int checks;
    private static final long NOW = 1000;
    private static final String UUID = "00112233-4455-4677-8899-aabbccddeeff";
    private static void check(boolean value, String name) { checks++; if (!value) throw new AssertionError(name); }
    private static JSONObject catalog() {
        return new JSONObject().put("provider", "codex").put("catalog_revision", "catalog-v1").put("valid_until", 1100.5)
            .put("models", new JSONArray().put(new JSONObject().put("id", "source-model").put("label", "Source model").put("efforts", new JSONArray().put("low").put("high"))));
    }
    private static JSONObject selection() { return new JSONObject().put("provider", "codex").put("catalog_revision", "catalog-v1").put("model", "source-model").put("effort", "high").put("apply_at", "next_message"); }
    private static JSONObject approval() { return new JSONObject().put("id", "request-v1").put("revision", "revision-v1").put("turn_id", "turn-v1").put("provider", "claude").put("kind", "approval").put("title", "Synthetic action").put("detail", "Synthetic impact").put("operation", "create a synthetic file").put("expires_at", 1100.5).put("state", "pending"); }
    private static JSONObject question() {
        return approval().put("kind", "question").put("questions", new JSONArray().put(new JSONObject().put("id", "q1").put("text", "Synthetic choice").put("multiple", false).put("allows_text", true)
            .put("options", new JSONArray().put(new JSONObject().put("id", "a").put("label", "A").put("description", "First synthetic option")).put(new JSONObject().put("id", "b").put("label", "B").put("description", "Second synthetic option")))));
    }
    private static JSONObject answer(JSONArray choices, String text) { return new JSONObject().put("question_id", "q1").put("choices", choices).put("text", text); }
    private static JSONObject decision(String action, JSONArray answers) { return new JSONObject().put("task_id", "synthetic-task").put("request_id", "request-v1").put("revision", "revision-v1").put("decision_id", UUID).put("decision", action).put("answers", answers); }
    private static void testModelPickerStates() {
        JSONObject caps = new JSONObject().put("model_settings", true);
        JSONObject response = new JSONObject().put("capabilities", caps);
        check(NativeSessionControlPolicy.catalogState(null, false, false, NOW) == CatalogState.LOADING, "first read has a visible loading state");
        check(NativeSessionControlPolicy.catalogState(null, false, true, NOW) == CatalogState.REQUEST_FAILED, "failed first read is not loading");
        check(NativeSessionControlPolicy.catalogState(response, true, false, NOW) == CatalogState.NOT_PROVIDED, "missing catalog has no picker");
        response.put("catalog", catalog());
        check(NativeSessionControlPolicy.catalogState(response, true, false, NOW) == CatalogState.READY, "fresh valid catalog exposes picker");
        check(NativeSessionControlPolicy.catalogState(response, false, false, NOW) == CatalogState.RECHECKING, "stale snapshot disables cached choices");
        check(NativeSessionControlPolicy.catalogState(response, false, true, NOW) == CatalogState.REQUEST_FAILED, "failed refresh cannot expose cached choices");
        check(NativeSessionControlPolicy.catalogState(response, true, false, 1100) == CatalogState.EXPIRED, "server deadline disables even a fresh snapshot");
        caps.put("model_settings", false);
        check(NativeSessionControlPolicy.catalogState(response, true, false, NOW) == CatalogState.NOT_PROVIDED, "revoked capability disables model picker");
        caps.put("model_settings", true);
        response.put("catalog", catalog().put("models", new JSONArray()));
        check(NativeSessionControlPolicy.catalogState(response, true, false, NOW) == CatalogState.INVALID, "invalid catalog cannot expose choices");
        for (CatalogState state : CatalogState.values()) {
            check(state == CatalogState.READY || !state.message.isEmpty(), "every unavailable state has visible feedback");
            check(!state.message.endsWith("。"), "model settings use compact status copy");
        }
        JSONObject reported = catalog().put("current_model", "source-model");
        check(NativeSessionControlPolicy.effortModel(reported, null, false, NOW) != null, "inheritance can use the catalog's verified current model");
        check(NativeSessionControlPolicy.effortModel(catalog(), null, false, NOW) == null, "missing current model must not assume first option");
        check(NativeSessionControlPolicy.effortModel(reported, null, true, NOW) == null, "corrupt override never falls back to inheritance");
        check(NativeSessionControlPolicy.effortModel(reported, selection().put("catalog_revision", "old"), false, NOW) == null, "stale explicit selection cannot be specialized");
        check(NativeSessionControlPolicy.effortModel(reported, selection(), false, NOW) != null, "valid explicit selection allows source efforts");
        check(NativeSessionControlPolicy.effortModel(reported.put("current_model", "other"), null, false, NOW) == null, "unrecognized current model stays unselected");
        check(NativeSessionControlPolicy.effortModel(catalog().put("current_model", "source-model"), null, false, 1200) == null, "expired model cannot be used for inherited effort");
        check("极高".equals(NativeSessionControlPolicy.effortLabel("xhigh")) && "custom".equals(NativeSessionControlPolicy.effortLabel("custom")), "known effort labels localized, unknown preserved");
        java.util.HashSet<String> messages = new java.util.HashSet<>();
        for (String status : new String[]{"pending", "unavailable", "unsupported", "offline", "archived"}) {
            JSONObject data = new JSONObject().put("catalog_status", status);
            String message = NativeSessionControlPolicy.catalogMessage(CatalogState.NOT_PROVIDED, data);
            check(messages.add(message) && !message.endsWith("。"), "distinct fixed diagnostic label");
            check(CatalogState.REQUEST_FAILED.message.equals(NativeSessionControlPolicy.catalogMessage(CatalogState.REQUEST_FAILED, data)), "stale server reason cannot mask transport failure");
        }
        check(CatalogState.NOT_PROVIDED.message.equals(NativeSessionControlPolicy.catalogMessage(CatalogState.NOT_PROVIDED, null)), "older server compatibility");
        check(CatalogState.NOT_PROVIDED.message.equals(NativeSessionControlPolicy.catalogMessage(CatalogState.NOT_PROVIDED, new JSONObject().put("catalog_status", "unexpected"))), "never display arbitrary source reason");
    }
    private static void testFastMode() {
        JSONObject base = catalog().put("current_model", "source-model").put("current_effort", "low");
        JSONObject model = base.optJSONArray("models").optJSONObject(0);
        JSONObject staged = new JSONObject().put("catalog_revision", "catalog-v1").put("model", "source-model");
        check(NativeSessionControlPolicy.fastModel(base, null, false, NOW) == null, "old catalog does not expose speed");
        model.put("service_tiers", new JSONArray().put("fast"));
        check(NativeSessionControlPolicy.fastModel(base, null, false, NOW) == null, "legacy alias alone is not capability");
        model.put("service_tiers", new JSONArray().put("priority").put("priority"));
        check(NativeSessionControlPolicy.fastModel(base, null, false, NOW) == null, "malformed advertised tier list fails closed");
        model.put("service_tiers", new JSONArray().put("priority"));
        check(NativeSessionControlPolicy.fastModel(base, null, false, NOW) == model, "known Codex current model can expose speed");
        JSONObject inheritedChoice = NativeSessionControlPolicy.stageFast(base, null, false, NOW);
        check(inheritedChoice != null && "source-model".equals(inheritedChoice.optString("model")), "inherited settings can stage Fast without an explicit model");
        model.optJSONArray("efforts").put("ultra");
        base.put("current_effort", "ultra");
        JSONObject inheritedUltra = NativeSessionControlPolicy.stageFast(base, null, false, NOW);
        check(inheritedUltra != null && "ultra".equals(NativeSessionControlPolicy.fastReply(base, null, inheritedUltra, NOW).optString("effort")),
                "preserve a provider-advertised inherited ultra effort");
        base.put("current_effort", "unavailable-effort");
        check(NativeSessionControlPolicy.stageFast(base, null, false, NOW) == null, "do not offer a Fast choice that cannot be saved");
        check(NativeSessionControlPolicy.stageFast(base, selection(), false, NOW) != null, "an explicit valid effort makes Fast selectable even with an unavailable inherited effort");
        base.put("current_effort", "low");
        check(NativeSessionControlPolicy.stageFast(base, null, true, NOW) == null, "do not stage Fast with a damaged selection");
        check(NativeSessionControlPolicy.stageFast(base, null, false, 1100) == null, "expired catalogs cannot stage Fast");
        check(NativeSessionControlPolicy.fastReply(base, null, null, NOW) == null, "never chooses Fast by default");
        check(NativeSessionControlPolicy.fastModel(base, null, true, NOW) == null, "corrupt saved model cannot gain capability");
        check(NativeSessionControlPolicy.fastReply(base, null, staged, NOW + 1000) == null, "expired tier cannot send");
        JSONObject inherited = NativeSessionControlPolicy.fastReply(base, null, staged, NOW);
        check(NativeSessionControlPolicy.fastRequested(inherited) && "low".equals(inherited.optString("effort")), "preserve reported effort with explicit speed");
        JSONObject explicit = selection();
        JSONObject chosen = NativeSessionControlPolicy.fastReply(base, explicit, staged, NOW);
        check(NativeSessionControlPolicy.fastRequested(chosen) && "high".equals(chosen.optString("effort"))
                && explicit.isNull("service_tier"), "do not mutate explicit model and effort selection");
        check(NativeSessionControlPolicy.fastReply(base, explicit.put("effort", "low"), staged, NOW).optString("effort").equals("low"),
                "changing effort on same model preserves queued speed");
        check(NativeSessionControlPolicy.fastReply(base, explicit, staged.put("catalog_revision", "stale"), NOW) == null, "queued speed cannot outlive displayed revision");
        staged.put("catalog_revision", "catalog-v1").put("model", "old");
        check(NativeSessionControlPolicy.fastReply(base, explicit, staged, NOW) == null, "queued speed cannot apply to another model");
        check(NativeSessionControlPolicy.selectionSnapshot(chosen), "same request retry retains original priority tier");
        check(!NativeSessionControlPolicy.selectionSnapshot(selection().put("service_tier", "fast")), "legacy alias refused on outgoing selection");
        check(!NativeSessionControlPolicy.selectionSnapshot(selection().put("service_tier", true)), "boolean tier refused");
        check(!NativeSessionControlPolicy.selectionSnapshot(chosen.put("provider", "claude")), "Claude cannot request Codex speed");
        check(NativeSessionControlPolicy.fastModel(base.put("provider", "claude"), null, false, NOW) == null, "Claude never shows speed");
        base.put("provider", "codex").put("current_model", JSONObject.NULL);
        check(NativeSessionControlPolicy.fastModel(base, null, false, NOW) == null, "unknown inherited model never guessed");
        check(NativeSessionControlPolicy.fastModel(base, explicit, false, NOW) == model, "explicit model works without inherited current model");
        model.put("service_tiers", new JSONArray());
        check(!NativeSessionControlPolicy.selection(selection().put("service_tier", "priority"), base, NOW), "revoked capability refuses queued tier");
        check(NativeSessionControlPolicy.selection(selection().put("service_tier", JSONObject.NULL), base, NOW), "null speed continues to inherit");
    }
    private static void testPlanMode() {
        JSONObject base = catalog().put("current_model", "source-model").put("current_effort", "low");
        JSONObject model = base.optJSONArray("models").optJSONObject(0);
        model.put("service_tiers", new JSONArray().put("priority"));
        JSONObject fast = NativeSessionControlPolicy.stageFast(base, null, false, NOW);
        check(NativeSessionControlPolicy.stagePlan(base, null, false, NOW) == null, "do not show Plan on older servers");
        base.put("collaboration_modes", new JSONArray().put("plan"));
        check(NativeSessionControlPolicy.planReply(base, null, null, NOW) == null, "never enable Plan by default");
        JSONObject staged = NativeSessionControlPolicy.stagePlan(base, null, false, NOW);
        check(staged != null, "advertised Codex Plan may use a verified inherited model");
        JSONObject inherited = NativeSessionControlPolicy.planReply(base, null, staged, NOW);
        check(NativeSessionControlPolicy.planRequested(inherited) && "low".equals(inherited.optString("effort"))
                && inherited.isNull("service_tier"), "Plan preserves inherited effort and leaves speed alone");
        JSONObject selected = selection();
        JSONObject both = NativeSessionControlPolicy.oneTurnSettings(base, selected, fast, staged, NOW);
        check(NativeSessionControlPolicy.planRequested(both) && NativeSessionControlPolicy.fastRequested(both)
                && "high".equals(both.optString("effort")) && selected.isNull("collaboration_mode"), "explicit Plan and Fast coexist without mutating saved defaults");
        check(NativeSessionControlPolicy.selectionSnapshot(both), "retry can keep the exact Plan/Fast snapshot");
        check(!NativeSessionControlPolicy.selectionSnapshot(selection().put("collaboration_mode", "default")), "do not send a guessed default mode");
        check(!NativeSessionControlPolicy.selectionSnapshot(selection().put("collaboration_mode", true)), "reject invalid mode types");
        check(!NativeSessionControlPolicy.selectionSnapshot(both.put("provider", "claude")), "never submit Codex Plan to Claude");
        check(NativeSessionControlPolicy.stagePlan(base.put("current_model", "other"), null, false, NOW) == null, "unknown inherited model cannot stage Plan");
        check(NativeSessionControlPolicy.oneTurnSettings(base, null, fast, staged, NOW) == null, "changed inherited model invalidates pending choices");
        check(NativeSessionControlPolicy.oneTurnSettings(base, selected, fast, staged, NOW + 1000) == null, "expired catalog cannot merge pending choices");
        check(NativeSessionControlPolicy.oneTurnSettings(base, selected, fast, staged.put("catalog_revision", "obsolete"), NOW) == null,
                "never silently drop stale Plan to send a fast reply");
        base.put("collaboration_modes", new JSONArray().put("plan").put("unsupported"));
        check(NativeSessionControlPolicy.stagePlan(base, selected, false, NOW) == null, "malformed mode capability is not advertised");
        check(!NativeSessionControlPolicy.selection(selection().put("collaboration_mode", "plan"), base, NOW), "revoked catalog capability blocks Plan");
    }
    private static void testAntigravityProvider() throws Exception {
        check(NativeSessionControlPolicy.provider("antigravity"), "Antigravity ACP sessions mount native controls");
        check(!NativeSessionControlPolicy.provider("unknown"), "unrecognized tools remain non-interactive");
        JSONObject agCatalog = catalog().put("provider", "antigravity");
        check(NativeSessionControlPolicy.catalog(agCatalog, NOW), "Antigravity catalog validated");
        check(!NativeSessionControlPolicy.advertisesPlan(agCatalog), "Codex collaboration mode stays off for Antigravity");
        check(NativeSessionControlPolicy.fastModel(agCatalog, null, false, NOW) == null, "Codex fast tier stays off for Antigravity");
        JSONObject agSelected = selection().put("provider", "antigravity");
        check(NativeSessionControlPolicy.selection(agSelected, agCatalog, NOW), "Antigravity model and effort selection accepted");
        check(!NativeSessionControlPolicy.selectionSnapshot(agSelected.put("service_tier", "priority")), "Antigravity snapshot rejects service tier");
        JSONObject agApproval = approval().put("provider", "antigravity");
        check(NativeSessionControlPolicy.request(agApproval), "Antigravity single-turn approval validated");
    }
    public static void main(String[] args) throws Exception {
        testAntigravityProvider();
        testFastMode();
        testPlanMode();
        testModelPickerStates();
        JSONObject nullFields = new JSONObject().put("current_model", JSONObject.NULL).put("current_effort", JSONObject.NULL).put("effort", JSONObject.NULL);
        check("null".equals(nullFields.optString("current_model", "")), "stub reproduces Android JSON null stringification");
        check(NativeSessionControlPolicy.nullableText(nullFields, "current_model").isEmpty(), "missing current model never displayed as null");
        check(NativeSessionControlPolicy.nullableText(nullFields, "current_effort").isEmpty(), "missing current effort never displayed as null");
        check(NativeSessionControlPolicy.nullableText(nullFields, "effort").isEmpty(), "default selected effort never displayed as null");
        check(NativeSessionControlPolicy.nullableText(null, "model").isEmpty(), "missing object hidden");
        check(NativeSessionControlPolicy.nullableText(new JSONObject().put("model", 42), "model").isEmpty(), "non-string optional metadata not coerced");
        check("source-model".equals(NativeSessionControlPolicy.nullableText(new JSONObject().put("model", "source-model"), "model")), "real model remains visible");
        check(NativeSessionControlPolicy.catalog(catalog(), NOW), "source catalog including fractional epoch accepted");
        check(!NativeSessionControlPolicy.catalog(null, NOW), "missing catalog hidden");
        check(!NativeSessionControlPolicy.catalog(catalog().put("valid_until", NOW), NOW), "expired catalog blocked");
        check(!NativeSessionControlPolicy.catalog(catalog().put("valid_until", "1100"), NOW), "string timestamp not coerced");
        check(!NativeSessionControlPolicy.catalog(catalog().put("valid_until", Double.NaN), NOW), "NaN deadline rejected");
        check(!NativeSessionControlPolicy.catalog(catalog().put("provider", "unknown"), NOW), "unknown provider rejected");
        check(!NativeSessionControlPolicy.catalog(catalog().put("models", new JSONArray()), NOW), "empty catalog hidden");
        JSONObject duplicate = catalog(); duplicate.optJSONArray("models").put(duplicate.optJSONArray("models").optJSONObject(0));
        check(!NativeSessionControlPolicy.catalog(duplicate, NOW), "ambiguous duplicate model blocked");
        check(NativeSessionControlPolicy.selection(null, null, NOW), "inherit omits selection without a guessed catalog");
        check(NativeSessionControlPolicy.selection(selection(), catalog(), NOW), "source-supported pair accepted");
        check(NativeSessionControlPolicy.selection(selection().put("effort", null), catalog(), NOW), "explicit model default allowed");
        check(NativeSessionControlPolicy.selection(selection().put("effort", JSONObject.NULL), catalog(), NOW), "JSON null effort accepted as model default");
        check(!NativeSessionControlPolicy.selection(selection().put("effort", "invented"), catalog(), NOW), "unsupported effort blocked");
        check(!NativeSessionControlPolicy.selection(selection().put("model", "invented"), catalog(), NOW), "unsupported model blocked");
        check(!NativeSessionControlPolicy.selection(selection().put("catalog_revision", "old"), catalog(), NOW), "stale revision blocked");
        check(!NativeSessionControlPolicy.selection(selection().put("provider", "claude"), catalog(), NOW), "cross-provider catalog blocked");
        check(!NativeSessionControlPolicy.selection(selection().put("apply_at", "now"), catalog(), NOW), "cannot change current operation");
        check(!NativeSessionControlPolicy.selection(selection(), null, NOW), "missing catalog never drops selected settings silently");
        check(NativeSessionControlPolicy.selectionSnapshot(selection()), "frozen retry selection structurally valid");
        check(!NativeSessionControlPolicy.selectionSnapshot(selection().put("model", "")), "corrupt saved selection blocked");
        check(NativeSessionControlPolicy.request(approval()), "complete approval shown");
        check(!NativeSessionControlPolicy.request(approval().put("operation", "")), "missing operation cannot be approved");
        check(!NativeSessionControlPolicy.request(approval().put("operation", "safe\u202Eevil")), "bidi concealed operation blocked");
        check(!NativeSessionControlPolicy.request(approval().put("revision", 1)), "revision type not coerced");
        check(!NativeSessionControlPolicy.request(approval().put("kind", "unknown")), "unknown action cannot become an approval");
        check(!NativeSessionControlPolicy.request(approval().put("state", "running")), "unknown lifecycle state blocked");
        for (String state : new String[]{"forwarded", "acknowledged", "expired", "cancelled"}) {
            JSONObject receipt = approval().put("title", "请求已结束").put("operation", "").put("detail", "").put("questions", new JSONArray()).put("state", state);
            check(NativeSessionControlPolicy.request(receipt), "redacted approval receipt remains readable");
            check(NativeSessionControlPolicy.request(receipt.put("kind", "question")), "redacted question receipt remains readable");
            check(!NativeSessionControlPolicy.stateLabel(receipt, NOW).contains("不完整"), "terminal receipt shows correct state instead of malformed error");
            check(!NativeSessionControlPolicy.decision(receipt, decision("answer", new JSONArray()), "synthetic-task", NOW), "terminal receipt cannot send an action");
        }
        JSONObject capabilities = new JSONObject().put("approvals", true).put("questions", true);
        check(NativeSessionControlPolicy.actionable(approval(), capabilities, NOW), "live capability and request allow explicit action");
        check(NativeSessionControlPolicy.pending(approval(), NOW), "live pending request uses action heading");
        check(!NativeSessionControlPolicy.pending(approval().put("state", "submitted"), NOW), "submitted receipt not labeled waiting for user action");
        check(!NativeSessionControlPolicy.pending(approval().put("state", "forwarded"), NOW), "forwarded receipt uses history heading");
        check(!NativeSessionControlPolicy.pending(approval().put("expires_at", NOW), NOW), "expired pending receipt uses history heading");
        check(!NativeSessionControlPolicy.actionable(approval(), new JSONObject(), NOW), "missing approval capability disables action");
        check(!NativeSessionControlPolicy.actionable(approval().put("expires_at", NOW), capabilities, NOW), "expired action disabled");
        check(!NativeSessionControlPolicy.actionable(approval().put("state", "submitted"), capabilities, NOW), "submitted not resubmitted as new decision");
        check(!NativeSessionControlPolicy.actionable(approval().put("state", "cancelled"), capabilities, NOW), "cancelled action disabled");
        check(NativeSessionControlPolicy.request(question()), "question schema separate from approval");
        check(!NativeSessionControlPolicy.answers(question(), new JSONArray().put(answer(new JSONArray(), null))), "no default selected answer");
        check(NativeSessionControlPolicy.answers(question(), new JSONArray().put(answer(new JSONArray().put("a"), null))), "single source option accepted");
        check(NativeSessionControlPolicy.answers(question(), new JSONArray().put(answer(new JSONArray().put("a"), null).put("text", JSONObject.NULL))), "JSON null custom answer remains absent");
        check(!NativeSessionControlPolicy.answers(question(), new JSONArray().put(answer(new JSONArray().put("invented"), null))), "invented option rejected");
        check(!NativeSessionControlPolicy.answers(question(), new JSONArray().put(answer(new JSONArray().put("a").put("b"), null))), "single question cannot send multiple choices");
        JSONObject multi = question(); multi.optJSONArray("questions").optJSONObject(0).put("multiple", true);
        check(NativeSessionControlPolicy.answers(multi, new JSONArray().put(answer(new JSONArray().put("a").put("b"), null))), "multiple selection honored");
        check(!NativeSessionControlPolicy.answers(multi, new JSONArray().put(answer(new JSONArray().put("a").put("a"), null))), "duplicate answer rejected");
        check(NativeSessionControlPolicy.answers(question(), new JSONArray().put(answer(new JSONArray(), "Explicit synthetic answer"))), "free text only when source permits");
        check(!NativeSessionControlPolicy.answers(question(), new JSONArray().put(answer(new JSONArray().put("a"), "text"))), "text and choices are alternatives");
        check(!NativeSessionControlPolicy.answers(question(), new JSONArray().put(answer(new JSONArray(), " "))), "blank custom text rejected");
        check(!NativeSessionControlPolicy.answers(question(), new JSONArray().put(answer(new JSONArray(), repeat('x', 4001)))), "custom answer shares backend length bound");
        check(NativeSessionControlPolicy.answers(question(), new JSONArray().put(answer(new JSONArray(), repeat('x', 4000)))), "exact backend answer bound accepted");
        JSONObject noText = question(); noText.optJSONArray("questions").optJSONObject(0).put("allows_text", false);
        check(!NativeSessionControlPolicy.answers(noText, new JSONArray().put(answer(new JSONArray(), "text"))), "forbidden text rejected");
        check(!NativeSessionControlPolicy.answers(question(), new JSONArray().put(answer(new JSONArray().put("a"), null).put("question_id", "another"))), "answers bound to exact question order and id");
        check(NativeSessionControlPolicy.decision(approval(), decision("allow_once", new JSONArray()), "synthetic-task", NOW), "explicit one-time approval accepted");
        check(NativeSessionControlPolicy.decision(approval(), decision("deny", new JSONArray()), "synthetic-task", NOW), "explicit rejection accepted");
        check(!NativeSessionControlPolicy.decision(approval(), decision("always_allow", new JSONArray()), "synthetic-task", NOW), "permanent grant absent");
        check(!NativeSessionControlPolicy.decision(approval(), decision("allow_once", new JSONArray()), "other-task", NOW), "decision task isolation");
        check(!NativeSessionControlPolicy.decision(approval(), decision("allow_once", new JSONArray()).put("revision", "new"), "synthetic-task", NOW), "changed revision blocks original decision");
        check(!NativeSessionControlPolicy.decision(approval(), decision("allow_once", new JSONArray()).put("decision_id", "not-uuid"), "synthetic-task", NOW), "decision identity required");
        check(NativeSessionControlPolicy.decision(question(), decision("answer", new JSONArray().put(answer(new JSONArray().put("a"), null))), "synthetic-task", NOW), "question answer distinct decision");
        check(!NativeSessionControlPolicy.decision(question(), decision("allow_once", new JSONArray()), "synthetic-task", NOW), "question never treated as approval");
        String original = NativeSessionControlPolicy.fingerprint(approval());
        check(!original.equals(NativeSessionControlPolicy.fingerprint(approval().put("operation", "different operation"))), "full operation change invalidates displayed approval even same revision");
        check(original.equals(NativeSessionControlPolicy.fingerprint(approval().put("state", "submitted"))), "receipt state does not change payload identity");
        check(!NativeSessionControlPolicy.fingerprint(question()).equals(NativeSessionControlPolicy.fingerprint(noText)), "answer capability change invalidates draft binding");
        check(NativeSessionControlPolicy.stateLabel(approval().put("state", "submitted"), NOW).contains("等待"), "submitted label is pending source confirmation");
        check(NativeSessionControlPolicy.stateLabel(approval().put("state", "acknowledged"), NOW).contains("执行结果请看"), "acknowledgment is not execution success");
        check(NativeSessionControlPolicy.stateLabel(approval().put("state", "forwarded"), NOW).equals("已交给电脑，等待任务结果"), "forwarded transport does not claim source acceptance");
        System.out.println("NativeSessionControlPolicyTest: " + checks + " checks passed");
    }
    private static String repeat(char value, int count) { char[] result = new char[count]; java.util.Arrays.fill(result, value); return new String(result); }
}
