package io.github.mapepire_ibmi.mockserver;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * The real SQL engine behind the mock server: an in-memory SQLite database that runs every statement no rule
 * answers. Clients can then create schemas and tables, insert rows (including with parameter markers and
 * batches), and query them back with ordinary SQL.
 * <p>
 * The data lives only in memory. Nothing is written to disk, and everything is discarded when the store is
 * closed or the JVM exits. A <code>QTEMP</code> schema exists from the start, as it always does on IBM i.
 * <p>
 * SQLite has no schemas, so a few Db2 statements are translated:
 * <ul>
 * <li><code>CREATE SCHEMA name</code> (or <code>CREATE COLLECTION</code>) attaches a new, empty in-memory database
 * under that name, so that <code>name.table</code> works as it does on Db2.</li>
 * <li><code>DROP SCHEMA name</code> (or <code>DROP COLLECTION</code>), with optional <code>CASCADE</code> or
 * <code>RESTRICT</code>, detaches it and discards its contents.</li>
 * <li><code>COMMIT</code> and <code>ROLLBACK</code> succeed when no transaction is active, as they do on Db2 with
 * autocommit.</li>
 * <li>An empty statement fails with SQL0104, as it does on Db2.</li>
 * </ul>
 * Everything else is passed to SQLite unchanged, so it must be valid SQLite SQL. SQLite errors are reported with
 * the closest Db2 SQLSTATE and SQL code (for example SQL0204 for a missing table).
 * <p>
 * All statements share one connection, so every client of the mock server sees the same data. Methods are
 * synchronized, so statements from different connections run one at a time.
 */
public class MockSqliteStore implements AutoCloseable {

    private static final String IDENTIFIER = "(\"(?:[^\"]|\"\")+\"|[\\p{L}_$#@][\\p{L}\\p{N}_$#@]*)";
    private static final Pattern s_createSchema = compile("create\\s+(?:schema|collection)\\s+" + IDENTIFIER);
    private static final Pattern s_dropSchema = compile("drop\\s+(?:schema|collection)\\s+" + IDENTIFIER + "(?:\\s+(?:cascade|restrict))?");
    private static final Pattern s_endTransaction = compile("(?:commit|rollback)(?:\\s+work)?(?:\\s+hold)?");
    private static final String QTEMP = "QTEMP";
    private static final String URL = "jdbc:sqlite::memory:";

    private final Connection m_connection;

    /**
     * Open a new, empty in-memory database.
     *
     * @throws SQLException
     *             if SQLite cannot be loaded or the database cannot be opened
     */
    public MockSqliteStore() throws SQLException {
        // Use the driver directly rather than DriverManager, so a merged jar's service registrations do not matter
        m_connection = new org.sqlite.JDBC().connect(URL, new Properties());
        if (null == m_connection) {
            throw new SQLException("The SQLite driver did not accept " + URL);
        }
        try {
            executeUpdate("ATTACH DATABASE ':memory:' AS " + QTEMP);
        } catch (final SQLException _e) {
            m_connection.close();
            throw _e;
        }
    }

    /**
     * Bind one parameter value from a request to a statement.
     *
     * @param _stmt
     *            the statement
     * @param _index
     *            the one-based parameter index
     * @param _value
     *            the value from the request's <code>parameters</code> array
     * @throws SQLException
     *             if the value cannot be bound
     */
    private static void bind(final PreparedStatement _stmt, final int _index, final JsonElement _value) throws SQLException {
        if (null == _value || _value.isJsonNull()) {
            _stmt.setNull(_index, Types.NULL);
        } else if (!_value.isJsonPrimitive()) {
            _stmt.setString(_index, _value.toString());
        } else if (_value.getAsJsonPrimitive().isBoolean()) {
            _stmt.setInt(_index, _value.getAsBoolean() ? 1 : 0);
        } else if (_value.getAsJsonPrimitive().isNumber()) {
            final BigDecimal number = _value.getAsBigDecimal();
            if (0 >= number.stripTrailingZeros().scale() && 64 > number.toBigInteger().bitLength()) {
                _stmt.setLong(_index, number.longValueExact());
            } else {
                _stmt.setDouble(_index, number.doubleValue());
            }
        } else {
            _stmt.setString(_index, _value.getAsString());
        }
    }

    private static Pattern compile(final String _regex) {
        return Pattern.compile(_regex, Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    }

    /**
     * Count the columns a prepared statement returns.
     *
     * @param _meta
     *            the statement's metadata, or <code>null</code>
     * @return the number of columns; 0 for a statement that returns no result set
     */
    private static int getColumnCount(final ResultSetMetaData _meta) {
        if (null == _meta) {
            return 0;
        }
        try {
            return _meta.getColumnCount();
        } catch (final SQLException _e) {
            // sqlite-jdbc reports "column 1 out of bounds" instead of returning 0 when there are no columns
            return 0;
        }
    }

    /**
     * Describe the columns of a result set the way a SQL rule would.
     *
     * @param _meta
     *            the JDBC metadata
     * @return the columns
     * @throws SQLException
     *             if the metadata cannot be read
     */
    private static List<MockColumn> toColumns(final ResultSetMetaData _meta) throws SQLException {
        final List<MockColumn> ret = new ArrayList<MockColumn>();
        for (int i = 1; i <= _meta.getColumnCount(); ++i) {
            final JsonObject column = new JsonObject();
            column.addProperty("name", _meta.getColumnLabel(i));
            column.addProperty("type", toTypeName(_meta.getColumnTypeName(i)));
            final int precision = _meta.getPrecision(i);
            if (0 < precision) {
                column.addProperty("precision", precision);
            }
            column.addProperty("scale", Math.max(0, _meta.getScale(i)));
            column.addProperty("nullable", _meta.isNullable(i));
            final String table = _meta.getTableName(i);
            column.addProperty("table", null == table ? "" : table);
            ret.add(MockColumn.fromJson(column));
        }
        return ret;
    }

    /**
     * Build the error Db2 reports for an empty statement.
     *
     * @return a rule that reports the error
     */
    private static SqlRule newEmptyStatementError() {
        return SqlRule.error(".*", "[SQL0104] Token *END was not valid.", "42601", -104);
    }

    /**
     * Map a SQLite failure to the closest Db2 error.
     *
     * @param _e
     *            the SQLite failure
     * @return a rule that reports the error
     */
    private static SqlRule toErrorRule(final SQLException _e) {
        final String message = null == _e.getMessage() ? "SQLite error" : _e.getMessage();
        final String lower = message.toLowerCase(Locale.ROOT);
        final String sqlState;
        final int sqlRc;
        if (lower.contains("no such column")) {
            sqlState = "42703";
            sqlRc = -206;
        } else if (lower.contains("no such") || lower.contains("unknown database")) {
            sqlState = "42704";
            sqlRc = -204;
        } else if (lower.contains("already exists") || lower.contains("already in use")) {
            sqlState = "42710";
            sqlRc = -601;
        } else if (lower.contains("unique constraint") || lower.contains("primary key")) {
            sqlState = "23505";
            sqlRc = -803;
        } else if (lower.contains("not null constraint")) {
            sqlState = "23502";
            sqlRc = -407;
        } else if (lower.contains("syntax error") || lower.contains("incomplete input")) {
            sqlState = "42601";
            sqlRc = -104;
        } else {
            sqlState = "HY000";
            sqlRc = -1;
        }
        return SqlRule.error(".*", String.format("[SQL%04d] %s", Math.abs(sqlRc), message), sqlState, sqlRc);
    }

    /**
     * Map a SQLite declared type to a Db2-style type name.
     *
     * @param _declared
     *            the declared type, possibly with a length, for example <code>VARCHAR(50)</code>
     * @return the upper-case type name without its length
     */
    private static String toTypeName(final String _declared) {
        if (null == _declared || _declared.trim().isEmpty()) {
            return "VARCHAR";
        }
        final int paren = _declared.indexOf('(');
        final String ret = (0 <= paren ? _declared.substring(0, paren) : _declared).trim().toUpperCase(Locale.ROOT);
        switch (ret) {
            case "INT":
                return "INTEGER";
            case "TEXT":
                return "VARCHAR";
            case "REAL":
                return "DOUBLE";
            default:
                return ret;
        }
    }

    /**
     * Convert one column value from a SQLite row to the JSON value sent to the client.
     *
     * @param _value
     *            the value from {@link ResultSet#getObject(int)}
     * @return the JSON value
     */
    private static JsonElement toJson(final Object _value) {
        if (null == _value) {
            return JsonNull.INSTANCE;
        }
        if (_value instanceof Number) {
            return new JsonPrimitive((Number) _value);
        }
        if (_value instanceof Boolean) {
            return new JsonPrimitive((Boolean) _value);
        }
        if (_value instanceof byte[]) {
            final StringBuilder hex = new StringBuilder();
            for (final byte b : (byte[]) _value) {
                hex.append(String.format("%02X", b & 0xff));
            }
            return new JsonPrimitive(hex.toString());
        }
        return new JsonPrimitive(_value.toString());
    }

    /**
     * Discard the database and everything in it.
     */
    @Override
    public synchronized void close() {
        try {
            m_connection.close();
        } catch (final SQLException _e) {
            // The in-memory database is gone either way
        }
    }

    /**
     * Describe a statement without running it, for <code>prepare_sql</code>.
     *
     * @param _sql
     *            the statement text
     * @return a result set rule with the statement's columns and no rows, an update count rule for a statement
     *         that returns no result set, or an error rule if the statement is not valid
     */
    public synchronized SqlRule describe(final String _sql) {
        final String sql = SqlRule.normalize(_sql);
        if (sql.isEmpty()) {
            return newEmptyStatementError();
        }
        if (s_createSchema.matcher(sql).matches() || s_dropSchema.matcher(sql).matches() || s_endTransaction.matcher(sql).matches()) {
            return SqlRule.updateCount(".*", 0);
        }
        try (final PreparedStatement stmt = m_connection.prepareStatement(sql)) {
            final ResultSetMetaData meta = stmt.getMetaData();
            if (0 < getColumnCount(meta)) {
                return SqlRule.resultSet(".*", new MockResultSet(toColumns(meta), new ArrayList<List<JsonElement>>()));
            }
            return SqlRule.updateCount(".*", 0);
        } catch (final SQLException _e) {
            return toErrorRule(_e);
        }
    }

    /**
     * Run a statement.
     *
     * @param _sql
     *            the statement text
     * @param _parameters
     *            the values for the statement's parameter markers, or <code>null</code> for none
     * @return a result set rule holding every row the statement produced, an update count rule, or an error rule
     *         if the statement failed
     */
    public synchronized SqlRule execute(final String _sql, final JsonArray _parameters) {
        final String sql = SqlRule.normalize(_sql);
        if (sql.isEmpty()) {
            return newEmptyStatementError();
        }
        try {
            final Matcher create = s_createSchema.matcher(sql);
            if (create.matches()) {
                executeUpdate("ATTACH DATABASE ':memory:' AS " + create.group(1));
                return SqlRule.updateCount(".*", 0);
            }
            final Matcher drop = s_dropSchema.matcher(sql);
            if (drop.matches()) {
                executeUpdate("DETACH DATABASE " + drop.group(1));
                return SqlRule.updateCount(".*", 0);
            }
            if (s_endTransaction.matcher(sql).matches() && m_connection.getAutoCommit()) {
                try {
                    executeUpdate(sql);
                } catch (final SQLException _e) {
                    if (null == _e.getMessage() || !_e.getMessage().contains("no transaction is active")) {
                        throw _e;
                    }
                }
                return SqlRule.updateCount(".*", 0);
            }
            try (final PreparedStatement stmt = m_connection.prepareStatement(sql)) {
                if (null != _parameters) {
                    for (int i = 0; i < _parameters.size(); ++i) {
                        bind(stmt, i + 1, _parameters.get(i));
                    }
                }
                if (!stmt.execute()) {
                    return SqlRule.updateCount(".*", Math.max(0, stmt.getUpdateCount()));
                }
                try (final ResultSet rs = stmt.getResultSet()) {
                    final List<MockColumn> columns = toColumns(rs.getMetaData());
                    final List<List<JsonElement>> rows = new ArrayList<List<JsonElement>>();
                    while (rs.next()) {
                        final List<JsonElement> row = new ArrayList<JsonElement>(columns.size());
                        for (int i = 1; i <= columns.size(); ++i) {
                            row.add(toJson(rs.getObject(i)));
                        }
                        rows.add(row);
                    }
                    return SqlRule.resultSet(".*", new MockResultSet(columns, rows));
                }
            }
        } catch (final SQLException _e) {
            return toErrorRule(_e);
        }
    }

    /**
     * Run a statement that produces no result set.
     *
     * @param _sql
     *            the statement text
     * @throws SQLException
     *             if the statement fails
     */
    private void executeUpdate(final String _sql) throws SQLException {
        try (final Statement stmt = m_connection.createStatement()) {
            stmt.execute(_sql);
        }
    }
}
