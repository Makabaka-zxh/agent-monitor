package org.json;

/** Compilation boundary: package/provider scenarios must not enter metadata/network parsing. */
public final class JSONObject {
    public JSONObject(String value) {
        throw new AssertionError("Metadata parsing is outside this package/provider host test");
    }
    public Object opt(String key) { throw new AssertionError("Unexpected JSON access"); }
    public JSONArray optJSONArray(String key) { throw new AssertionError("Unexpected JSON access"); }
}
