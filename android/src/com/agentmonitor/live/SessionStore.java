package com.agentmonitor.live;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Only ciphertext reaches disk; tokens and PKCE verifiers are never logged. */
public final class SessionStore {
    private static final String ALIAS = "monitor_live_session_v1";
    private static SharedPreferences prefs(Context context) { return context.getSharedPreferences("native_connection", Context.MODE_PRIVATE); }
    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
    public static synchronized JSONObject read(Context context) {
        String sealed = prefs(context).getString("sealed", "");
        if (sealed.isEmpty()) return new JSONObject();
        try {
            String[] parts = sealed.split(":", 2);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
            return new JSONObject(new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8));
        } catch (Exception ignored) { prefs(context).edit().clear().commit(); return new JSONObject(); }
    }
    public static synchronized void write(Context context, JSONObject value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted = cipher.doFinal(value.toString().getBytes(StandardCharsets.UTF_8));
        String sealed = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" + Base64.encodeToString(encrypted, Base64.NO_WRAP);
        if (!prefs(context).edit().putString("sealed", sealed).commit()) throw new Exception("保存连接失败");
    }
    public static synchronized void clear(Context context) { prefs(context).edit().clear().commit(); }
    public static synchronized void clearIfToken(Context context, String token) {
        JSONObject store = read(context);
        if (!token.equals(store.optString("reader_token"))) return;
        JSONObject pairing = store.optJSONObject("pairing");
        if (pairing != null) {
            try { write(context, new JSONObject().put("pairing", pairing)); } catch (Exception ignored) { clear(context); }
        } else { clear(context); WebSessionCookies.clear(); }
    }
    public static String token(Context context) { JSONObject store = read(context); return store.optBoolean("logout_pending") ? "" : store.optString("reader_token", ""); }
}


