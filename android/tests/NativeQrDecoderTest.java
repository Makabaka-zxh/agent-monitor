package com.agentmonitor.live;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

public final class NativeQrDecoderTest {
    private static int checks;
    private static void require(boolean condition, String label) {
        checks++;
        if (!condition) throw new AssertionError(label);
    }
    public static void main(String[] args) throws Exception {
        String text = "https://monitor.example.com/#/pairing-confirm/abcdefghijklmnopqrstuvwxyz123456";
        BitMatrix qr = new MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, 480, 480);
        int size = qr.getWidth(), count = size * size;
        byte[] luma = new byte[count], inverted = new byte[count], rotated = new byte[count];
        int[] rgb = new int[count];
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
            byte value = qr.get(x, y) ? 0 : (byte) 255;
            luma[y * size + x] = value;
            inverted[y * size + x] = (byte) (255 - (value & 255));
            rotated[x * size + (size - y - 1)] = value;
            rgb[y * size + x] = qr.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
        }
        require(text.equals(NativeQrDecoder.luminance(luma, size, size)), "Camera luminance pairing QR");
        require(text.equals(NativeQrDecoder.luminance(inverted, size, size)), "Inverted QR");
        require(text.equals(NativeQrDecoder.luminance(rotated, size, size)), "Portrait camera sensor orientation");
        require(text.equals(NativeQrDecoder.rgb(rgb, size, size)), "Gallery QR");
        require(NativeQrDecoder.luminance(new byte[count], size, size) == null, "Blank frame");
        require(NativeQrDecoder.luminance(null, 480, 480) == null, "Missing frame");
        require(NativeQrDecoder.luminance(new byte[4], 480, 480) == null, "Truncated frame");
        require(NativeQrDecoder.luminance(new byte[4], -1, 4) == null, "Negative dimensions");
        require(NativeQrDecoder.luminance(new byte[4], Integer.MAX_VALUE, Integer.MAX_VALUE) == null, "Overflow dimensions");
        require(NativeQrDecoder.rgb(new int[4], 2049, 1) == null, "Decode allocation limit");
        BitMatrix linear = new MultiFormatWriter().encode("123456789012", BarcodeFormat.CODE_128, 480, 120);
        int[] barcode = new int[480 * 120];
        for (int y = 0; y < 120; y++) for (int x = 0; x < 480; x++) barcode[y * 480 + x] = linear.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
        require(NativeQrDecoder.rgb(barcode, 480, 120) == null, "Does not accept unrelated barcode formats");
        require(NativeQrDecoder.guideEdge(1280, 720, 720, 1280, true) == 525, "Portrait guide in native sensor pixels");
        require(NativeQrDecoder.guideEdge(1280, 720, 1280, 720, false) == 525, "Landscape guide in native sensor pixels");
        require(NativeQrDecoder.guideEdge(1280, 720, 720, 720, true) == 525, "Square viewport crops long preview axis");
        require(NativeQrDecoder.guideEdge(1280, 720, 720, 2000, true) == 336, "Tall center-cropped view narrows decoding region");
        require(NativeQrDecoder.guideEdge(1280, 720, 0, 720, true) == 0, "No crop before view layout");
        require(NativeQrDecoder.guideEdge(0, 720, 480, 720, true) == 0, "Reject invalid camera frame");
        System.out.println("NativeQrDecoderTest: " + checks + " checks passed");
    }
}
