package io.github.mapepire_ibmi.mockserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tests rule loading, rule matching, and the fallback behavior for statements that match no rule.
 */
class MockDatabaseTest {

    @TempDir
    File m_tempDir;

    private static JsonArray array(final String _json) {
        return JsonParser.parseString(_json).getAsJsonArray();
    }

    private static MockDatabase fromJson(final String _json) throws IOException {
        return MockDatabase.fromJson(new StringReader(_json.replace('\'', '"')));
    }

    @Test
    void testClFallbackWithoutDefaults() {
        final ClRule rule = new MockDatabase(false).findClRule("ANYTHING");
        assertTrue(rule.isSuccess());
        assertEquals(0, rule.buildMessages(0).size());
    }

    /**
     * User rules are consulted before the built-in rules, and can be removed again.
     */
    @Test
    void testCustomRulesTakePriority() {
        final MockDatabase db = new MockDatabase();
        assertEquals(SqlRule.Kind.RESULT_SET, db.findSqlRule("SELECT * FROM SAMPLE.DEPARTMENT", null).getKind());
        db.addSqlRule(SqlRule.error("select \\* from sample\\.department", "[SQL0551] Not authorized.", "42501", -551));
        final SqlRule rule = db.findSqlRule("SELECT * FROM SAMPLE.DEPARTMENT", null);
        assertEquals(SqlRule.Kind.ERROR, rule.getKind());
        assertEquals("42501", rule.newException().getSqlState());
        db.clearRules();
        assertEquals(SqlRule.Kind.RESULT_SET, db.findSqlRule("SELECT * FROM SAMPLE.DEPARTMENT", null).getKind());
    }

    /**
     * Statements that match no rule are classified by their first word.
     */
    @Test
    void testFallbacks() {
        final MockDatabase db = new MockDatabase(false);
        assertEquals("42704", db.findSqlRule("SELECT * FROM SAMPLE.DEPARTMENT", null).newException().getSqlState());
        assertEquals("42704", db.findSqlRule("  values 1", null).newException().getSqlState());
        assertEquals("42704", db.findSqlRule("WITH X AS (SELECT 1 FROM SYSIBM.SYSDUMMY1) SELECT * FROM X", null).newException().getSqlState());
        assertEquals(SqlRule.Kind.UPDATE, db.findSqlRule("insert into t values(1)", null).getKind());
        assertEquals(0, db.findSqlRule("COMMIT", null).getUpdateCount());
        final MockSqlException bad = db.findSqlRule("GIBBERISH", null).newException();
        assertEquals("42601", bad.getSqlState());
        assertEquals("[SQL0104] Token GIBBERISH was not valid.", bad.getMessage());
        assertEquals("42601", db.findSqlRule("", null).newException().getSqlState());
        assertNull(db.getVisualExplain());
    }

    /**
     * The example rules file shipped with the project loads, and its rules behave as documented.
     *
     * @throws IOException
     *             if the example file is missing or invalid
     */
    @Test
    void testExampleRules() throws IOException {
        final MockServerConfig config = new MockServerConfig().setDatabase(MockDatabase.fromFile(new File("examples/rules.json")));
        final MockSession session = new MockSession(config, new MockTracer("example", null));
        final String call = session.handle("{\"id\":\"c1\",\"type\":\"prepare_sql_execute\",\"sql\":\"CALL MYAPP.GET_TOTAL(?)\",\"parameters\":[null]}");
        final JsonObject reply = JsonParser.parseString(call).getAsJsonObject();
        assertTrue(reply.get("success").getAsBoolean(), call);
        assertEquals("TOTAL", reply.getAsJsonArray("output_parms").get(0).getAsJsonObject().get("name").getAsString());
        assertEquals(SqlRule.Kind.RESULT_SET, config.getDatabase().findSqlRule("SELECT * FROM SAMPLE.DEPARTMENT", null).getKind());
        assertEquals(5000, config.getDatabase().findSqlRule("select * from myapp.slow_view", null).getDelayMs());
        assertFalse(config.getDatabase().findClRule("DLTOBJ OBJ(X) OBJTYPE(*FILE)").isSuccess());
    }

    /**
     * Rules files can hold result set, update, and error rules, and can exclude the built-in rules.
     *
     * @throws IOException
     *             if the file cannot be written or read
     */
    @Test
    void testFromFile() throws IOException {
        final File file = new File(m_tempDir, "rules.json");
        //@formatter:off
        final String json = String.join("\n",
                "{",
                "  'include_defaults': false,",
                "  'sql': [",
                "    { 'pattern': 'select name from people', 'columns': [ { 'name': 'NAME' } ], 'rows': [ ['ALICE'], { 'NAME': 'BOB' }, {} ] },",
                "    { 'pattern': 'delete from people', 'update_count': 3 },",
                "    { 'pattern': 'drop table people', 'error': { 'message': 'Not allowed', 'sql_state': '42501', 'sql_rc': -551 } }",
                "  ],",
                "  'cl': [ { 'pattern': 'dspjob', 'messages': [ { 'id': 'CPF1234', 'text': 'Hello' } ] } ]",
                "}").replace('\'', '"');
        //@formatter:on
        Files.write(file.toPath(), json.getBytes(StandardCharsets.UTF_8));
        final MockDatabase db = MockDatabase.fromFile(file);

        final SqlRule query = db.findSqlRule("SELECT NAME FROM PEOPLE", null);
        assertEquals(SqlRule.Kind.RESULT_SET, query.getKind());
        final MockResultSet rs = query.getResultSet();
        assertEquals(3, rs.getRows().size());
        assertEquals("ALICE", rs.getRows().get(0).get(0).getAsString());
        assertEquals("BOB", rs.getRows().get(1).get(0).getAsString());
        assertTrue(rs.getRows().get(2).get(0).isJsonNull());
        assertEquals("VARCHAR", rs.getColumns().get(0).toMetadata().get("type"));

        assertEquals(3, db.findSqlRule("delete from people", null).getUpdateCount());
        assertEquals(-551, db.findSqlRule("drop table people", null).newException().getSqlRc());
        assertEquals("CPF1234", db.findClRule("DSPJOB").buildMessages(0).get(0).getAsJsonObject().get("id").getAsString());

        // Built-in rules were excluded, so this falls back to "object not found"
        assertEquals(SqlRule.Kind.ERROR, db.findSqlRule("SELECT * FROM SAMPLE.DEPARTMENT", null).getKind());
    }

    /**
     * Malformed rules are rejected with a description of the problem.
     */
    @Test
    void testInvalidRules() {
        assertThrows(IOException.class, () -> fromJson("not json {"));
        assertThrows(IOException.class, () -> fromJson("['an', 'array']"));
        assertThrows(IOException.class, () -> fromJson("{'sql': [ { 'update_count': 1 } ]}"));
        assertThrows(IOException.class, () -> fromJson("{'sql': 'not an array'}"));
        assertThrows(IOException.class, () -> fromJson("{'sql': [ { 'pattern': 'x', 'columns': [ { 'type': 'INTEGER' } ] } ]}"));
        final IOException e = assertThrows(IOException.class, () -> fromJson("{'sql': [ { 'pattern': 'x', 'columns': [ { 'name': 'A' } ], 'rows': [ [1, 2] ] } ]}"));
        assertTrue(e.getMessage().contains("2 values but there are 1 columns"), e.getMessage());
        assertThrows(IOException.class, () -> fromJson("{'cl': [ { 'messages': [] } ]}"));
        assertThrows(IOException.class, () -> fromJson("{'visual_explain': { 'rows': [] }}"));
    }

    /**
     * Matching ignores case, surrounding whitespace, and trailing semicolons, but must cover the whole
     * statement.
     */
    @Test
    void testMatching() {
        final SqlRule rule = SqlRule.updateCount("update t set x = 1", 7);
        assertTrue(rule.matches(SqlRule.normalize("  UPDATE T SET X = 1 ;; "), null));
        assertFalse(rule.matches(SqlRule.normalize("UPDATE T SET X = 1 WHERE Y = 2"), null));
        assertTrue(SqlRule.updateCount("select\\s+1", 0).matches(SqlRule.normalize("select\n  1"), null));
    }

    /**
     * Parameter-restricted rules only match executions with equal parameters, and preparing prefers an
     * unrestricted rule.
     */
    @Test
    void testParameterRestrictedRules() {
        final MockDatabase db = new MockDatabase(false);
        final SqlRule forOne = SqlRule.updateCount("delete from t where id = \\?", 1).withParameters(array("[1]"));
        final SqlRule forAny = SqlRule.updateCount("delete from t where id = \\?", 0);
        db.addSqlRule(forOne);
        db.addSqlRule(forAny);
        assertSame(forOne, db.findSqlRule("DELETE FROM T WHERE ID = ?", array("[1]")));
        assertSame(forAny, db.findSqlRule("DELETE FROM T WHERE ID = ?", array("[2]")));
        assertSame(forAny, db.findSqlRule("DELETE FROM T WHERE ID = ?", null));
        assertSame(forAny, db.findRuleForPrepare("DELETE FROM T WHERE ID = ?"));

        final MockDatabase onlyRestricted = new MockDatabase(false);
        onlyRestricted.addSqlRule(forOne);
        assertSame(forOne, onlyRestricted.findRuleForPrepare("DELETE FROM T WHERE ID = ?"));
    }

    @Test
    void testParameterMarkerCounting() {
        assertEquals(0, MockSession.countParameterMarkers("SELECT 'what?' FROM \"WHY?\""));
        assertEquals(2, MockSession.countParameterMarkers("SELECT * FROM T WHERE A = ? AND B = '?' AND C = ?"));
    }

    /**
     * Result set rows given as objects are reordered to match the columns, and formatted either way.
     */
    @Test
    void testRowFormatting() {
        final JsonArray columns = array("[{\"name\":\"A\",\"type\":\"INTEGER\"},{\"name\":\"B\"}]");
        final MockResultSet rs = MockResultSet.fromJson(columns, array("[{\"B\":\"x\",\"A\":1}]"));
        assertEquals(Arrays.asList(JsonParser.parseString("1"), JsonParser.parseString("\"x\"")), rs.formatRow(0, true));
        final Object row = rs.formatRow(0, false);
        assertEquals("{A=1, B=\"x\"}", row.toString());
        final MockCursor cursor = new MockCursor(rs);
        assertFalse(cursor.isDone());
        assertEquals(1, cursor.nextBlock(0, false).size());
        assertTrue(cursor.isDone());
        assertTrue(cursor.nextBlock(10, false).isEmpty());
        final JsonElement precision = JsonParser.parseString(String.valueOf(rs.getColumns().get(0).toMetadata().get("precision")));
        assertEquals(10, precision.getAsInt());
    }
}
