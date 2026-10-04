package com.agentmonitor.live;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/** Pure local decoder; callers validate the returned text before any pairing action. */
final class NativeQrDecoder {
    /** Source pixels under the centered square guide after a center-crop preview transform. */
    static int guideEdge(int frameWidth, int frameHeight, int viewWidth, int viewHeight, boolean swapped) {
        if (!validSize(frameWidth, frameHeight) || viewWidth <= 0 || viewHeight <= 0) return 0;
        double orientedWidth = swapped ? frameHeight : frameWidth;
        double orientedHeight = swapped ? frameWidth : frameHeight;
        double scale = Math.max(viewWidth / orientedWidth, viewHeight / orientedHeight);
        int edge = (int) Math.floor(Math.min(viewWidth, viewHeight) * .73 / scale);
        return Math.max(1, Math.min(Math.min(frameWidth, frameHeight), edge));
    }

    static String luminance(byte[] pixels, int width, int height) {
        if (!validSize(width, height) || pixels == null || pixels.length < (long) width * height) return null;
        return read(new PlanarYUVLuminanceSource(pixels, width, height, 0, 0, width, height, false));
    }

    static String rgb(int[] pixels, int width, int height) {
        if (!validSize(width, height) || pixels == null || pixels.length < (long) width * height) return null;
        return read(new RGBLuminanceSource(width, height, pixels));
    }

    private static boolean validSize(int width, int height) {
        return width > 0 && height > 0 && width <= 2048 && height <= 2048 && (long) width * height <= 4_194_304L;
    }

    private static String read(LuminanceSource source) {
        MultiFormatReader reader = new MultiFormatReader();
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        reader.setHints(hints);
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                Result result = reader.decodeWithState(new BinaryBitmap(new HybridBinarizer(attempt == 0 ? source : source.invert())));
                String text = result.getText();
                if (result.getBarcodeFormat() == BarcodeFormat.QR_CODE && text != null && !text.isEmpty() && text.length() <= 2048) return text;
            } catch (ReaderException | IllegalArgumentException ignored) {
            } finally { reader.reset(); }
        }
        return null;
    }
}
