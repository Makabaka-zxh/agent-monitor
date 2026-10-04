package org.json;

/** Fixed bytes only. Tests inject the decoder seam; this does not pretend to parse JSON. */
public final class JSONObject {
    private final String source;
    public JSONObject() { this("{}"); }
    public JSONObject(String source) { this.source = source; }
    public String toString() { return source; }
    public String optString(String key) { return ""; }
    public boolean optBoolean(String key, boolean fallback) { return fallback; }
    public JSONArray optJSONArray(String key) { return null; }
}
