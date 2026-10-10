package org.json;

public final class JSONArray {
    public int length() { throw new AssertionError("Unexpected JSON access"); }
    public JSONObject optJSONObject(int index) { throw new AssertionError("Unexpected JSON access"); }
}
