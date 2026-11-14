package io.github.mapepire_ibmi.mockserver;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Describes how the mock server responds to CL commands (the <code>cl</code> and <code>rccl</code> request types)
 * that match a regular expression. The pattern is matched case-insensitively against the whole, trimmed command.
 * <p>
 * Each message is a JSON object using the field names of the real server's IBM i message objects:
 * <code>id</code>, <code>type</code>, <code>severity</code>, <code>text</code>, <code>help</code>,
 * <code>time</code>, <code>messageLibrary</code>, <code>messageFile</code>, <code>sendingProgram</code>,
 * <code>sendingInstruction</code>, <code>receivingProgram</code>, and <code>receivingInstruction</code>.
 */
public class ClRule {

    private final long m_delayMs;
    private final boolean m_isSuccess;
    private final List<JsonObject> m_messages;
    private final Pattern m_pattern;

    /**
     * Create a rule.
     *
     * @param _regex
     *            the command pattern
     * @param _isSuccess
     *            whether matching commands succeed
     * @param _messages
     *            the messages a matching command produces
     * @param _delayMs
     *            how long to wait before replying, in milliseconds
     */
    public ClRule(final String _regex, final boolean _isSuccess, final List<JsonObject> _messages, final long _delayMs) {
        m_pattern = Pattern.compile(_regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        m_isSuccess = _isSuccess;
        m_messages = Collections.unmodifiableList(new ArrayList<JsonObject>(_messages));
        m_delayMs = _delayMs;
    }

    private static void addIfAbsent(final JsonObject _obj, final String _key, final String _value) {
        if (!_obj.has(_key)) {
            _obj.addProperty(_key, _value);
        }
    }

    /**
     * Create a rule under which matching commands fail.
     *
     * @param _regex
     *            the command pattern
     * @param _messages
     *            the messages produced; the last <code>ESCAPE</code> message other than <code>CPF0006</code> is
     *            used to build the error text
     * @return the rule
     */
    public static ClRule failure(final String _regex, final JsonObject... _messages) {
        return new ClRule(_regex, false, Arrays.asList(_messages), 0);
    }

    /**
     * Build a rule from its JSON definition: <code>pattern</code> (required), <code>success</code> (default
     * <code>true</code>), <code>messages</code> (default none), and <code>delay_ms</code> (default 0).
     *
     * @param _json
     *            the rule definition
     * @return the rule
     * @throws IllegalArgumentException
     *             if the definition is malformed
     */
    public static ClRule fromJson(final JsonObject _json) {
        final String regex = JsonUtils.getString(_json, "pattern", null);
        if (null == regex) {
            throw new IllegalArgumentException("CL rule is missing 'pattern': " + _json);
        }
        final List<JsonObject> messages = new ArrayList<JsonObject>();
        final JsonElement messagesElement = _json.get("messages");
        if (null != messagesElement && messagesElement.isJsonArray()) {
            for (final JsonElement message : messagesElement.getAsJsonArray()) {
                if (!message.isJsonObject()) {
                    throw new IllegalArgumentException("CL message must be an object: " + message);
                }
                messages.add(message.getAsJsonObject());
            }
        }
        return new ClRule(regex, JsonUtils.getBoolean(_json, "success", true), messages, JsonUtils.getLong(_json, "delay_ms", 0));
    }

    /**
     * Create a message object for use in a rule.
     *
     * @param _id
     *            the message identifier, for example <code>CPF2110</code>
     * @param _type
     *            the message type, for example <code>COMPLETION</code> or <code>ESCAPE</code>
     * @param _severity
     *            the message severity
     * @param _text
     *            the first-level message text
     * @return the message
     */
    public static JsonObject message(final String _id, final String _type, final int _severity, final String _text) {
        final JsonObject ret = new JsonObject();
        ret.addProperty("id", _id);
        ret.addProperty("type", _type);
        ret.addProperty("severity", _severity);
        ret.addProperty("text", _text);
        return ret;
    }

    /**
     * Create a rule under which matching commands succeed.
     *
     * @param _regex
     *            the command pattern
     * @param _messages
     *            the messages produced
     * @return the rule
     */
    public static ClRule success(final String _regex, final JsonObject... _messages) {
        return new ClRule(_regex, true, Arrays.asList(_messages), 0);
    }

    /**
     * Build the messages to report for one execution of a command. Fields a rule leaves out are filled in with
     * the same defaults for every message, so that every reply carries the full set of message fields.
     *
     * @param _timestamp
     *            the epoch-millisecond time to report for messages that do not specify one
     * @return a fresh, mutable copy of the messages
     */
    public JsonArray buildMessages(final long _timestamp) {
        final JsonArray ret = new JsonArray();
        for (final JsonObject template : m_messages) {
            final JsonObject message = template.deepCopy();
            addIfAbsent(message, "id", null);
            addIfAbsent(message, "type", "COMPLETION");
            if (!message.has("severity")) {
                message.addProperty("severity", 0);
            }
            addIfAbsent(message, "text", "");
            addIfAbsent(message, "help", "");
            if (!message.has("time")) {
                message.addProperty("time", _timestamp);
            }
            addIfAbsent(message, "messageLibrary", "QSYS");
            addIfAbsent(message, "messageFile", "QCPFMSG");
            addIfAbsent(message, "sendingProgram", "QMOCKSVR");
            addIfAbsent(message, "sendingInstruction", "0000");
            addIfAbsent(message, "receivingProgram", "QSQCALLSP");
            addIfAbsent(message, "receivingInstruction", "0000");
            ret.add(message);
        }
        return ret;
    }

    public long getDelayMs() {
        return m_delayMs;
    }

    public boolean isSuccess() {
        return m_isSuccess;
    }

    public boolean matches(final String _command) {
        return m_pattern.matcher(_command.trim()).matches();
    }
}
