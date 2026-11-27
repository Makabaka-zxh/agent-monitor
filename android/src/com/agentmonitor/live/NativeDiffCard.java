package com.agentmonitor.live;

import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.HashSet;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Portrait-friendly collapsible Git Diff summary card mounted directly beneath Final Result.
 * Renders file-level +add/-del stats and horizontally scrollable syntax-tinted hunks.
 */
public final class NativeDiffCard {
    private final NativeUi ui;
    private final LinearLayout container;
    private final Set<String> expandedPaths = new HashSet<>();
    private String lastSignature = "";
    private int lastFileCount = 0;
    private boolean initializedDefaultExpansion = false;

    public NativeDiffCard(NativeUi ui, LinearLayout container) {
        this.ui = ui;
        this.container = container;
    }

    public int fileCount() {
        return lastFileCount;
    }

    public void clear() {
        lastSignature = "";
        lastFileCount = 0;
        initializedDefaultExpansion = false;
        expandedPaths.clear();
        container.removeAllViews();
        container.setVisibility(View.GONE);
    }

    public void bind(JSONObject diff) {
        JSONArray files = diff == null ? null : diff.optJSONArray("files");
        if (diff == null || files == null || files.length() == 0) {
            clear();
            return;
        }
        String sig = diff.toString();
        if (sig.equals(lastSignature)) return;
        lastSignature = sig;
        lastFileCount = files.length();

        int greenInk = ui.dark ? Color.parseColor("#5AD48A") : Color.parseColor("#1D7A46");
        int redInk = ui.dark ? Color.parseColor("#F47B7B") : Color.parseColor("#C23B3B");
        int addBg = ui.dark ? Color.parseColor("#242E8B57") : Color.parseColor("#182E8B57");
        int delBg = ui.dark ? Color.parseColor("#26C94A4A") : Color.parseColor("#16C94A4A");
        int hunkBg = ui.dark ? Color.parseColor("#22506B8D") : Color.parseColor("#14506B8D");

        if (!initializedDefaultExpansion) {
            initializedDefaultExpansion = true;
            if (files.length() == 1) {
                JSONObject first = files.optJSONObject(0);
                if (first != null && !first.optBoolean("binary")) {
                    expandedPaths.add(first.optString("path", ""));
                }
            }
        }

        container.removeAllViews();
        LinearLayout card = ui.card();
        card.setBackground(ui.bordered(ui.surface, 20));
        card.setPadding(ui.dp(18), ui.dp(16), ui.dp(18), ui.dp(16));

        // Header row: Code icon + "代码变更" + source pill + +N / -M summary
        LinearLayout header = ui.row();
        header.setGravity(Gravity.CENTER_VERTICAL);

        NativeUi.Icon codeIcon = (NativeUi.Icon) ui.icon("code", 18);
        codeIcon.setInk(ui.accent);
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(ui.dp(18), ui.dp(18));
        iconLp.setMarginEnd(ui.dp(8));
        header.addView(codeIcon, iconLp);

        TextView heading = ui.heading("代码变更", 17);
        heading.setSingleLine(true);
        header.addView(heading, new LinearLayout.LayoutParams(-2, -2));

        String source = diff.optString("source", "working_tree");
        String sourceLabel = "last_commit".equals(source) ? "本轮提交" : "未提交";
        TextView sourcePill = ui.text(sourceLabel, 11, true);
        sourcePill.setSingleLine(true);
        sourcePill.setTextColor(ui.muted);
        sourcePill.setBackground(ui.rounded(ui.soft, 8));
        sourcePill.setPadding(ui.dp(7), ui.dp(2.5f), ui.dp(7), ui.dp(2.5f));
        LinearLayout.LayoutParams spLp = new LinearLayout.LayoutParams(-2, -2);
        spLp.setMarginStart(ui.dp(8));
        header.addView(sourcePill, spLp);

        header.addView(new View(ui.activity), new LinearLayout.LayoutParams(0, 1, 1));

        int totalAdd = Math.max(0, diff.optInt("additions", 0));
        int totalDel = Math.max(0, diff.optInt("deletions", 0));
        int totalFiles = Math.max(files.length(), diff.optInt("total_files", files.length()));

        TextView countLabel = ui.text(totalFiles + " 文件", 12, false);
        countLabel.setSingleLine(true);
        countLabel.setTextColor(ui.muted);
        LinearLayout.LayoutParams countLp = new LinearLayout.LayoutParams(-2, -2);
        countLp.setMarginStart(ui.dp(8));
        header.addView(countLabel, countLp);

        if (totalAdd > 0 || totalDel > 0) {
            TextView addView = ui.text("+" + totalAdd, 12.5f, true);
            addView.setSingleLine(true);
            addView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            addView.setTextColor(greenInk);
            LinearLayout.LayoutParams aLp = new LinearLayout.LayoutParams(-2, -2);
            aLp.setMarginStart(ui.dp(8));
            header.addView(addView, aLp);

            TextView delView = ui.text("-" + totalDel, 12.5f, true);
            delView.setSingleLine(true);
            delView.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            delView.setTextColor(redInk);
            LinearLayout.LayoutParams dLp = new LinearLayout.LayoutParams(-2, -2);
            dLp.setMarginStart(ui.dp(5));
            header.addView(delView, dLp);
        }
        card.addView(header, wrap());

        String commitTitle = diff.isNull("commit_title") ? "" : diff.optString("commit_title", "").trim();
        if ("null".equalsIgnoreCase(commitTitle)) commitTitle = "";
        if (!commitTitle.isEmpty()) {
            TextView commitLine = ui.text("提交：" + commitTitle, 12, false);
            commitLine.setTextColor(ui.muted);
            commitLine.setSingleLine(true);
            commitLine.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams cLp = wrap();
            cLp.topMargin = ui.dp(6);
            card.addView(commitLine, cLp);
        }

        LinearLayout filesBox = ui.column();
        LinearLayout.LayoutParams fbLp = wrap();
        fbLp.topMargin = ui.dp(12);

        for (int i = 0; i < files.length(); i++) {
            JSONObject file = files.optJSONObject(i);
            if (file == null) continue;
            final String path = file.optString("path", "").trim();
            if (path.isEmpty()) continue;
            String status = file.optString("status", "M").trim();
            int fAdd = Math.max(0, file.optInt("additions", 0));
            int fDel = Math.max(0, file.optInt("deletions", 0));
            boolean binary = file.optBoolean("binary");
            JSONArray hunks = file.optJSONArray("hunks");
            boolean hasHunks = !binary && hunks != null && hunks.length() > 0;
            boolean initiallyOpen = hasHunks && expandedPaths.contains(path);

            LinearLayout fileGroup = ui.column();
            fileGroup.setBackground(ui.bordered(ui.bg, 14));
            fileGroup.setClipToOutline(true);
            LinearLayout.LayoutParams fgLp = wrap();
            if (i > 0) fgLp.topMargin = ui.dp(8);

            LinearLayout fileHeader = ui.row();
            fileHeader.setGravity(Gravity.CENTER_VERTICAL);
            fileHeader.setPaddingRelative(ui.dp(11), ui.dp(10), ui.dp(10), ui.dp(10));
            if (hasHunks) {
                fileHeader.setBackground(ui.ripple(Color.TRANSPARENT, 14));
                fileHeader.setClickable(true);
                fileHeader.setFocusable(true);
                fileHeader.setAccessibilityDelegate(NativeUi.buttonAccessibility());
            }

            TextView badge = ui.text(statusBadgeLabel(status), 10.5f, true);
            badge.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            badge.setTextColor(statusBadgeColor(status, greenInk, redInk));
            badge.setBackground(ui.rounded(ui.soft, 6));
            badge.setPadding(ui.dp(5.5f), ui.dp(2), ui.dp(5.5f), ui.dp(2));
            LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(-2, -2);
            bLp.setMarginEnd(ui.dp(8));
            fileHeader.addView(badge, bLp);

            TextView pathView = ui.text(path, 12.5f, true);
            pathView.setTypeface(Typeface.MONOSPACE, Typeface.NORMAL);
            pathView.setSingleLine(true);
            pathView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            pathView.setTextColor(ui.text);
            fileHeader.addView(pathView, new LinearLayout.LayoutParams(0, -2, 1));

            if (binary) {
                TextView binTag = ui.text("二进制", 11, false);
                binTag.setTextColor(ui.muted);
                LinearLayout.LayoutParams btLp = new LinearLayout.LayoutParams(-2, -2);
                btLp.setMarginStart(ui.dp(6));
                fileHeader.addView(binTag, btLp);
            } else {
                if (fAdd > 0) {
                    TextView fa = ui.text("+" + fAdd, 11.5f, true);
                    fa.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                    fa.setTextColor(greenInk);
                    LinearLayout.LayoutParams faLp = new LinearLayout.LayoutParams(-2, -2);
                    faLp.setMarginStart(ui.dp(6));
                    fileHeader.addView(fa, faLp);
                }
                if (fDel > 0) {
                    TextView fd = ui.text("-" + fDel, 11.5f, true);
                    fd.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
                    fd.setTextColor(redInk);
                    LinearLayout.LayoutParams fdLp = new LinearLayout.LayoutParams(-2, -2);
                    fdLp.setMarginStart(ui.dp(4));
                    fileHeader.addView(fd, fdLp);
                }
            }

            final NativeUi.Icon chevron = hasHunks
                    ? (NativeUi.Icon) ui.icon(initiallyOpen ? "chevron-up" : "chevron-down", 16)
                    : null;
            if (chevron != null) {
                LinearLayout.LayoutParams chLp = new LinearLayout.LayoutParams(ui.dp(18), ui.dp(18));
                chLp.setMarginStart(ui.dp(6));
                fileHeader.addView(chevron, chLp);
            }
            fileGroup.addView(fileHeader, wrap());

            if (hasHunks) {
                final LinearLayout body = buildHunksView(hunks, file.optBoolean("truncated"), greenInk, redInk, addBg, delBg, hunkBg);
                body.setVisibility(initiallyOpen ? View.VISIBLE : View.GONE);
                fileGroup.addView(body, wrap());
                fileHeader.setOnClickListener(v -> {
                    boolean nextOpen = body.getVisibility() != View.VISIBLE;
                    if (nextOpen) expandedPaths.add(path); else expandedPaths.remove(path);
                    body.setVisibility(nextOpen ? View.VISIBLE : View.GONE);
                    chevron.setName(nextOpen ? "chevron-up" : "chevron-down");
                    if (nextOpen) ui.reveal(body);
                });
            }
            filesBox.addView(fileGroup, fgLp);
        }
        card.addView(filesBox, fbLp);

        if (diff.optBoolean("truncated") || totalFiles > files.length()) {
            TextView note = ui.text(
                    totalFiles > files.length()
                            ? "已展示前 " + files.length() + " / " + totalFiles + " 个文件的精简差异，完整变更请在电脑查看"
                            : "大文件差异已按竖屏阅读自动截断，完整 Diff 请在电脑查看",
                    11.5f, false);
            note.setTextColor(ui.muted);
            LinearLayout.LayoutParams nLp = wrap();
            nLp.topMargin = ui.dp(10);
            card.addView(note, nLp);
        }

        container.addView(card, wrap());
        container.setVisibility(View.VISIBLE);
    }

    private LinearLayout buildHunksView(JSONArray hunks, boolean fileTruncated,
                                        int greenInk, int redInk, int addBg, int delBg, int hunkBg) {
        LinearLayout wrapper = ui.column();
        View topRule = new View(ui.activity);
        topRule.setBackgroundColor(ui.line);
        wrapper.addView(topRule, new LinearLayout.LayoutParams(-1, Math.max(1, ui.dp(0.6f))));

        HorizontalScrollView hScroll = new HorizontalScrollView(ui.activity);
        hScroll.setHorizontalScrollBarEnabled(true);
        hScroll.setFillViewport(true);

        LinearLayout codeTable = ui.column();
        codeTable.setPadding(0, ui.dp(4), 0, ui.dp(6));

        for (int h = 0; h < hunks.length(); h++) {
            JSONObject hunk = hunks.optJSONObject(h);
            if (hunk == null) continue;
            String hdr = hunk.optString("header", "").trim();
            if (!hdr.isEmpty()) {
                TextView hdrLine = new TextView(ui.activity);
                hdrLine.setText(hdr);
                hdrLine.setTypeface(Typeface.MONOSPACE);
                hdrLine.setTextSize(11);
                hdrLine.setTextColor(ui.accent);
                hdrLine.setBackgroundColor(hunkBg);
                hdrLine.setPadding(ui.dp(10), ui.dp(3), ui.dp(12), ui.dp(3));
                hdrLine.setSingleLine(true);
                codeTable.addView(hdrLine, wrap());
            }
            JSONArray lines = hunk.optJSONArray("lines");
            if (lines == null) continue;
            for (int l = 0; l < lines.length(); l++) {
                JSONObject line = lines.optJSONObject(l);
                if (line == null) continue;
                String kind = line.optString("kind", "context");
                String text = line.optString("text", "");
                int lineNo = "del".equals(kind)
                        ? line.optInt("old_line", 0)
                        : line.optInt("new_line", line.optInt("old_line", 0));
                String numCol = lineNo > 0 ? String.format(java.util.Locale.ROOT, "%4d ", lineNo) : "     ";
                char prefix = "add".equals(kind) ? '+' : ("del".equals(kind) ? '-' : ' ');

                TextView row = new TextView(ui.activity);
                row.setTypeface(Typeface.MONOSPACE);
                row.setTextSize(11.5f);
                row.setSingleLine(true);
                row.setText(numCol + prefix + " " + text);
                row.setPadding(ui.dp(8), ui.dp(2), ui.dp(14), ui.dp(2));
                if ("add".equals(kind)) {
                    row.setTextColor(greenInk);
                    row.setBackgroundColor(addBg);
                } else if ("del".equals(kind)) {
                    row.setTextColor(redInk);
                    row.setBackgroundColor(delBg);
                } else {
                    row.setTextColor(ui.muted);
                }
                codeTable.addView(row, wrap());
            }
        }
        if (fileTruncated) {
            TextView more = ui.text("  … 其余行已折叠", 11, false);
            more.setTextColor(ui.muted);
            more.setPadding(ui.dp(8), ui.dp(4), ui.dp(12), ui.dp(2));
            codeTable.addView(more, wrap());
        }

        hScroll.addView(codeTable, new FrameLayout.LayoutParams(-2, -2));
        wrapper.addView(hScroll, wrap());
        return wrapper;
    }

    private String statusBadgeLabel(String status) {
        if (status == null || status.isEmpty()) return "M";
        char c = Character.toUpperCase(status.charAt(0));
        switch (c) {
            case 'A':
            case 'U':
            case '?': return "A";
            case 'D': return "D";
            case 'R': return "R";
            default: return "M";
        }
    }

    private int statusBadgeColor(String status, int greenInk, int redInk) {
        String s = statusBadgeLabel(status);
        if ("A".equals(s)) return greenInk;
        if ("D".equals(s)) return redInk;
        return ui.accent;
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(-1, -2);
    }
}
