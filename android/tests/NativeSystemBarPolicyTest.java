package com.agentmonitor.live;

public final class NativeSystemBarPolicyTest {
    private static int checks;
    private static void check(boolean value) {
        checks++;
        if (!value) throw new AssertionError("system bar policy check " + checks);
    }
    public static void main(String[] args) {
        check(NativeSystemBarPolicy.darkIcons(0xffffffff));
        check(!NativeSystemBarPolicy.darkIcons(0xff000000));
        check(NativeSystemBarPolicy.darkIcons(0xfff4f5f7));
        check(!NativeSystemBarPolicy.darkIcons(0xff17191e));
        check(!NativeSystemBarPolicy.darkIcons(0xff757575));
        check(NativeSystemBarPolicy.darkIcons(0xff767676));
        check(NativeSystemBarPolicy.darkIcons(0xff00ff00));
        check(!NativeSystemBarPolicy.darkIcons(0xff0000ff));
        check(NativeSystemBarPolicy.opaque(0x00123456) == 0xff123456);
        check(NativeSystemBarPolicy.opaque(0x80123456) == 0xff123456);
        check(NativeSystemBarPolicy.opaque(0xff123456) == 0xff123456);
        check(NativeSystemBarPolicy.darkIcons(0x00ffffff) == NativeSystemBarPolicy.darkIcons(0xffffffff));
        check(NativeSystemBarPolicy.luminance(0xff000000) == 0.0);
        check(Math.abs(NativeSystemBarPolicy.luminance(0xffffffff) - 1.0) < 0.0000001);
        // Across the contrast boundary, select the better black/white icon treatment.
        for (int value : new int[] {0, 32, 64, 100, 117, 118, 160, 220, 255}) {
            int color = 0xff000000 | value << 16 | value << 8 | value;
            double luminance = NativeSystemBarPolicy.luminance(color);
            double black = (luminance + 0.05) / 0.05;
            double white = 1.05 / (luminance + 0.05);
            check((NativeSystemBarPolicy.darkIcons(color) ? black : white) >= 4.5);
        }
        System.out.println("NativeSystemBarPolicyTest: " + checks + " checks passed");
    }
}
