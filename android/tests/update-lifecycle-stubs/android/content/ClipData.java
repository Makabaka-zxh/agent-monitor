package android.content;
import android.net.Uri;
public class ClipData {
    public static ClipData newRawUri(String s,Uri u) {
        return new ClipData();
    }
}
