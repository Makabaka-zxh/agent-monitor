package com.agentmonitor.live;

/** Fixed notification content examples. No account, task, clock or device state is read here. */
final class LivePreviewModel {
    private static final long NOW = 1800000000000L;
    private static final long ELAPSED = 125000L;
    private static final String TASK = "整理项目任务（示例）";

    static final class Preview {
        final String title, body;
        Preview(String title, String body) { this.title = title; this.body = body; }
    }

    static Preview render(String tool, LivePresentation.Options options, boolean locked) {
        return render(tool, options, locked, true);
    }

    /** Optional missing-data example remains synthetic; no caller-supplied usage can enter a preview. */
    static Preview render(String tool, LivePresentation.Options options, boolean locked, boolean numbersAvailable) {
        String brand = "claude".equals(tool) ? "Claude Code" : "Codex";
        // Match the actual notification's public surface, independently of private display choices.
        if (locked) return new Preview(brand, "");
        if (options == null) throw new IllegalArgumentException("Display options are required");
        LivePresentation.Data data = new LivePresentation.Data();
        data.computer = "示例电脑";
        if (numbersAvailable) {
            data.session = 12345L;
            data.today = 2345678L;
            data.quotas.add(new LivePresentation.Quota("5时", 8, NOW + 30 * 60000L, NOW));
            data.quotas.add(new LivePresentation.Quota("7天", 72, NOW + 24 * 60 * 60000L, NOW));
        }
        return new Preview(LivePresentation.title(TASK, brand, options),
                LivePresentation.expanded(brand, "执行中", data, options, ELAPSED, NOW));
    }

    private LivePreviewModel() { }
}
