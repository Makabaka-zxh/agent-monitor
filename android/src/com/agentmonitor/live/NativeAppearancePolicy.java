package com.agentmonitor.live;

/** Local appearance choices only; never changes system wallpaper or account settings. */
public final class NativeAppearancePolicy {
    private NativeAppearancePolicy() {}
    public static String mode(String value) {
        if ("sand".equals(value) || "mist".equals(value) || "blue".equals(value) || "bing".equals(value)) return value;
        return "cloud";
    }
    public static String[] modes() { return new String[]{"cloud", "sand", "mist", "blue", "bing"}; }
    public static String label(String mode) {
        switch (mode(mode)) {
            case "sand": return "暖砂";
            case "mist": return "雾青";
            case "blue": return "浅海";
            case "bing": return "Bing 每日壁纸";
            default: return "云白";
        }
    }
    public static int background(String mode, boolean dark) {
        switch (mode(mode)) {
            case "sand": return dark ? 0xff1b1917 : 0xfff5f1e9;
            case "mist": return dark ? 0xff141c1a : 0xffedf3ef;
            case "blue": return dark ? 0xff141b24 : 0xffedf2f8;
            default: return dark ? 0xff15191f : 0xfff7f8fa;
        }
    }
    public static int header(String mode, boolean dark) {
        int base = background(mode, dark), target = dark ? 0xff28323d : 0xffffffff;
        return blend(base, target, dark ? 18 : 45);
    }
    private static int blend(int a, int b, int percent) {
        int r = (((a >>> 16) & 255) * (100 - percent) + ((b >>> 16) & 255) * percent) / 100;
        int g = (((a >>> 8) & 255) * (100 - percent) + ((b >>> 8) & 255) * percent) / 100;
        int blue = ((a & 255) * (100 - percent) + (b & 255) * percent) / 100;
        return 0xff000000 | r << 16 | g << 8 | blue;
    }
}
