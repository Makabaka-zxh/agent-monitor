package com.agentmonitor.live;

import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Fail-closed wire validation. No provider options or approvals are inferred from output text. */
public final class NativeSessionControlPolicy {
    public static boolean provider(String value) {
        return "codex".equals(value) || "claude".equals(value) || "antigravity".equals(value);
    }
    /** Android's JSON null sentinel may stringify as "null"; it is never display text. */
    public static String nullableText(JSONObject value, String key) {
        Object field = value == null ? null : value.opt(key);
        return field instanceof String ? (String) field : "";
    }
    private static boolean text(Object value, int max, boolean required) {
        if (!(value instanceof String)) return !required && value == null;
        String word = (String) value;
        if (word.length() > max || required && word.trim().isEmpty()) return false;
        for (int i = 0; i < word.length(); i++) {
            char ch = word.charAt(i);
            if (Character.isISOControl(ch) && ch != '\n' && ch != '\r' && ch != '\t' || ch >= '\u202A' && ch <= '\u202E' || ch >= '\u2066' && ch <= '\u2069') return false;
        }
        return true;
    }
    private static boolean id(Object value) {
        if (!text(value, 240, true)) return false;
        String v = (String) value; return v.indexOf('\n') < 0 && v.indexOf('\r') < 0 && v.indexOf('\t') < 0;
    }
    public static long seconds(JSONObject object, String key) {
        Object value = object == null ? null : object.opt(key);
        if (!(value instanceof Number)) return 0;
        double number = ((Number) value).doubleValue();
        return Double.isNaN(number) || Double.isInfinite(number) || number < 0 || number > 32503680000L ? 0 : ((Number) value).longValue();
    }
    public static boolean catalog(JSONObject value, long now) {
        if (value == null || !provider(value.optString("provider")) || !id(value.opt("catalog_revision")) || seconds(value, "valid_until") <= now) return false;
        JSONArray models = value.optJSONArray("models"); if (models == null || models.length() == 0 || models.length() > 256) return false;
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < models.length(); i++) {
            JSONObject model = models.optJSONObject(i);
            if (model == null || !id(model.opt("id")) || model.optString("id").length() > 120 || !seen.add(model.optString("id")) || !text(model.opt("label"), 120, true)) return false;
            JSONArray efforts = model.optJSONArray("efforts"); if (efforts == null || efforts.length() > 16) return false;
            Set<String> levels = new HashSet<>();
            for (int j = 0; j < efforts.length(); j++) if (!id(efforts.opt(j)) || efforts.optString(j).length() > 32 || !levels.add(efforts.optString(j))) return false;
        }
        return true;
    }
    public enum CatalogState {
        LOADING("正在获取这项任务可用的模型…"),
        NOT_PROVIDED("暂时没有可选模型，可重新获取"),
        REQUEST_FAILED("模型列表读取失败，请稍后重试"),
        RECHECKING("正在重新确认这项任务的模型信息…"),
        EXPIRED("模型列表已过期，可重新获取"),
        INVALID("返回的模型列表无法验证，可重新获取"),
        READY("");

        public final String message;
        CatalogState(String message) { this.message = message; }
    }
    public static CatalogState catalogState(JSONObject response, boolean fresh, boolean readFailed, long now) {
        if (readFailed) return CatalogState.REQUEST_FAILED;
        if (response == null) return CatalogState.LOADING;
        JSONObject capabilities = response.optJSONObject("capabilities"), value = response.optJSONObject("catalog");
        long expires = seconds(value, "valid_until");
        if (expires > 0 && expires <= now) return CatalogState.EXPIRED;
        if (!fresh) return CatalogState.RECHECKING;
        if (value == null || capabilities == null || !Boolean.TRUE.equals(capabilities.opt("model_settings"))) return CatalogState.NOT_PROVIDED;
        return catalog(value, now) ? CatalogState.READY : CatalogState.INVALID;
    }
    public static JSONObject model(JSONObject catalog, String id) {
        JSONArray values = catalog == null ? null : catalog.optJSONArray("models");
        if (values != null) for (int i = 0; i < values.length(); i++) { JSONObject value = values.optJSONObject(i); if (value != null && id.equals(value.optString("id"))) return value; }
        return null;
    }
    public static String catalogMessage(CatalogState state, JSONObject response) {
        if (state != CatalogState.NOT_PROVIDED) return state.message;
        switch (nullableText(response, "catalog_status")) {
            case "archived": return "任务已归档，恢复后可选择模型";
            case "offline": return "电脑离线，连接后可重新获取模型";
            case "unsupported": return "电脑端暂不支持读取模型";
            case "pending": return "正在等待电脑返回可选模型";
            case "unavailable": return "电脑未能取得可选模型，可稍后重试";
            default: return state.message;
        }
    }
    /** Inheritance can only be specialized to a model the catalog itself identifies. */
    public static JSONObject effortModel(JSONObject catalog, JSONObject selected, boolean corrupt, long now) {
        if (corrupt || !catalog(catalog, now) || !selection(selected, catalog, now)) return null;
        return model(catalog, selected == null ? nullableText(catalog, "current_model") : selected.optString("model"));
    }
    /** Fast is a capability of the exact Codex model, not a new reasoning effort. */
    public static JSONObject fastModel(JSONObject catalog, JSONObject selected, boolean corrupt, long now) {
        JSONObject model = effortModel(catalog, selected, corrupt, now);
        return advertisesFast(catalog, model) ? model : null;
    }
    private static boolean advertisesFast(JSONObject catalog, JSONObject model) {
        if (!"codex".equals(nullableText(catalog, "provider")) || model == null) return false;
        JSONArray tiers = model.optJSONArray("service_tiers");
        return tiers != null && tiers.length() == 1 && "priority".equals(tiers.opt(0));
    }
    public static boolean fastRequested(JSONObject selected) {
        return "priority".equals(nullableText(selected, "service_tier"));
    }
    public static boolean planRequested(JSONObject selected) {
        return "plan".equals(nullableText(selected, "collaboration_mode"));
    }
    public static boolean advertisesPlan(JSONObject catalog) {
        JSONArray modes = catalog == null ? null : catalog.optJSONArray("collaboration_modes");
        return "codex".equals(nullableText(catalog, "provider")) && modes != null && modes.length() == 1 && "plan".equals(modes.opt(0));
    }
    /** Expose a one-turn option only when the effective model and effort can be sent together. */
    public static JSONObject stageFast(JSONObject catalog, JSONObject selected, boolean corrupt, long now) {
        return stageOneTurn(catalog, selected, corrupt, false, now);
    }
    public static JSONObject stagePlan(JSONObject catalog, JSONObject selected, boolean corrupt, long now) {
        return stageOneTurn(catalog, selected, corrupt, true, now);
    }
    private static JSONObject stageOneTurn(JSONObject catalog, JSONObject selected, boolean corrupt, boolean plan, long now) {
        JSONObject model = plan && advertisesPlan(catalog) ? effortModel(catalog, selected, corrupt, now)
                : plan ? null : fastModel(catalog, selected, corrupt, now);
        if (model == null) return null;
        try {
            JSONObject pending = new JSONObject().put("model", model.optString("id"))
                    .put("catalog_revision", catalog.optString("catalog_revision"));
            return oneTurnReply(catalog, selected, pending, plan, now) == null ? null : pending;
        } catch (Exception invalid) { return null; }
    }
    /** Bind one queued speed choice to the exact catalogue and effective model shown. */
    public static JSONObject fastReply(JSONObject catalog, JSONObject selected, JSONObject pending, long now) {
        return oneTurnReply(catalog, selected, pending, false, now);
    }
    /** Bind one queued Plan choice to the exact catalogue and effective model shown. */
    public static JSONObject planReply(JSONObject catalog, JSONObject selected, JSONObject pending, long now) {
        return oneTurnReply(catalog, selected, pending, true, now);
    }
    private static JSONObject oneTurnReply(JSONObject catalog, JSONObject selected, JSONObject pending, boolean plan, long now) {
        if (pending == null || !id(pending.opt("model")) || !id(pending.opt("catalog_revision"))) return null;
        JSONObject model = plan && advertisesPlan(catalog) ? effortModel(catalog, selected, false, now)
                : plan ? null : fastModel(catalog, selected, false, now);
        if (model == null || !model.optString("id").equals(pending.optString("model"))
                || !catalog.optString("catalog_revision").equals(pending.optString("catalog_revision"))) return null;
        try {
            String effort = selected == null ? nullableText(catalog, "current_effort") : nullableText(selected, "effort");
            JSONObject result = new JSONObject().put("provider", "codex").put("catalog_revision", pending.optString("catalog_revision"))
                    .put("model", pending.optString("model")).put("effort", effort.isEmpty() ? JSONObject.NULL : effort)
                    .put("apply_at", "next_message");
            if (plan) result.put("collaboration_mode", "plan");
            else result.put("service_tier", "priority");
            return selection(result, catalog, now) ? result : null;
        } catch (Exception invalid) { return null; }
    }
    public static JSONObject oneTurnSettings(JSONObject catalog, JSONObject selected, JSONObject fast, JSONObject plan, long now) {
        if (fast == null) return planReply(catalog, selected, plan, now);
        JSONObject speed = fastReply(catalog, selected, fast, now);
        if (speed == null || plan == null) return speed;
        if (planReply(catalog, selected, plan, now) == null) return null;
        try { return speed.put("collaboration_mode", "plan"); } catch (Exception invalid) { return null; }
    }
    public static String effortLabel(String effort) {
        switch (effort) {
            case "none": return "关闭";
            case "minimal": return "最低";
            case "low": return "低";
            case "medium": return "中";
            case "high": return "高";
            case "xhigh": return "极高";
            case "max": return "最高";
            default: return effort;
        }
    }
    public static boolean selection(JSONObject selected, JSONObject catalog, long now) {
        if (selected == null) return true;
        if (!selectionSnapshot(selected) || !catalog(catalog, now) || !catalog.optString("provider").equals(selected.optString("provider")) || !catalog.optString("catalog_revision").equals(selected.optString("catalog_revision"))) return false;
        JSONObject model = model(catalog, selected.optString("model")); if (model == null) return false;
        if (fastRequested(selected) && !advertisesFast(catalog, model)) return false;
        if (planRequested(selected) && !advertisesPlan(catalog)) return false;
        if (selected.isNull("effort")) return true;
        Object effort = selected.opt("effort"); if (!(effort instanceof String)) return false;
        JSONArray options = model.optJSONArray("efforts");
        for (int i = 0; i < options.length(); i++) if (effort.equals(options.optString(i))) return true;
        return false;
    }
    /** Restored retries keep their original catalog revision; freshness is checked server-side. */
    public static boolean selectionSnapshot(JSONObject selected) {
        return selected != null && provider(selected.optString("provider")) && id(selected.opt("catalog_revision")) && id(selected.opt("model"))
                && (selected.isNull("effort") || id(selected.opt("effort")))
                && (selected.isNull("service_tier") || "codex".equals(selected.optString("provider")) && fastRequested(selected))
                && (selected.isNull("collaboration_mode") || "codex".equals(selected.optString("provider")) && planRequested(selected))
                && "next_message".equals(selected.optString("apply_at"));
    }
    public static boolean request(JSONObject value) {
        if (value == null || !id(value.opt("id")) || value.optString("id").length() > 128 || !id(value.opt("revision")) || value.optString("revision").length() > 128 || !id(value.opt("turn_id")) || value.optString("turn_id").length() > 200 || !provider(value.optString("provider")) || seconds(value, "expires_at") == 0) return false;
        String kind = value.optString("kind"), state = value.optString("state");
        if (!("pending".equals(state) || "submitted".equals(state) || "forwarded".equals(state) || "acknowledged".equals(state) || "expired".equals(state) || "cancelled".equals(state))) return false;
        if (!("question".equals(kind) || "approval".equals(kind))) return false;
        if (!text(value.opt("title"), 240, true) || !text(value.opt("detail"), 4000, false)) return false;
        if (terminal(value)) return true; // Resolved receipts deliberately omit operation and question content.
        if ("approval".equals(kind)) return text(value.opt("operation"), 8000, true);
        if (!"question".equals(kind)) return false;
        JSONArray questions = value.optJSONArray("questions"); if (questions == null || questions.length() == 0 || questions.length() > 16) return false;
        Set<String> questionIds = new HashSet<>();
        for (int i = 0; i < questions.length(); i++) {
            JSONObject question = questions.optJSONObject(i);
            if (question == null || !id(question.opt("id")) || question.optString("id").length() > 80 || !questionIds.add(question.optString("id")) || !text(question.opt("text"), 2000, true) || !(question.opt("multiple") instanceof Boolean) || !(question.opt("allows_text") instanceof Boolean)) return false;
            JSONArray options = question.optJSONArray("options"); if (options == null || options.length() > 32 || options.length() == 0 && !question.optBoolean("allows_text")) return false;
            Set<String> optionIds = new HashSet<>();
            for (int j = 0; j < options.length(); j++) {
                JSONObject option = options.optJSONObject(j);
                if (option == null || !id(option.opt("id")) || option.optString("id").length() > 80 || !optionIds.add(option.optString("id")) || !text(option.opt("label"), 240, true) || !text(option.opt("description"), 1000, false)) return false;
            }
        }
        return true;
    }
    public static boolean terminal(JSONObject value) {
        String state = value == null ? "" : value.optString("state");
        return "forwarded".equals(state) || "acknowledged".equals(state) || "expired".equals(state) || "cancelled".equals(state);
    }
    public static boolean actionable(JSONObject request, JSONObject capabilities, long now) {
        return pending(request, now) && capabilities != null && Boolean.TRUE.equals(capabilities.opt("approval".equals(request.optString("kind")) ? "approvals" : "questions"));
    }
    public static boolean pending(JSONObject request, long now) { return request(request) && "pending".equals(request.optString("state")) && seconds(request, "expires_at") > now; }
    public static boolean answers(JSONObject request, JSONArray answers) {
        if (!request(request) || !"question".equals(request.optString("kind"))) return false;
        JSONArray questions = request == null ? null : request.optJSONArray("questions");
        if (questions == null || questions.length() == 0 || answers == null || answers.length() != questions.length()) return false;
        for (int i = 0; i < questions.length(); i++) {
            JSONObject question = questions.optJSONObject(i), answer = answers.optJSONObject(i);
            if (question == null || answer == null || !question.optString("id").equals(answer.optString("question_id"))) return false;
            JSONArray choices = answer.optJSONArray("choices"); if (choices == null || !question.optBoolean("multiple") && choices.length() > 1) return false;
            JSONArray options = question.optJSONArray("options"); Set<String> allowed = new HashSet<>(), seen = new HashSet<>();
            for (int j = 0; j < options.length(); j++) allowed.add(options.optJSONObject(j).optString("id"));
            for (int j = 0; j < choices.length(); j++) if (!(choices.opt(j) instanceof String) || !allowed.contains(choices.optString(j)) || !seen.add(choices.optString(j))) return false;
            boolean hasText = !answer.isNull("text") && answer.opt("text") instanceof String && !answer.optString("text").trim().isEmpty();
            if (!answer.isNull("text") && (!question.optBoolean("allows_text") || !text(answer.opt("text"), 4000, true) || choices.length() > 0)) return false;
            if (choices.length() == 0 && !hasText) return false;
        }
        return true;
    }
    public static boolean decision(JSONObject request, JSONObject payload, String taskId, long now) {
        if (!request(request) || payload == null || !"pending".equals(request.optString("state")) || seconds(request, "expires_at") <= now || !taskId.equals(payload.optString("task_id")) || !request.optString("id").equals(payload.optString("request_id")) || !request.optString("revision").equals(payload.optString("revision")) || !payload.optString("decision_id").matches("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}")) return false;
        String action = payload.optString("decision");
        JSONArray answers = payload.optJSONArray("answers");
        if ("approval".equals(request.optString("kind"))) return ("allow_once".equals(action) || "deny".equals(action)) && answers != null && answers.length() == 0;
        return "answer".equals(action) && answers(request, answers);
    }
    /** All displayed operation/choice fields are bound, not just the source revision. */
    public static String fingerprint(JSONObject request) {
        if (!request(request)) return "";
        StringBuilder value = new StringBuilder();
        for (String key : new String[]{"id", "revision", "turn_id", "provider", "kind", "title", "detail", "operation"}) append(value, request.optString(key));
        append(value, Long.toString(seconds(request, "expires_at")));
        JSONArray questions = "question".equals(request.optString("kind")) ? request.optJSONArray("questions") : null;
        if (questions != null) for (int i = 0; i < questions.length(); i++) {
            JSONObject question = questions.optJSONObject(i); if (question == null) continue;
            for (String key : new String[]{"id", "text"}) append(value, question.optString(key));
            append(value, String.valueOf(question.optBoolean("multiple"))); append(value, String.valueOf(question.optBoolean("allows_text")));
            JSONArray options = question.optJSONArray("options");
            if (options != null) for (int j = 0; j < options.length(); j++) { JSONObject option = options.optJSONObject(j); for (String key : new String[]{"id", "label", "description"}) append(value, option.optString(key)); }
        }
        return value.toString();
    }
    private static void append(StringBuilder value, String part) { value.append(part.length()).append(':').append(part); }
    public static String stateLabel(JSONObject request, long now) {
        if (!request(request)) return "请求信息不完整，请到电脑查看";
        String state = request.optString("state");
        if ("acknowledged".equals(state)) return "电脑已收到；执行结果请看任务状态";
        if ("forwarded".equals(state)) return "已交给电脑，等待任务结果";
        if ("submitted".equals(state)) return "已发送，等待电脑确认";
        if ("cancelled".equals(state)) return "请求已取消";
        if ("expired".equals(state) || seconds(request, "expires_at") <= now) return "请求已过期";
        return "approval".equals(request.optString("kind")) ? "仅批准这一次操作" : "请选择后发送";
    }
    private NativeSessionControlPolicy() { }
}
