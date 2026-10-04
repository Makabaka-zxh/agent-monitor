package android.app;

import android.content.Context;
import android.content.Intent;
import java.util.HashMap;
import java.util.Map;

/** Models identity for the intent fields used here. Extras do not distinguish tokens. */
public final class PendingIntent {
    public static final int FLAG_UPDATE_CURRENT = 1 << 27, FLAG_IMMUTABLE = 1 << 26;
    private static final Map<String, PendingIntent> TOKENS = new HashMap<>();
    public final String kind;
    public final int flags;
    public Intent intent;
    private PendingIntent(String kind, Intent intent, int flags) {
        this.kind = kind; this.intent = new Intent(intent); this.flags = flags;
    }
    private static PendingIntent obtain(String kind, int requestCode, Intent intent, int flags) {
        String identity = kind + "|" + requestCode + "|" + intent.component.getName() + "|"
                + intent.getAction() + "|" + intent.getDataString() + "|" + (flags & FLAG_IMMUTABLE);
        PendingIntent token = TOKENS.get(identity);
        if (token == null) { token = new PendingIntent(kind, intent, flags); TOKENS.put(identity, token); }
        else if ((flags & FLAG_UPDATE_CURRENT) != 0) token.intent = new Intent(intent);
        return token;
    }
    public static PendingIntent getActivity(Context context, int requestCode, Intent intent, int flags) { return obtain("activity", requestCode, intent, flags); }
    public static PendingIntent getBroadcast(Context context, int requestCode, Intent intent, int flags) { return obtain("broadcast", requestCode, intent, flags); }
    public static void reset() { TOKENS.clear(); }
}