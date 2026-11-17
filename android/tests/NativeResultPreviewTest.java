package com.agentmonitor.live;

public final class NativeResultPreviewTest {
    public static void main(String[] args) {
        String shortResult = "已完成。\n保存了两个文件。";
        expect(shortResult.length(), NativeResultPreview.end(shortResult, 600));
        String nearLine = "甲".repeat(540) + "\n" + "乙".repeat(200);
        expect(540, NativeResultPreview.end(nearLine, 600));
        String earlyLine = "摘要\n" + "正文".repeat(400);
        expect(600, NativeResultPreview.end(earlyLine, 600));
        String lines = "• 任务完成\r\n".repeat(13);
        expect("• 任务完成\r\n".length() * 11 + "• 任务完成".length(), NativeResultPreview.end(lines, 600));
        String withEndingNewline = "完成\n".repeat(12);
        expect(withEndingNewline.length(), NativeResultPreview.end(withEndingNewline, 600));
        String withLeadingBlankLines = "\n".repeat(12) + "完成。";
        expect(withLeadingBlankLines.length(), NativeResultPreview.end(withLeadingBlankLines, 600));
        expect(599, NativeResultPreview.end("字".repeat(599) + "😀后续", 600));
        expect(599, NativeResultPreview.end("字".repeat(599) + "e\u0301后续", 600));
        expect(0, NativeResultPreview.end(shortResult, 0));
        System.out.println("NativeResultPreviewTest passed: 9 checks");
    }

    private static void expect(int want, int actual) {
        if (want != actual) throw new AssertionError("Expected " + want + " but got " + actual);
    }
}
