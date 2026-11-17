package com.agentmonitor.live;

/** Fixed notification content examples. No account, task, clock or device state is read here. */
final class LivePreviewModel {
    private static final long NOW = 1800000000000L;
    private static final long ELAPSED = 125000L;
    private static final String TASK = "整理项目任务（示例）";
    enum Surface { COMPACT, EXPANDED, LOCKSCREEN }

    static final class Preview {
        final String brand, identity, title, body;
        Preview(String brand, String identity, String title, String body) {
            this.brand = brand; this.identity = identity; this.title = title; this.body = body;
        }
    }

    static Preview render(String tool, LivePresentation.Options options, boolean locked) {
        return render(tool, options, locked, true);
    }

    /** Optional missing-data example remains synthetic; no caller-supplied usage can enter a preview. */
    static Preview render(String tool, LivePresentation.Options options, boolean locked, boolean numbersAvailable) {
        return render(tool, options, locked, numbersAvailable, false);
    }

    /** Lock-screen detail is a separate opt-in; the six original display fields remain independent. */
    static Preview render(String tool, LivePresentation.Options options, boolean locked, boolean numbersAvailable, boolean lockscreenDetails) {
        return render(tool, options, locked ? Surface.LOCKSCREEN : Surface.EXPANDED, numbersAvailable, lockscreenDetails);
    }

    /** Collapsed and expanded notifications share the real title and status formatting. */
    static Preview render(String tool, LivePresentation.Options options, Surface surface, boolean numbersAvailable, boolean lockscreenDetails) {
        if (surface == null) throw new IllegalArgumentException("Preview surface is required");
        String brand = "claude".equals(tool) ? "Claude Code" : "Codex";
        // Match the actual notification's public surface, independently of private display choices.
        if (surface == Surface.LOCKSCREEN && !lockscreenDetails) return new Preview(brand, brand, brand,
                LivePresentation.statusLine("执行中", options != null && options.elapsed, ELAPSED));
        if (options == null) throw new IllegalArgumentException("Display options are required");
        String identity = LivePresentation.compactIdentity(brand, "示例电脑", options.computer);
        if (surface == Surface.COMPACT) return new Preview(brand, identity, LivePresentation.title(TASK, brand, options),
                LivePresentation.statusLine("执行中", options.elapsed, ELAPSED));
        LivePresentation.Data data = new LivePresentation.Data();
        data.computer = "示例电脑";
        if (numbersAvailable) {
            data.session = 12345L;
            data.today = 2345678L;
            data.quotas.add(new LivePresentation.Quota("5时", 8, NOW + 30 * 60000L, NOW));
            data.quotas.add(new LivePresentation.Quota("7天", 72, NOW + 24 * 60 * 60000L, NOW));
        }
        return new Preview(brand, identity, LivePresentation.title(TASK, brand, options),
                LivePresentation.expanded(brand, "执行中", data, options, ELAPSED, NOW));
    }

    private LivePreviewModel() { }
}
