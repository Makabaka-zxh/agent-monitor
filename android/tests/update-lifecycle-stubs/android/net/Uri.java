package android.net;
public class Uri {
    public final String value;
    private Uri(String v) {
        value=v;
    }
    public static Uri parse(String s) {
        return new Uri(s);
    }
}
