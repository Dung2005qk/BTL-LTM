package com.ltm.geoduel.common;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

/**
 * Phong bì thông điệp: {"type": "...", "data": {...}}.
 * Một dòng JSON = một thông điệp trên đường truyền TCP.
 */
public final class Message {
    private static final Gson GSON = new Gson();

    private final String type;
    private final JsonObject data;

    public Message(String type) {
        this(type, new JsonObject());
    }

    public Message(String type, JsonObject data) {
        this.type = type;
        this.data = data == null ? new JsonObject() : data;
    }

    public String type() { return type; }
    public JsonObject data() { return data; }

    // ----- builder tiện dụng -----
    public Message put(String key, String value) { data.addProperty(key, value); return this; }
    public Message put(String key, Number value) { data.addProperty(key, value); return this; }
    public Message put(String key, boolean value) { data.addProperty(key, value); return this; }
    public Message put(String key, JsonElement value) { data.add(key, value); return this; }

    // ----- đọc an toàn (client không đáng tin: thiếu trường không được gây crash) -----
    public String getString(String key, String def) {
        JsonElement e = data.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : def;
    }
    public int getInt(String key, int def) {
        JsonElement e = data.get(key);
        try { return e != null && e.isJsonPrimitive() ? e.getAsInt() : def; }
        catch (NumberFormatException ex) { return def; }
    }
    public long getLong(String key, long def) {
        JsonElement e = data.get(key);
        try { return e != null && e.isJsonPrimitive() ? e.getAsLong() : def; }
        catch (NumberFormatException ex) { return def; }
    }
    public double getDouble(String key, double def) {
        JsonElement e = data.get(key);
        try { return e != null && e.isJsonPrimitive() ? e.getAsDouble() : def; }
        catch (NumberFormatException ex) { return def; }
    }
    public boolean getBool(String key, boolean def) {
        JsonElement e = data.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsBoolean() : def;
    }
    public JsonObject getObject(String key) {
        JsonElement e = data.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }
    public JsonArray getArray(String key) {
        JsonElement e = data.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
    }

    public String toJsonLine() {
        JsonObject root = new JsonObject();
        root.addProperty("type", type);
        root.add("data", data);
        return GSON.toJson(root);
    }

    /** @return null nếu dòng không phải JSON hợp lệ hoặc thiếu type. */
    public static Message parse(String line) {
        if (line == null || line.isBlank()) return null;
        try {
            JsonElement root = JsonParser.parseString(line);
            if (!root.isJsonObject()) return null;
            JsonObject obj = root.getAsJsonObject();
            JsonElement type = obj.get("type");
            if (type == null || !type.isJsonPrimitive()) return null;
            JsonElement data = obj.get("data");
            JsonObject dataObj = data != null && data.isJsonObject() ? data.getAsJsonObject() : new JsonObject();
            return new Message(type.getAsString(), dataObj);
        } catch (JsonSyntaxException ex) {
            return null;
        }
    }

    @Override
    public String toString() {
        return toJsonLine();
    }
}
