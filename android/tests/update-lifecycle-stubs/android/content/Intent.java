package android.content;
import android.net.Uri;
public class Intent {
    public static final String ACTION_VIEW="view",CATEGORY_BROWSABLE="browsable";
    public static final int FLAG_GRANT_READ_URI_PERMISSION=1;
    public final String action;
    public Uri uri;
    public String packageName,className;
    public Intent(String a) {
        action=a;
    }
    public Intent(String a,Uri u) {
        action=a;
        uri=u;
    }
    public Intent addCategory(String c) {
        return this;
    }
    public Intent setDataAndType(Uri u,String t) {
        uri=u;
        return this;
    }
    public Intent addFlags(int f) {
        return this;
    }
    public void setClipData(ClipData c) {
    }
    public Intent setClassName(String p,String c) {
        packageName=p;
        className=c;
        return this;
    }
}
