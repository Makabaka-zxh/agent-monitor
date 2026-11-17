package com.agentmonitor.live;

/** Palette safety and third-level navigation, without user preferences or a device. */
public final class NativeAppearancePolicyTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++; if (!condition) throw new AssertionError(message);
    }
    private static double contrast(int a, int b) {
        double x = NativeSystemBarPolicy.luminance(a), y = NativeSystemBarPolicy.luminance(b);
        return (Math.max(x, y) + .05) / (Math.min(x, y) + .05);
    }
    private static int composite(int background, int image, int alpha) {
        int result = 0xff000000;
        for (int shift : new int[]{0, 8, 16}) result |= ((((background >>> shift) & 255) * alpha
                + ((image >>> shift) & 255) * (255 - alpha)) / 255) << shift;
        return result;
    }
    public static void main(String[] args) {
        for (String invalid : new String[]{null, "", "BING", "../bing", "unknown", "bing "})
            check("cloud".equals(NativeAppearancePolicy.mode(invalid)), "invalid saved choice falls back locally");
        for (String mode : NativeAppearancePolicy.modes()) {
            check(mode.equals(NativeAppearancePolicy.mode(mode)), "known choice restores");
            check(!NativeAppearancePolicy.label(mode).isEmpty(), "choice has a display label");
            for (boolean dark : new boolean[]{false, true}) {
                int base = NativeAppearancePolicy.background(mode, dark), header = NativeAppearancePolicy.header(mode, dark);
                check((base >>> 24) == 255 && (header >>> 24) == 255, "system protection is opaque");
                check(NativeSystemBarPolicy.darkIcons(header) != dark, "header icon treatment follows actual surface");
                check(contrast(base, dark ? 0xfff2f5f8 : 0xff202831) >= 7, "main text readable on all presets");
                check(contrast(base, dark ? 0xffa9b5c2 : 0xff5f6b78) >= 4.5, "secondary text readable on all presets");
            }
        }
        for (boolean dark : new boolean[]{false, true}) for (int extreme : new int[]{0xff000000, 0xffffffff}) {
            int surface = composite(NativeAppearancePolicy.background("bing", dark), extreme, dark ? 0xda : 0xde);
            check(contrast(surface, dark ? 0xfff2f5f8 : 0xff202831) >= 7, "wallpaper main text contrast at both extremes");
            check(contrast(surface, dark ? 0xffa9b5c2 : 0xff4d5c6b) >= 4.5, "wallpaper secondary text contrast at both extremes");
        }
        check("settings-background".equals(NativeNavigationPolicy.safePage("settings-background")), "background page restores after appearance recreation");
        check("account".equals(NativeNavigationPolicy.tabForPage("settings-background", "")), "background stays in My branch");
        check("settings-appearance".equals(NativeNavigationPolicy.backTarget("settings-background", "").page), "third level returns to appearance");
        check("应用背景".equals(NativeNavigationPolicy.settingsTitle("settings-background")), "background page has distinct title");
        check(NativeNavigationPolicy.settingsSheetParent("background").equals("settings-notifications"), "existing background execution help unchanged");
        check(NativeNavigationPolicy.settingsSheetParent("bing").isEmpty(), "background selection does not authorize settings sheets");
        System.out.println("NativeAppearancePolicyTest: " + checks + " checks passed");
    }
}
