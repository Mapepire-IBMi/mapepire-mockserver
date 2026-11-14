package io.github.mapepire_ibmi.mockserver;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * The protocol engine for one client connection. A session receives the text of each request, simulates the work
 * the real Mapepire server would do, and returns the text of the reply. It is independent of the transport, so it
 * can be driven directly in tests as well as by {@link MockServer}.
 * <p>
 * Replies follow the real server's conventions: every reply carries the request's <code>id</code>, a
 * <code>success</code> flag and an <code>execution_time</code>; failures add <code>error</code> and, for SQL
 * failures, <code>sql_state</code> and <code>sql_rc</code>; and nulls are serialized explicitly. Requests are
 * processed one at a time, in the order they arrive.
 */
public class MockSession {

    private static final int DEFAULT_ROWS = 1000;

    private static final AtomicInteger s_incompleteCounter = new AtomicInteger(1);
    private static final AtomicInteger s_jobNumber = new AtomicInteger(100000);
    private static final AtomicInteger s_unparseableCounter = new AtomicInteger(1);

    private final MockServerConfig m_config;
    private boolean m_isEnded = false;
    private String m_jobName;
    private final Map<String, StatementHandle> m_preparedStatements = new LinkedHashMap<String, StatementHandle>();
    private final Map<String, StatementHandle> m_queries = new LinkedHashMap<String, StatementHandle>();
    private final MockTracer m_tracer;

    /**
     * Create a session.
     *
     * @param _config
     *            the server configuration, which supplies the rules and version information
     * @param _tracer
     *            the tracer for this connection
     */
    public MockSession(final MockServerConfig _config, final MockTracer _tracer) {
        m_config = _config;
        m_tracer = _tracer;
        m_jobName = nextJobName();
    }

    /**
     * Count the parameter markers in a statement, ignoring any that appear inside quoted strings or identifiers.
     *
     * @param _sql
     *            the statement text
     * @return the number of <code>?</code> markers
     */
    static int countParameterMarkers(final String _sql) {
        int count = 0;
        char quote = 0;
        for (int i = 0; i < _sql.length(); ++i) {
            final char c = _sql.charAt(i);
            if (0 != quote) {
                if (quote == c) {
                    quote = 0;
                }
            } else if ('\'' == c || '"' == c) {
                quote = c;
            } else if ('?' == c) {
                ++count;
            }
        }
        return count;
    }

    /**
     * Escape text for inclusion in HTML or XML.
     *
     * @param _text
     *            the raw text
     * @return the escaped text
     */
    private static String escapeMarkup(final String _text) {
        return _text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /**
     * Build the error text for a failed CL command the way the real server does: from the most recent
     * <code>ESCAPE</code> message other than the generic <code>CPF0006</code>.
     *
     * @param _messages
     *            the messages the command produced
     * @return the error text
     */
    private static String getClErrorText(final JsonArray _messages) {
        String detail = "Unknown error";
        for (final JsonElement element : _messages) {
            final JsonObject message = element.getAsJsonObject();
            final String id = JsonUtils.getString(message, "id", null);
            if ("ESCAPE".equals(JsonUtils.getString(message, "type", null)) && !"CPF0006".equals(id)) {
                detail = String.format("[%s] %s", id, JsonUtils.getString(message, "text", ""));
            }
        }
        return "CL command execution failed: " + detail;
    }

    /**
     * Read a field a request type requires.
     *
     * @param _req
     *            the request
     * @param _key
     *            the field name
     * @return the field value as a string
     * @throws MockRequestException
     *             if the field is absent or not a primitive
     */
    private static String getRequiredString(final JsonObject _req, final String _key) throws MockRequestException {
        final String ret = JsonUtils.getString(_req, _key, null);
        if (null == ret) {
            throw new MockRequestException("Request is missing required field '" + _key + "'");
        }
        return ret;
    }

    private static String nextJobName() {
        return String.format("%06d/QUSER/QZDASOINIT", s_jobNumber.getAndIncrement());
    }

    /**
     * Wait before replying, to simulate a slow operation.
     *
     * @param _delayMs
     *            the delay in milliseconds; nothing happens if it is not positive
     */
    private static void sleep(final long _delayMs) {
        if (0 >= _delayMs) {
            return;
        }
        try {
            Thread.sleep(_delayMs);
        } catch (final InterruptedException _e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Build the <code>metadata</code> object for a prepared statement: the result set's columns (when the
     * statement produces one) and a description of each parameter marker.
     *
     * @param _rule
     *            the rule describing the statement
     * @param _parameterCount
     *            the number of parameter markers
     * @return the metadata
     */
    private Map<String, Object> buildMetadata(final SqlRule _rule, final int _parameterCount) {
        final Map<String, Object> ret = SqlRule.Kind.RESULT_SET == _rule.getKind() ? _rule.getResultSet().toMetadata(m_jobName) : new LinkedHashMap<String, Object>();
        final List<Map<String, Object>> parameters = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < _parameterCount; ++i) {
            final Map<String, Object> parameter = new LinkedHashMap<String, Object>();
            parameter.put("type", "VARCHAR");
            parameter.put("mode", "IN");
            parameter.put("precision", 255);
            parameter.put("scale", 0);
            parameter.put("name", "");
            parameters.add(parameter);
        }
        ret.put("parameters", parameters);
        return ret;
    }

    /**
     * Process one request of a known type, adding the type-specific reply fields.
     *
     * @param _type
     *            the request type
     * @param _id
     *            the request's correlation ID
     * @param _req
     *            the request
     * @param _reply
     *            the reply being built
     * @throws MockRequestException
     *             if the request fails, or its type is not known
     */
    private void dispatch(final String _type, final String _id, final JsonObject _req, final Map<String, Object> _reply) throws MockRequestException {
        switch (_type) {
            case "connect":
                handleConnect(_req, _reply);
                break;
            case "sql":
                handleSql(_id, _req, _reply);
                break;
            case "prepare_sql":
                handlePrepareSql(_id, _req, _reply, false);
                break;
            case "prepare_sql_execute":
                handlePrepareSql(_id, _req, _reply, true);
                break;
            case "execute":
                executePrepared(getContinuation(_req, false), _req, _reply);
                break;
            case "sqlmore":
                handleSqlMore(_req, _reply);
                break;
            case "sqlclose":
                handleSqlClose(_req);
                break;
            case "cl":
                handleCl(_req, _reply, true);
                break;
            case "rccl":
                handleCl(_req, _reply, false);
                break;
            case "dove":
                handleDove(_req, _reply);
                break;
            case "ping":
                _reply.put("alive", true);
                _reply.put("db_alive", true);
                break;
            case "getdbjob":
                _reply.put("job", m_jobName);
                break;
            case "getversion":
                _reply.put("build_date", m_config.getBuildDate());
                _reply.put("version", m_config.getVersion());
                break;
            case "setconfig":
                handleSetConfig(_req, _reply);
                break;
            case "gettracedata":
                _reply.put("tracedata", m_tracer.getData());
                _reply.put("jtopentracedata", "");
                break;
            case "getcldoc":
                handleGetClDoc(_req, _reply);
                break;
            case "getcmdxml":
                handleGetCmdXml(_req, _reply);
                break;
            case "exit":
                m_isEnded = true;
                break;
            default:
                throw new MockRequestException("Unknown request type: " + _type);
        }
    }

    /**
     * Execute a prepared statement with the request's <code>parameters</code>, either once (a flat array) or as a
     * batch (an array of arrays), and add the execution's reply fields.
     *
     * @param _handle
     *            the prepared statement
     * @param _req
     *            the <code>execute</code> or <code>prepare_sql_execute</code> request
     * @param _reply
     *            the reply being built
     * @throws MockRequestException
     *             if the parameters are missing or the wrong length, or the matching rule is an error rule
     */
    private void executePrepared(final StatementHandle _handle, final JsonObject _req, final Map<String, Object> _reply) throws MockRequestException {
        final JsonElement parametersElement = _req.get("parameters");
        if (null == parametersElement || !parametersElement.isJsonArray()) {
            throw new MockRequestException("Request is missing required field 'parameters'");
        }
        final JsonArray parameters = parametersElement.getAsJsonArray();
        final boolean isTerse = JsonUtils.getBoolean(_req, "terse", _handle.isTerse());
        _handle.setTerse(isTerse);
        _handle.setCursor(null);

        if (0 < parameters.size() && parameters.get(0).isJsonArray()) {
            long updateCount = 0;
            for (final JsonElement batchEntry : parameters) {
                if (!batchEntry.isJsonArray()) {
                    throw new MockRequestException("Every entry of a batch 'parameters' array must itself be an array");
                }
                verifyParameterCount(_handle, batchEntry.getAsJsonArray());
                final SqlRule rule = m_config.getDatabase().findSqlRule(_handle.getSql(), batchEntry.getAsJsonArray());
                sleep(rule.getDelayMs());
                if (SqlRule.Kind.ERROR == rule.getKind()) {
                    throw rule.newException();
                }
                if (SqlRule.Kind.RESULT_SET == rule.getKind()) {
                    throw new MockSqlException("A statement that returns a result set cannot be run as a batch.", "07000", -99999);
                }
                updateCount += rule.getUpdateCount();
            }
            _reply.put("batch_added", parameters.size());
            _reply.put("data", new ArrayList<Object>());
            _reply.put("has_results", false);
            _reply.put("update_count", updateCount);
            _reply.put("output_parms", null);
            _reply.put("is_done", true);
            return;
        }

        verifyParameterCount(_handle, parameters);
        final SqlRule rule = m_config.getDatabase().findSqlRule(_handle.getSql(), parameters);
        sleep(rule.getDelayMs());
        if (SqlRule.Kind.ERROR == rule.getKind()) {
            throw rule.newException();
        }
        if (SqlRule.Kind.RESULT_SET == rule.getKind()) {
            final MockCursor cursor = new MockCursor(rule.getResultSet());
            _handle.setCursor(cursor);
            _reply.put("has_results", true);
            _reply.put("update_count", -1);
            _reply.put("metadata", buildMetadata(rule, _handle.getParameterCount()));
            _reply.put("data", cursor.nextBlock(JsonUtils.getInt(_req, "rows", DEFAULT_ROWS), isTerse));
            _reply.put("output_parms", rule.getOutputParms());
            _reply.put("is_done", cursor.isDone());
        } else {
            _reply.put("data", new ArrayList<Object>());
            _reply.put("has_results", false);
            _reply.put("update_count", rule.getUpdateCount());
            _reply.put("output_parms", rule.getOutputParms());
            _reply.put("is_done", true);
        }
    }

    /**
     * Complete a reply: record success or failure, add the execution time, and serialize it.
     *
     * @param _reply
     *            the reply being built
     * @param _startNanos
     *            the {@link System#nanoTime()} at which processing started
     * @param _failure
     *            the failure to report, or <code>null</code> if the request succeeded
     * @return the reply text
     */
    private String finish(final Map<String, Object> _reply, final long _startNanos, final MockRequestException _failure) {
        if (null == _failure) {
            _reply.put("success", true);
        } else {
            _reply.put("error", _failure.getMessage());
            _reply.put("success", false);
            if (_failure instanceof MockSqlException) {
                _reply.put("sql_state", ((MockSqlException) _failure).getSqlState());
                _reply.put("sql_rc", ((MockSqlException) _failure).getSqlRc());
            }
        }
        _reply.put("execution_time", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - _startNanos));
        final String ret = JsonUtils.getReplyGson().toJson(_reply);
        m_tracer.logDatastreamOut(ret);
        return ret;
    }

    /**
     * Look up the statement a <code>cont_id</code> refers to.
     *
     * @param _req
     *            the request carrying the <code>cont_id</code>
     * @param _isIncludingQueries
     *            whether statements opened by plain <code>sql</code> requests are eligible, as well as prepared
     *            statements
     * @return the statement
     * @throws MockRequestException
     *             if the <code>cont_id</code> is missing or does not refer to an open statement
     */
    private StatementHandle getContinuation(final JsonObject _req, final boolean _isIncludingQueries) throws MockRequestException {
        final String contId = JsonUtils.getString(_req, "cont_id", null);
        if (null == contId) {
            throw new MockRequestException("Correlation ID not specified");
        }
        StatementHandle ret = _isIncludingQueries ? m_queries.get(contId) : null;
        if (null == ret) {
            ret = m_preparedStatements.get(contId);
        }
        if (null == ret) {
            throw new MockRequestException("invalid correlation ID");
        }
        return ret;
    }

    public synchronized String getJobName() {
        return m_jobName;
    }

    public MockTracer getTracer() {
        return m_tracer;
    }

    /**
     * Process one request and produce its reply.
     *
     * @param _request
     *            the request text
     * @return the reply text, or <code>null</code> if the request was blank and needs no reply
     */
    public synchronized String handle(final String _request) {
        final long startNanos = System.nanoTime();
        final Map<String, Object> reply = new LinkedHashMap<String, Object>();
        if (_request.trim().isEmpty()) {
            return null;
        }
        final JsonObject req;
        try {
            req = JsonParser.parseString(_request).getAsJsonObject();
        } catch (final JsonParseException | IllegalStateException _e) {
            m_tracer.logDatastreamIn(_request);
            reply.put("id", "unparseable_" + s_unparseableCounter.getAndIncrement());
            reply.put("unparseable", _request);
            return finish(reply, startNanos, new MockRequestException("Non-parseable request: '" + _request + "'"));
        }
        final String id = JsonUtils.getString(req, "id", null);
        final String type = JsonUtils.getString(req, "type", null);
        m_tracer.logDatastreamIn(redact(req, type));
        if (null == id || null == type) {
            reply.put("id", "incomplete_" + s_incompleteCounter.getAndIncrement());
            reply.put("incomplete", _request);
            return finish(reply, startNanos, new MockRequestException("Request is missing required fields (most likely 'id' or 'type'): '" + _request + "'"));
        }
        reply.put("id", id);
        MockRequestException failure = null;
        try {
            dispatch(type, id, req, reply);
        } catch (final MockRequestException _e) {
            failure = _e;
        } catch (final RuntimeException _e) {
            // Request boundary: any unexpected failure becomes an error reply rather than a dropped connection
            m_tracer.logErr(_e);
            failure = new MockRequestException("Internal Error: " + _e.getClass().getSimpleName() + ": " + _e.getMessage());
        }
        return finish(reply, startNanos, failure);
    }

    /**
     * Handle <code>cl</code> (run through SQL) and <code>rccl</code> (run through a remote command call)
     * requests. Both report the command's messages in <code>data</code>.
     *
     * @param _req
     *            the request
     * @param _reply
     *            the reply being built
     * @param _isUsingSql
     *            <code>true</code> for <code>cl</code>, whose failures are reported as SQL errors
     * @throws MockRequestException
     *             if <code>cmd</code> is missing or the command fails
     */
    private void handleCl(final JsonObject _req, final Map<String, Object> _reply, final boolean _isUsingSql) throws MockRequestException {
        final String cmd = getRequiredString(_req, "cmd");
        final ClRule rule = m_config.getDatabase().findClRule(cmd);
        sleep(rule.getDelayMs());
        final JsonArray messages = rule.buildMessages(System.currentTimeMillis());
        _reply.put("data", messages);
        if (!rule.isSuccess()) {
            final String error = getClErrorText(messages);
            if (_isUsingSql) {
                throw new MockSqlException(error, "38501", -443);
            }
            throw new MockRequestException(error);
        }
    }

    /**
     * Handle a <code>connect</code> request: validate the technique and start a new simulated job. Any statements
     * from a previous connection are discarded, as they would be when the real server reconnects.
     *
     * @param _req
     *            the request
     * @param _reply
     *            the reply being built
     * @throws MockRequestException
     *             if the technique is not <code>tcp</code> or <code>cli</code>
     */
    private void handleConnect(final JsonObject _req, final Map<String, Object> _reply) throws MockRequestException {
        final String technique = JsonUtils.getString(_req, "technique", "cli");
        if (!"tcp".equalsIgnoreCase(technique) && !"cli".equalsIgnoreCase(technique)) {
            throw new MockRequestException("Invalid connection technique specified");
        }
        m_queries.clear();
        m_preparedStatements.clear();
        m_jobName = nextJobName();
        m_tracer.logInfo("Connected as job " + m_jobName + " using technique " + technique.toUpperCase(Locale.ROOT));
        _reply.put("job", m_jobName);
    }

    /**
     * Handle a <code>dove</code> (Visual Explain) request. The explain data comes from the database's
     * <code>visual_explain</code> result set; when <code>run</code> is set, the statement's own results are
     * included too.
     *
     * @param _req
     *            the request
     * @param _reply
     *            the reply being built
     * @throws MockRequestException
     *             if <code>sql</code> is missing, no explain data is configured, or the statement fails
     */
    private void handleDove(final JsonObject _req, final Map<String, Object> _reply) throws MockRequestException {
        final String sql = getRequiredString(_req, "sql");
        final boolean isRunning = JsonUtils.getBoolean(_req, "run", false);
        final boolean isTerse = JsonUtils.getBoolean(_req, "terse", false);
        final MockResultSet explain = m_config.getDatabase().getVisualExplain();
        if (null == explain) {
            throw new MockRequestException("Unable to locate QQQDBVE id in QTEMP.QDOVEOUT (QQRID=3014)");
        }
        final SqlRule rule = m_config.getDatabase().findSqlRule(sql, null);
        sleep(rule.getDelayMs());
        if (SqlRule.Kind.ERROR == rule.getKind()) {
            throw rule.newException();
        }
        _reply.put("vemetadata", explain.toMetadata(m_jobName));
        _reply.put("vedata", new MockCursor(explain).nextBlock(Integer.MAX_VALUE, isTerse));
        if (isRunning && SqlRule.Kind.RESULT_SET == rule.getKind()) {
            final MockCursor cursor = new MockCursor(rule.getResultSet());
            _reply.put("metadata", rule.getResultSet().toMetadata(m_jobName));
            _reply.put("data", cursor.nextBlock(JsonUtils.getInt(_req, "rows", DEFAULT_ROWS), isTerse));
            _reply.put("is_done", cursor.isDone());
        } else {
            _reply.put("is_done", true);
        }
    }

    /**
     * Handle a <code>getcldoc</code> request with placeholder documentation for the command.
     *
     * @param _req
     *            the request
     * @param _reply
     *            the reply being built
     * @throws MockRequestException
     *             if <code>path</code> is missing
     */
    private void handleGetClDoc(final JsonObject _req, final Map<String, Object> _reply) throws MockRequestException {
        final String path = escapeMarkup(getRequiredString(_req, "path"));
        _reply.put("html", "<html><head><title>" + path + "</title></head><body><h1>" + path + "</h1><p>Command documentation generated by the Mapepire mock server.</p></body></html>");
        _reply.put("uim", ":PNLGRP.\n:HELP NAME='" + path + "'.\nCommand documentation generated by the Mapepire mock server.\n:EHELP.\n:EPNLGRP.");
    }

    /**
     * Handle a <code>getcmdxml</code> request with a minimal command definition document.
     *
     * @param _req
     *            the request
     * @param _reply
     *            the reply being built
     * @throws MockRequestException
     *             if <code>path</code> is missing
     */
    private void handleGetCmdXml(final JsonObject _req, final Map<String, Object> _reply) throws MockRequestException {
        final String path = escapeMarkup(getRequiredString(_req, "path"));
        _reply.put("xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?><QcdCLCmd DTDVersion=\"1.0\"><Cmd CmdName=\"" + path + "\" Prompt=\"Mock command\"/></QcdCLCmd>");
    }

    /**
     * Handle <code>prepare_sql</code> and <code>prepare_sql_execute</code> requests. The statement is registered
     * under the request's ID before it is prepared, so that it can be closed even if preparing fails.
     *
     * @param _id
     *            the request ID, which becomes the statement handle
     * @param _req
     *            the request
     * @param _reply
     *            the reply being built
     * @param _isExecuting
     *            <code>true</code> for <code>prepare_sql_execute</code>
     * @throws MockRequestException
     *             if <code>sql</code> is missing, or preparing or executing fails
     */
    private void handlePrepareSql(final String _id, final JsonObject _req, final Map<String, Object> _reply, final boolean _isExecuting) throws MockRequestException {
        final String sql = getRequiredString(_req, "sql");
        final StatementHandle handle = new StatementHandle(sql, JsonUtils.getBoolean(_req, "terse", false), countParameterMarkers(sql));
        m_preparedStatements.put(_id, handle);
        final SqlRule rule = m_config.getDatabase().findRuleForPrepare(sql);
        if (!_isExecuting) {
            sleep(rule.getDelayMs());
        }
        if (SqlRule.Kind.ERROR == rule.getKind()) {
            throw rule.newException();
        }
        _reply.put("metadata", buildMetadata(rule, handle.getParameterCount()));
        _reply.put("parameter_count", handle.getParameterCount());
        if (_isExecuting) {
            executePrepared(handle, _req, _reply);
        }
    }

    /**
     * Handle a <code>setconfig</code> request. Every supplied value is validated before any is applied, so an
     * invalid request changes nothing.
     *
     * @param _req
     *            the request
     * @param _reply
     *            the reply being built
     * @throws MockRequestException
     *             if any supplied destination or level is not valid
     */
    private void handleSetConfig(final JsonObject _req, final Map<String, Object> _reply) throws MockRequestException {
        final String dest = validateTraceSetting(_req, "tracedest", true);
        final String level = validateTraceSetting(_req, "tracelevel", false);
        final String jtOpenDest = validateTraceSetting(_req, "jtopentracedest", true);
        final String jtOpenLevel = validateTraceSetting(_req, "jtopentracelevel", false);
        if (null != dest) {
            m_tracer.setDest(dest);
        }
        if (null != level) {
            m_tracer.setLevel(level);
        }
        if (null != jtOpenDest) {
            m_tracer.setJtOpenDest(jtOpenDest);
        }
        if (null != jtOpenLevel) {
            m_tracer.setJtOpenLevel(jtOpenLevel);
        }
        _reply.put("tracedest", m_tracer.getDestString());
        _reply.put("tracelevel", m_tracer.getLevel());
        _reply.put("jtopentracedest", m_tracer.getJtOpenDest());
        _reply.put("jtopentracelevel", m_tracer.getJtOpenLevel());
    }

    /**
     * Handle a <code>sql</code> request. The statement is registered under the request's ID before it runs, so
     * that it can be closed even if it fails, as with the real server.
     *
     * @param _id
     *            the request ID, which becomes the cursor handle
     * @param _req
     *            the request
     * @param _reply
     *            the reply being built
     * @throws MockRequestException
     *             if <code>sql</code> is missing or the statement fails
     */
    private void handleSql(final String _id, final JsonObject _req, final Map<String, Object> _reply) throws MockRequestException {
        final String sql = getRequiredString(_req, "sql");
        final StatementHandle handle = new StatementHandle(sql, JsonUtils.getBoolean(_req, "terse", false), 0);
        m_queries.put(_id, handle);
        final SqlRule rule = m_config.getDatabase().findSqlRule(sql, null);
        sleep(rule.getDelayMs());
        if (SqlRule.Kind.ERROR == rule.getKind()) {
            throw rule.newException();
        }
        if (SqlRule.Kind.RESULT_SET == rule.getKind()) {
            final MockCursor cursor = new MockCursor(rule.getResultSet());
            handle.setCursor(cursor);
            _reply.put("has_results", true);
            _reply.put("update_count", -1);
            _reply.put("metadata", rule.getResultSet().toMetadata(m_jobName));
            _reply.put("data", cursor.nextBlock(JsonUtils.getInt(_req, "rows", DEFAULT_ROWS), handle.isTerse()));
            _reply.put("is_done", cursor.isDone());
        } else {
            _reply.put("has_results", false);
            _reply.put("update_count", rule.getUpdateCount());
        }
    }

    /**
     * Handle a <code>sqlclose</code> request, discarding the statement.
     *
     * @param _req
     *            the request
     * @throws MockRequestException
     *             if the <code>cont_id</code> is missing or unknown
     */
    private void handleSqlClose(final JsonObject _req) throws MockRequestException {
        getContinuation(_req, true);
        final String contId = JsonUtils.getString(_req, "cont_id", null);
        m_queries.remove(contId);
        m_preparedStatements.remove(contId);
    }

    /**
     * Handle a <code>sqlmore</code> request, returning the next block of rows from an open cursor.
     *
     * @param _req
     *            the request
     * @param _reply
     *            the reply being built
     * @throws MockRequestException
     *             if the <code>cont_id</code> is missing or unknown, or the statement has no result set
     */
    private void handleSqlMore(final JsonObject _req, final Map<String, Object> _reply) throws MockRequestException {
        final StatementHandle handle = getContinuation(_req, true);
        final MockCursor cursor = handle.getCursor();
        if (null == cursor) {
            throw new MockRequestException("No result set is available for correlation ID " + JsonUtils.getString(_req, "cont_id", ""));
        }
        _reply.put("data", cursor.nextBlock(JsonUtils.getInt(_req, "rows", DEFAULT_ROWS), handle.isTerse()));
        _reply.put("is_done", cursor.isDone());
    }

    /**
     * Report whether the client has sent an <code>exit</code> request, after which the connection should be
     * closed once the reply has been sent.
     *
     * @return <code>true</code> once <code>exit</code> has been processed
     */
    public synchronized boolean isEnded() {
        return m_isEnded;
    }

    /**
     * Produce the text of a request to record in the trace. JDBC properties on a <code>connect</code> request
     * can carry credentials, so they are masked.
     *
     * @param _req
     *            the parsed request
     * @param _type
     *            the request type, or <code>null</code>
     * @return the text to trace
     */
    private String redact(final JsonObject _req, final String _type) {
        if (!"connect".equals(_type) || !_req.has("props")) {
            return _req.toString();
        }
        final JsonObject copy = _req.deepCopy();
        copy.addProperty("props", "***");
        return copy.toString();
    }

    /**
     * Read and validate one optional <code>setconfig</code> field.
     *
     * @param _req
     *            the request
     * @param _key
     *            the field name
     * @param _isDestination
     *            <code>true</code> if the field is a destination, <code>false</code> if it is a level
     * @return the canonical value, or <code>null</code> if the field was not supplied
     * @throws MockRequestException
     *             if the value is not valid
     */
    private String validateTraceSetting(final JsonObject _req, final String _key, final boolean _isDestination) throws MockRequestException {
        final String value = JsonUtils.getString(_req, _key, null);
        if (null == value) {
            return null;
        }
        final String ret = _isDestination ? MockTracer.canonicalizeDest(value) : MockTracer.canonicalizeLevel(value);
        if (null == ret) {
            throw new MockRequestException("Invalid trace " + (_isDestination ? "destination" : "level") + " specified: " + value);
        }
        return ret;
    }

    /**
     * Check that the number of parameter values matches the number of parameter markers.
     *
     * @param _handle
     *            the prepared statement
     * @param _parameters
     *            the parameter values for one execution
     * @throws MockSqlException
     *             if there are too few or too many values
     */
    private void verifyParameterCount(final StatementHandle _handle, final JsonArray _parameters) throws MockSqlException {
        if (_parameters.size() < _handle.getParameterCount()) {
            throw new MockSqlException("[SQL0313] Number of host variables not valid.", "07001", -313);
        }
        if (_parameters.size() > _handle.getParameterCount()) {
            throw new MockSqlException("Descriptor index not valid.", "07009", -99999);
        }
    }

    /**
     * The server-side state of a statement opened by a <code>sql</code>, <code>prepare_sql</code>, or
     * <code>prepare_sql_execute</code> request. Accessed only while the owning session's lock is held.
     */
    private static class StatementHandle {

        private MockCursor m_cursor = null;
        private boolean m_isTerse;
        private final int m_parameterCount;
        private final String m_sql;

        StatementHandle(final String _sql, final boolean _isTerse, final int _parameterCount) {
            m_sql = _sql;
            m_isTerse = _isTerse;
            m_parameterCount = _parameterCount;
        }

        MockCursor getCursor() {
            return m_cursor;
        }

        int getParameterCount() {
            return m_parameterCount;
        }

        String getSql() {
            return m_sql;
        }

        boolean isTerse() {
            return m_isTerse;
        }

        void setCursor(final MockCursor _cursor) {
            m_cursor = _cursor;
        }

        void setTerse(final boolean _isTerse) {
            m_isTerse = _isTerse;
        }
    }
}
