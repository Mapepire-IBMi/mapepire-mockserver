package io.github.mapepire_ibmi.mockserver;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/**
 * The simulated database: an ordered set of rules that decide how SQL statements and CL commands are answered.
 * <p>
 * Rules added by the user are consulted first, in the order they were added, followed by the built-in default
 * rules (unless those were excluded). The first matching rule wins. When no rule matches a SQL statement, a
 * fallback applies: query statements (<code>SELECT</code>, <code>VALUES</code>, <code>WITH</code>) fail with
 * SQL0204 (object not found), other recognized SQL statements succeed with an update count of zero, and
 * anything else fails with SQL0104 (token not valid). CL commands that match no rule succeed with no messages.
 * <p>
 * If {@link #enableSqlite()} has been called, SQL statements that match no rule are run against an in-memory
 * SQLite database (see {@link MockSqliteStore}) instead of getting the fallback behavior. Rules still take
 * priority, so the built-in sample data (such as <code>SYSIBM.SYSDUMMY1</code>) keeps working.
 * <p>
 * Rules may be added while the server is running; the rule lists are safe for concurrent use.
 */
public class MockDatabase {

    /** The classpath location of the built-in default rules. */
    public static final String DEFAULT_RULES_RESOURCE = "/io/github/mapepire_ibmi/mockserver/default-rules.json";

    private static final Set<String> s_nonQueryVerbs = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            "ALTER", "CALL", "COMMENT", "COMMIT", "CREATE", "DECLARE", "DELETE", "DROP", "GRANT", "INSERT", "LABEL",
            "LOCK", "MERGE", "REFRESH", "RELEASE", "RENAME", "REVOKE", "ROLLBACK", "SAVEPOINT", "SET", "TRUNCATE", "UPDATE")));
    private static final Set<String> s_queryVerbs = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList("SELECT", "VALUES", "WITH")));

    private final List<ClRule> m_clRules = new CopyOnWriteArrayList<ClRule>();
    private final List<ClRule> m_defaultClRules = new CopyOnWriteArrayList<ClRule>();
    private final List<SqlRule> m_defaultSqlRules = new CopyOnWriteArrayList<SqlRule>();
    private volatile MockSqliteStore m_sqlite = null;
    private final List<SqlRule> m_sqlRules = new CopyOnWriteArrayList<SqlRule>();
    private volatile MockResultSet m_visualExplain = null;

    /**
     * Create a database containing only the built-in default rules.
     */
    public MockDatabase() {
        this(true);
    }

    /**
     * Create a database, optionally containing the built-in default rules.
     *
     * @param _isIncludingDefaults
     *            whether to load the built-in default rules
     * @throws IllegalStateException
     *             if the built-in rules cannot be read, which indicates a broken build
     */
    public MockDatabase(final boolean _isIncludingDefaults) {
        if (_isIncludingDefaults) {
            loadDefaults();
        }
    }

    /**
     * Create a database from a rules file.
     *
     * @param _file
     *            the JSON rules file
     * @return the database
     * @throws IOException
     *             if the file cannot be read or is not a valid rules file
     */
    public static MockDatabase fromFile(final File _file) throws IOException {
        try (final InputStream in = new FileInputStream(_file); final Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return fromJson(reader);
        }
    }

    /**
     * Create a database from JSON rules. The built-in default rules are included unless the document sets
     * <code>"include_defaults": false</code>, and the SQLite store is enabled if it sets
     * <code>"sqlite": true</code>.
     *
     * @param _reader
     *            the source of the JSON document
     * @return the database
     * @throws IOException
     *             if the document is not a valid rules document
     */
    public static MockDatabase fromJson(final Reader _reader) throws IOException {
        final JsonObject config = parseObject(_reader);
        final MockDatabase ret = new MockDatabase(JsonUtils.getBoolean(config, "include_defaults", true));
        ret.addRules(config);
        if (JsonUtils.getBoolean(config, "sqlite", false)) {
            ret.enableSqlite();
        }
        return ret;
    }

    /**
     * Return the first word of a statement, upper-cased, for classifying statements that match no rule.
     *
     * @param _normalizedSql
     *            the normalized statement
     * @return the first word, or an empty string if the statement does not start with a letter
     */
    private static String getFirstWord(final String _normalizedSql) {
        int end = 0;
        while (end < _normalizedSql.length() && Character.isLetter(_normalizedSql.charAt(end))) {
            ++end;
        }
        return _normalizedSql.substring(0, end).toUpperCase(Locale.ROOT);
    }

    /**
     * Read an optional array of objects from a rules document.
     *
     * @param _config
     *            the rules document
     * @param _key
     *            the field holding the array
     * @return the objects, or an empty list if the field is absent
     * @throws IllegalArgumentException
     *             if the field is present but is not an array of objects
     */
    private static List<JsonObject> getObjects(final JsonObject _config, final String _key) {
        final List<JsonObject> ret = new ArrayList<JsonObject>();
        final JsonElement element = _config.get(_key);
        if (null == element || element.isJsonNull()) {
            return ret;
        }
        if (!element.isJsonArray()) {
            throw new IllegalArgumentException("'" + _key + "' must be an array");
        }
        for (final JsonElement item : element.getAsJsonArray()) {
            if (!item.isJsonObject()) {
                throw new IllegalArgumentException("Each entry in '" + _key + "' must be an object: " + item);
            }
            ret.add(item.getAsJsonObject());
        }
        return ret;
    }

    /**
     * Parse a reader's contents as a JSON object.
     *
     * @param _reader
     *            the source of the JSON document
     * @return the parsed object
     * @throws IOException
     *             if the document is not valid JSON, or is not an object
     */
    private static JsonObject parseObject(final Reader _reader) throws IOException {
        final JsonElement element;
        try {
            element = JsonParser.parseReader(_reader);
        } catch (final JsonParseException _e) {
            throw new IOException("Rules document is not valid JSON: " + _e.getMessage(), _e);
        }
        if (!element.isJsonObject()) {
            throw new IOException("Rules document must be a JSON object");
        }
        return element.getAsJsonObject();
    }

    public void addClRule(final ClRule _rule) {
        m_clRules.add(_rule);
    }

    /**
     * Add the rules from a rules document. The <code>include_defaults</code> field is ignored here; it only
     * applies when a database is created with {@link #fromJson(Reader)}.
     *
     * @param _config
     *            the rules document, with optional <code>sql</code> and <code>cl</code> arrays and an optional
     *            <code>visual_explain</code> object
     * @throws IOException
     *             if any rule is malformed; in that case no rules from the document are added
     */
    public void addRules(final JsonObject _config) throws IOException {
        addRules(_config, m_sqlRules, m_clRules);
    }

    /**
     * Parse a rules document into the given rule lists.
     *
     * @param _config
     *            the rules document
     * @param _sqlRules
     *            the list to append SQL rules to
     * @param _clRules
     *            the list to append CL rules to
     * @throws IOException
     *             if any rule is malformed; in that case neither list is modified
     */
    private void addRules(final JsonObject _config, final List<SqlRule> _sqlRules, final List<ClRule> _clRules) throws IOException {
        final List<SqlRule> sqlRules = new ArrayList<SqlRule>();
        final List<ClRule> clRules = new ArrayList<ClRule>();
        MockResultSet visualExplain = null;
        try {
            for (final JsonObject rule : getObjects(_config, "sql")) {
                sqlRules.add(SqlRule.fromJson(rule));
            }
            for (final JsonObject rule : getObjects(_config, "cl")) {
                clRules.add(ClRule.fromJson(rule));
            }
            final JsonElement ve = _config.get("visual_explain");
            if (null != ve && ve.isJsonObject()) {
                final JsonElement columns = ve.getAsJsonObject().get("columns");
                final JsonElement rows = ve.getAsJsonObject().get("rows");
                if (null == columns || !columns.isJsonArray()) {
                    throw new IllegalArgumentException("'visual_explain' must have a 'columns' array");
                }
                visualExplain = MockResultSet.fromJson(columns.getAsJsonArray(), null != rows && rows.isJsonArray() ? rows.getAsJsonArray() : null);
            }
        } catch (final IllegalArgumentException _e) {
            throw new IOException("Invalid rule: " + _e.getMessage(), _e);
        }
        _sqlRules.addAll(sqlRules);
        _clRules.addAll(clRules);
        if (null != visualExplain) {
            m_visualExplain = visualExplain;
        }
    }

    /**
     * Add a SQL rule. It takes priority over the built-in rules and over the fallback behavior, but not over
     * rules added before it.
     *
     * @param _rule
     *            the rule
     */
    public void addSqlRule(final SqlRule _rule) {
        m_sqlRules.add(_rule);
    }

    /**
     * Remove all user-added rules, leaving the built-in default rules (if any) in place.
     */
    public void clearRules() {
        m_sqlRules.clear();
        m_clRules.clear();
    }

    /**
     * Stop running unmatched statements against SQLite, and discard the SQLite database and everything in it.
     * Unmatched statements get the fallback behavior again. Does nothing if SQLite is not enabled.
     */
    public synchronized void disableSqlite() {
        final MockSqliteStore sqlite = m_sqlite;
        m_sqlite = null;
        if (null != sqlite) {
            sqlite.close();
        }
    }

    /**
     * Run SQL statements that match no rule against a new, empty, in-memory SQLite database, instead of giving
     * them the fallback behavior. The database is never written to disk; it is discarded by
     * {@link #disableSqlite()} or when the JVM exits. Does nothing if SQLite is already enabled.
     *
     * @return this database
     * @throws IllegalStateException
     *             if SQLite cannot be loaded
     */
    public synchronized MockDatabase enableSqlite() {
        if (null == m_sqlite) {
            try {
                m_sqlite = new MockSqliteStore();
            } catch (final SQLException _e) {
                throw new IllegalStateException("SQLite could not be started: " + _e.getMessage(), _e);
            }
        }
        return this;
    }

    /**
     * Find the rule that answers a CL command.
     *
     * @param _command
     *            the command text
     * @return the first matching rule, or a rule under which the command succeeds with no messages
     */
    public ClRule findClRule(final String _command) {
        for (final List<ClRule> rules : Arrays.asList(m_clRules, m_defaultClRules)) {
            for (final ClRule rule : rules) {
                if (rule.matches(_command)) {
                    return rule;
                }
            }
        }
        return new ClRule(".*", true, Collections.<JsonObject> emptyList(), 0);
    }

    /**
     * Find the rule to use when preparing a statement, before any parameter values are known. Rules without a
     * parameter restriction are preferred; if only parameter-restricted rules match, the first of them is used,
     * since it still describes the statement's shape.
     *
     * @param _sql
     *            the statement text
     * @return the rule; never <code>null</code>, because the fallback behavior applies when nothing matches
     */
    public SqlRule findRuleForPrepare(final String _sql) {
        final String sql = SqlRule.normalize(_sql);
        SqlRule firstConstrained = null;
        for (final List<SqlRule> rules : Arrays.asList(m_sqlRules, m_defaultSqlRules)) {
            for (final SqlRule rule : rules) {
                if (rule.matches(sql, null)) {
                    if (!rule.hasParameterConstraint()) {
                        return rule;
                    }
                    if (null == firstConstrained) {
                        firstConstrained = rule;
                    }
                }
            }
        }
        if (null != firstConstrained) {
            return firstConstrained;
        }
        final MockSqliteStore sqlite = m_sqlite;
        return null != sqlite ? sqlite.describe(sql) : getFallbackRule(sql);
    }

    /**
     * Find the rule that answers a statement executed with the given parameter values.
     *
     * @param _sql
     *            the statement text
     * @param _parameters
     *            the parameter values, or <code>null</code> for a statement run without parameters
     * @return the rule; never <code>null</code>, because the fallback behavior applies when nothing matches
     */
    public SqlRule findSqlRule(final String _sql, final JsonArray _parameters) {
        final String sql = SqlRule.normalize(_sql);
        final JsonArray parameters = null == _parameters ? new JsonArray() : _parameters;
        for (final List<SqlRule> rules : Arrays.asList(m_sqlRules, m_defaultSqlRules)) {
            for (final SqlRule rule : rules) {
                if (rule.matches(sql, parameters)) {
                    return rule;
                }
            }
        }
        final MockSqliteStore sqlite = m_sqlite;
        return null != sqlite ? sqlite.execute(sql, parameters) : getFallbackRule(sql);
    }

    /**
     * Build the rule that applies to a statement no rule matched.
     *
     * @param _normalizedSql
     *            the normalized statement
     * @return the fallback rule
     */
    private SqlRule getFallbackRule(final String _normalizedSql) {
        final String verb = getFirstWord(_normalizedSql);
        if (s_queryVerbs.contains(verb)) {
            return SqlRule.error(".*", "[SQL0204] Object not found. The mock server has no rule matching this statement.", "42704", -204);
        }
        if (s_nonQueryVerbs.contains(verb)) {
            return SqlRule.updateCount(".*", 0);
        }
        final String token = verb.isEmpty() ? (_normalizedSql.isEmpty() ? "*END" : _normalizedSql.substring(0, 1)) : verb;
        return SqlRule.error(".*", "[SQL0104] Token " + token + " was not valid.", "42601", -104);
    }

    public MockResultSet getVisualExplain() {
        return m_visualExplain;
    }

    public boolean isSqliteEnabled() {
        return null != m_sqlite;
    }

    /**
     * Load the built-in default rules from the classpath.
     *
     * @throws IllegalStateException
     *             if the resource is missing or malformed
     */
    private void loadDefaults() {
        try (final InputStream in = MockDatabase.class.getResourceAsStream(DEFAULT_RULES_RESOURCE)) {
            if (null == in) {
                throw new IllegalStateException("Built-in rules resource not found: " + DEFAULT_RULES_RESOURCE);
            }
            try (final Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                addRules(parseObject(reader), m_defaultSqlRules, m_defaultClRules);
            }
        } catch (final IOException _e) {
            throw new IllegalStateException("Built-in rules could not be loaded: " + _e.getMessage(), _e);
        }
    }

    public void setVisualExplain(final MockResultSet _visualExplain) {
        m_visualExplain = _visualExplain;
    }
}
