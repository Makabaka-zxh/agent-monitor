package com.agentmonitor.live;

/** Synthetic public metadata only; no network, app configuration, or real image files. */
public final class NativeBingWallpaperPolicyTest {
    private static int checks;
    private static final String ID = "OHR.PublicExample_ZH-CN1234567890_1920x1080.jpg";
    private static final String IMAGE = "https://www.bing.com/th?id=" + ID;
    private static void check(boolean value) {
        checks++;
        if (!value) throw new AssertionError("wallpaper policy check " + checks);
    }
    private static void rejected(String value) {
        try { NativeBingWallpaperPolicy.imageUrl(value); check(false); }
        catch (IllegalArgumentException expected) { check(true); }
    }
    private static void dimensionsRejected(int width, int height) {
        try { NativeBingWallpaperPolicy.sampleSize(width, height); check(false); }
        catch (IllegalArgumentException expected) { check(true); }
    }

    public static void main(String[] ignored) {
        check(NativeBingWallpaperPolicy.METADATA_URL.equals("https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=1&mkt=zh-CN"));
        check(NativeBingWallpaperPolicy.wallpaperAllowed(Boolean.TRUE));
        for (Object value : new Object[] {null, Boolean.FALSE, "true", 1, new Object()})
            check(!NativeBingWallpaperPolicy.wallpaperAllowed(value));
        check(IMAGE.equals(NativeBingWallpaperPolicy.imageUrl("/th?id=" + ID)));
        check(IMAGE.equals(NativeBingWallpaperPolicy.imageUrl(IMAGE)));
        check(IMAGE.equals(NativeBingWallpaperPolicy.imageUrl("/th?rf=LaDigue_1920x1080.jpg&id=" + ID + "&pid=hp")));
        for (String value : new String[] {null, "", "/th", "/th?pid=hp", "//www.bing.com/th?id=" + ID,
                "http://www.bing.com/th?id=" + ID, "https://bing.com/th?id=" + ID,
                "https://www.bing.com.example/th?id=" + ID, "https://www.bing.com@evil.example/th?id=" + ID,
                "https://user@www.bing.com/th?id=" + ID, "https://www.bing.com:443/th?id=" + ID,
                "https://www.bing.com/th?id=" + ID + "#image", "/th/../th?id=" + ID,
                "/%74h?id=" + ID, "/th?id=" + ID + "&id=" + ID,
                "/th?id=" + ID + "&token=secret", "/th?id=" + ID + "&pid=hp&pid=hp",
                "/th?id=" + ID + "&", "/th?id=%4fHR.Public_1920x1080.jpg",
                "/th?id=OHR.Public_1920x1080.png", "/th?id=OHR.Public_UHD.jpg",
                "/th?id=OHR.../escape_1920x1080.jpg", "/th?id=" + ID + "&rf=x%0d%0aHeader"}) rejected(value);
        check("https://www.bing.com/search?q=public%20image".equals(NativeBingWallpaperPolicy.publicLink("https://www.bing.com/search?q=public%20image")));
        for (String value : new String[] {null, "javascript:alert(1)", "http://www.bing.com/search?q=public",
                "https://evil.example/", "https://www.bing.com@evil.example/search", "https://user@www.bing.com/",
                "https://www.bing.com:443/", "https://www.bing.com/redirect?url=elsewhere"})
            check("https://www.bing.com/".equals(NativeBingWallpaperPolicy.publicLink(value)));
        check(NativeBingWallpaperPolicy.validDate("20261110"));
        check(NativeBingWallpaperPolicy.validDate("20000229"));
        for (String value : new String[] {null, "", "20260229", "20261301", "20261131", "20260000", "2026011", "2026-11-10", "19991231"})
            check(!NativeBingWallpaperPolicy.validDate(value));
        check(!NativeBingWallpaperPolicy.shouldFetch("20261110", "20261110", true, 0, 1000));
        check(NativeBingWallpaperPolicy.shouldFetch("20261110", "20261110", false, 0, 1000));
        check(NativeBingWallpaperPolicy.shouldFetch("20261110", "20261109", true, 0, 1000));
        check(!NativeBingWallpaperPolicy.shouldFetch("20261110", "20261109", true, 100, 101));
        check(!NativeBingWallpaperPolicy.shouldFetch("20261110", "", false, 100, 900099));
        check(NativeBingWallpaperPolicy.shouldFetch("20261110", "", false, 100, 900100));
        check(NativeBingWallpaperPolicy.shouldFetch("20261110", "", false, 100, 99));
        check(!NativeBingWallpaperPolicy.shouldFetch("bad", "", false, 0, 1000));
        check(!NativeBingWallpaperPolicy.shouldFetch("20261110", "", false, 0, -1));
        check(NativeBingWallpaperPolicy.sampleSize(1920, 1080) == 1);
        check(NativeBingWallpaperPolicy.sampleSize(3840, 2160) == 2);
        check(NativeBingWallpaperPolicy.sampleSize(8192, 4096) == 4);
        check(NativeBingWallpaperPolicy.sampleSize(1025, 2049) == 2);
        for (int[] pair : new int[][] {{0, 1}, {-1, 1080}, {8193, 1}, {8192, 8192}, {Integer.MAX_VALUE, Integer.MAX_VALUE}})
            dimensionsRejected(pair[0], pair[1]);
        check(NativeBingWallpaperPolicy.decodedSizeAllowed(2048, 1024));
        check(!NativeBingWallpaperPolicy.decodedSizeAllowed(2048, 2048));
        check(!NativeBingWallpaperPolicy.decodedSizeAllowed(2049, 1));
        check(!NativeBingWallpaperPolicy.decodedSizeAllowed(0, 1));
        check("public caption".equals(NativeBingWallpaperPolicy.publicText(" public\ncaption ", 50)));
        check("public".equals(NativeBingWallpaperPolicy.publicText("\u202epublic\u2069", 50)));
        check("".equals(NativeBingWallpaperPolicy.publicText("long", 3)));
        check("".equals(NativeBingWallpaperPolicy.publicText(Boolean.TRUE, 50)));
        System.out.println("NativeBingWallpaperPolicyTest: " + checks + " checks passed");
    }
}
