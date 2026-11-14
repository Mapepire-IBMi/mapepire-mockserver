package io.github.mapepire_ibmi.mockserver;

import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Describes how the mock server responds to SQL statements that match a regular expression. A rule produces exactly
 * one of three outcomes: a result set, an update count, or a SQL error.
 * <p>
 * The pattern is matched, case-insensitively and with <code>.</code> matching line terminators, against the whole
 * statement after leading and trailing whitespace and any trailing semicolons have been removed. A rule can also be
 * restricted to a specific list of parameter values, in which case it only matches prepared executions whose
 * <code>parameters</code> array is equal to that list.
 */
public class SqlRule {

    private final long m_delayMs;
    private final String m_errorMessage;
    private final int m_errorSqlRc;
    private final String m_errorSqlState;
    private final Kind m_kind;
    private final JsonArray m_outputParms;
    private final JsonArray m_parameters;
    private final Pattern m_pattern;
    private final MockResultSet m_resultSet;
    private final long m_updateCount;

    /**
     * Create a rule. Use the static factory methods rather than calling this directly.
     *
     * @param _pattern
     *            the compiled statement pattern
     * @param _kind
     *            the kind of outcome this rule produces
     * @param _resultSet
     *            the result set, for {@link Kind#RESULT_SET} rules
     * @param _updateCount
     *            the update count, for {@link Kind#UPDATE} rules
     * @param _errorMessage
     *            the error text, for {@link Kind#ERROR} rules
     * @param _errorSqlState
     *            the SQLSTATE, for {@link Kind#ERROR} rules
     * @param _errorSqlRc
     *            the SQL return code, for {@link Kind#ERROR} rules
     * @param _parameters
     *            the exact parameter values this rule is restricted to, or <code>null</code> for any
     * @param _outputParms
     *            the <code>output_parms</code> to report for prepared executions, or <code>null</code>
     * @param _delayMs
     *            how long to wait before replying, in milliseconds
     */
    private SqlRule(final Pattern _pattern, final Kind _kind, final MockResultSet _resultSet, final long _updateCount, final String _errorMessage, final String _errorSqlState, final int _errorSqlRc, final JsonArray _parameters, final JsonArray _outputParms, final long _delayMs) {
        m_pattern = _pattern;
        m_kind = _kind;
        m_resultSet = _resultSet;
        m_updateCount = _updateCount;
        m_errorMessage = _errorMessage;
        m_errorSqlState = _errorSqlState;
        m_errorSqlRc = _errorSqlRc;
        m_parameters = _parameters;
        m_outputParms = _outputParms;
        m_delayMs = _delayMs;
    }

    private static Pattern compile(final String _regex) {
        return Pattern.compile(_regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    }

    /**
     * Create a rule that fails matching statements with a SQL error.
     *
     * @param _regex
     *            the statement pattern
     * @param _message
     *            the error text
     * @param _sqlState
     *            the SQLSTATE to report
     * @param _sqlRc
     *            the SQL return code to report
     * @return the rule
     */
    public static SqlRule error(final String _regex, final String _message, final String _sqlState, final int _sqlRc) {
        return new SqlRule(compile(_regex), Kind.ERROR, null, 0, _message, _sqlState, _sqlRc, null, null, 0);
    }

    /**
     * Build a rule from its JSON definition. See the README for the full format. The outcome is an error if an
     * <code>error</code> object is present, a result set if <code>columns</code> is present, and an update count
     * otherwise.
     *
     * @param _json
     *            the rule definition
     * @return the rule
     * @throws IllegalArgumentException
     *             if the definition is malformed
     */
    public static SqlRule fromJson(final JsonObject _json) {
        final String regex = JsonUtils.getString(_json, "pattern", null);
        if (null == regex) {
            throw new IllegalArgumentException("SQL rule is missing 'pattern': " + _json);
        }
        final SqlRule base;
        final JsonElement error = _json.get("error");
        final JsonElement columns = _json.get("columns");
        if (null != error && error.isJsonObject()) {
            final JsonObject errorObj = error.getAsJsonObject();
            base = error(regex, JsonUtils.getString(errorObj, "message", "Mock SQL error"), JsonUtils.getString(errorObj, "sql_state", "HY000"), JsonUtils.getInt(errorObj, "sql_rc", -1));
        } else if (null != columns && columns.isJsonArray()) {
            final JsonElement rows = _json.get("rows");
            base = resultSet(regex, MockResultSet.fromJson(columns.getAsJsonArray(), null != rows && rows.isJsonArray() ? rows.getAsJsonArray() : null));
        } else {
            base = updateCount(regex, JsonUtils.getLong(_json, "update_count", 0));
        }
        final JsonElement parameters = _json.get("parameters");
        final JsonElement outputParms = _json.get("output_parms");
        //@formatter:off
        return new SqlRule(base.m_pattern, base.m_kind, base.m_resultSet, base.m_updateCount, base.m_errorMessage, base.m_errorSqlState, base.m_errorSqlRc,
                null != parameters && parameters.isJsonArray() ? parameters.getAsJsonArray() : null,
                null != outputParms && outputParms.isJsonArray() ? outputParms.getAsJsonArray() : null,
                JsonUtils.getLong(_json, "delay_ms", 0));
        //@formatter:on
    }

    /**
     * Normalize a statement for matching: trim whitespace and strip trailing semicolons.
     *
     * @param _sql
     *            the statement as sent by the client
     * @return the normalized statement
     */
    public static String normalize(final String _sql) {
        String ret = _sql.trim();
        while (ret.endsWith(";")) {
            ret = ret.substring(0, ret.length() - 1).trim();
        }
        return ret;
    }

    /**
     * Create a rule that answers matching statements with a result set.
     *
     * @param _regex
     *            the statement pattern
     * @param _resultSet
     *            the result set to return
     * @return the rule
     */
    public static SqlRule resultSet(final String _regex, final MockResultSet _resultSet) {
        return new SqlRule(compile(_regex), Kind.RESULT_SET, _resultSet, -1, null, null, 0, null, null, 0);
    }

    /**
     * Create a rule that answers matching statements with an update count and no result set.
     *
     * @param _regex
     *            the statement pattern
     * @param _updateCount
     *            the number of rows to report as affected
     * @return the rule
     */
    public static SqlRule updateCount(final String _regex, final long _updateCount) {
        return new SqlRule(compile(_regex), Kind.UPDATE, null, _updateCount, null, null, 0, null, null, 0);
    }

    public long getDelayMs() {
        return m_delayMs;
    }

    public Kind getKind() {
        return m_kind;
    }

    public JsonArray getOutputParms() {
        return m_outputParms;
    }

    public String getPattern() {
        return m_pattern.pattern();
    }

    public MockResultSet getResultSet() {
        return m_resultSet;
    }

    public long getUpdateCount() {
        return m_updateCount;
    }

    public boolean hasParameterConstraint() {
        return null != m_parameters;
    }

    /**
     * Decide whether this rule applies to a statement.
     *
     * @param _normalizedSql
     *            the statement, already passed through {@link #normalize(String)}
     * @param _parameters
     *            the parameter values supplied with the statement, or <code>null</code> to ignore any parameter
     *            restriction on this rule
     * @return <code>true</code> if the rule applies
     */
    public boolean matches(final String _normalizedSql, final JsonArray _parameters) {
        if (!m_pattern.matcher(_normalizedSql).matches()) {
            return false;
        }
        if (null == m_parameters || null == _parameters) {
            return true;
        }
        return m_parameters.equals(_parameters);
    }

    /**
     * Create the exception an {@link Kind#ERROR} rule reports.
     *
     * @return a new exception carrying this rule's message, SQLSTATE and return code
     */
    public MockSqlException newException() {
        return new MockSqlException(m_errorMessage, m_errorSqlState, m_errorSqlRc);
    }

    /**
     * Copy this rule, making the copy wait before replying. Useful for exercising client-side timeouts.
     *
     * @param _delayMs
     *            the delay in milliseconds
     * @return the delayed copy
     */
    public SqlRule withDelay(final long _delayMs) {
        return new SqlRule(m_pattern, m_kind, m_resultSet, m_updateCount, m_errorMessage, m_errorSqlState, m_errorSqlRc, m_parameters, m_outputParms, _delayMs);
    }

    /**
     * Copy this rule, restricting the copy to statements executed with exactly the given parameter values.
     *
     * @param _parameters
     *            the parameter values to require
     * @return the restricted copy
     */
    public SqlRule withParameters(final JsonArray _parameters) {
        return new SqlRule(m_pattern, m_kind, m_resultSet, m_updateCount, m_errorMessage, m_errorSqlState, m_errorSqlRc, _parameters, m_outputParms, m_delayMs);
    }

    /**
     * The kinds of outcome a rule can produce.
     */
    public enum Kind {
        /** The statement fails with a SQL error. */
        ERROR,
        /** The statement produces a result set. */
        RESULT_SET,
        /** The statement produces an update count and no result set. */
        UPDATE
    }
}
