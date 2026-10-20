package com.agentmonitor.live;

import org.json.JSONObject;

/** Conservative, plain-text rendering of the server's bounded error summary. */
public final class NativeErrorPresentation {
    public static final class Detail {
        public final boolean visible, hasAnalysis;
        public final String summary, location, suggestion;
        private Detail(boolean visible, boolean hasAnalysis, String summary, String location, String suggestion) {
            this.visible = visible; this.hasAnalysis = hasAnalysis; this.summary = summary;
            this.location = location; this.suggestion = suggestion;
        }
    }

    public static Detail from(JSONObject task) {
        return from(task, true);
    }
    public static Detail from(JSONObject task, boolean syncOutput) {
        return from(task, syncOutput, task == null ? "" : task.optString("status"));
    }
    public static Detail from(JSONObject task, boolean syncOutput, String currentStatus) {
        boolean error = task != null && "error".equals(currentStatus) && "error".equals(task.optString("status"));
        JSONObject analysis = error && syncOutput ? task.optJSONObject("error_analysis") : null;
        if (analysis == null) return new Detail(error, false, "当前只有失败状态，尚无可分析的错误详情", "", "");
        String summary = clean(analysis.optString("summary", ""), 600);
        if (summary.isEmpty()) summary = "已收到失败信息，尚不能确认具体原因。";
        String suggestion = clean(analysis.optString("suggestion", ""), 600);
        if (suggestion.isEmpty()) suggestion = "请在电脑端查看本次执行的完整错误信息。";
        return new Detail(true, true, summary, location(analysis.optJSONObject("location")), suggestion);
    }

    private static String location(JSONObject location) {
        if (location == null) return "已收到的输出未定位到具体文件或步骤。";
        String file = location.optString("file", "").replace('\\', '/');
        int slash = file.lastIndexOf('/'); if (slash >= 0) file = file.substring(slash + 1);
        file = clean(file, 240);
        if (".".equals(file) || "..".equals(file)) file = "";
        String step = clean(location.optString("step", ""), 80);
        StringBuilder result = new StringBuilder();
        if (!file.isEmpty()) {
            result.append(file); int line = location.optInt("line", 0), column = location.optInt("column", 0);
            if (line > 0 && line <= 10000000) {
                result.append(" · 第 ").append(line).append(" 行");
                if (column > 0 && column <= 1000000) result.append("，第 ").append(column).append(" 列");
            }
        }
        if (!step.isEmpty()) { if (result.length() > 0) result.append("\n"); result.append("步骤：").append(step); }
        return result.length() == 0 ? "已收到的输出未定位到具体文件或步骤。" : result.toString();
    }

    private static String clean(String value, int limit) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < value.length() && result.length() < limit; i++) {
            char ch = value.charAt(i); if (ch >= 32 && ch != 127 && ch != '\u202E' && ch != '\u202D') result.append(ch);
        }
        return result.toString().trim();
    }
    private NativeErrorPresentation() { }
}
