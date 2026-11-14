package io.github.mapepire_ibmi.mockserver;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Small helpers for reading optional, typed fields out of Gson objects, and the shared {@link Gson} instance used
 * to serialize replies.
 */
public final class JsonUtils {

    /**
     * Serializer for replies. Like the real Mapepire server, nulls are written out explicitly rather than omitted.
     */
    private static final Gson s_replyGson = new GsonBuilder().serializeNulls().create();

    private JsonUtils() {
    }

    /**
     * Read an optional boolean field.
     *
     * @param _obj
     *            the object to read from
     * @param _key
     *            the field name
     * @param _default
     *            the value to return when the field is absent, null, or not a boolean
     * @return the field value, or <code>_default</code>
     */
    public static boolean getBoolean(final JsonObject _obj, final String _key, final boolean _default) {
        final JsonElement element = _obj.get(_key);
        if (null == element || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isBoolean()) {
            return _default;
        }
        return element.getAsBoolean();
    }

    /**
     * Read an optional integer field.
     *
     * @param _obj
     *            the object to read from
     * @param _key
     *            the field name
     * @param _default
     *            the value to return when the field is absent, null, or not a number
     * @return the field value, or <code>_default</code>
     */
    public static int getInt(final JsonObject _obj, final String _key, final int _default) {
        final JsonElement element = _obj.get(_key);
        if (null == element || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            return _default;
        }
        return element.getAsInt();
    }

    /**
     * Read an optional long field.
     *
     * @param _obj
     *            the object to read from
     * @param _key
     *            the field name
     * @param _default
     *            the value to return when the field is absent, null, or not a number
     * @return the field value, or <code>_default</code>
     */
    public static long getLong(final JsonObject _obj, final String _key, final long _default) {
        final JsonElement element = _obj.get(_key);
        if (null == element || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            return _default;
        }
        return element.getAsLong();
    }

    public static Gson getReplyGson() {
        return s_replyGson;
    }

    /**
     * Read an optional string field. Numbers and booleans are converted to their string form.
     *
     * @param _obj
     *            the object to read from
     * @param _key
     *            the field name
     * @param _default
     *            the value to return when the field is absent, null, or not a primitive
     * @return the field value, or <code>_default</code>
     */
    public static String getString(final JsonObject _obj, final String _key, final String _default) {
        final JsonElement element = _obj.get(_key);
        if (null == element || !element.isJsonPrimitive()) {
            return _default;
        }
        return element.getAsString();
    }
}
