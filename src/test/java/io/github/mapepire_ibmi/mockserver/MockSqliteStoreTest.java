package io.github.mapepire_ibmi.mockserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Exercises the SQLite store through the protocol engine, with no network involved.
 */
class MockSqliteStoreTest {

    private MockServerConfig m_config;
    private MockSession m_session;

    private JsonObject send(final String _request) {
        final String reply = m_session.handle(_request.replace('\'', '"'));
        assertNotNull(reply);
        return JsonParser.parseString(reply).getAsJsonObject();
    }

    @BeforeEach
    void setUp() {
        m_config = new MockServerConfig();
        m_config.getDatabase().enableSqlite();
        m_session = new MockSession(m_config, new MockTracer("test", null));
    }

    @AfterEach
    void tearDown() {
        m_config.getDatabase().disableSqlite();
    }

    /**
     * Disabling the store brings back the fallback behavior, and enabling it again starts with an empty database.
     */
    @Test
    void testDisableDiscardsData() {
        assertTrue(send("{'id':'c','type':'sql','sql':'CREATE TABLE T1 (A INTEGER)'}").get("success").getAsBoolean());
        m_config.getDatabase().disableSqlite();
        assertFalse(m_config.getDatabase().isSqliteEnabled());
        final JsonObject fallback = send("{'id':'s1','type':'sql','sql':'SELECT * FROM T1'}");
        assertEquals("[SQL0204] Object not found. The mock server has no rule matching this statement.", fallback.get("error").getAsString());

        m_config.getDatabase().enableSqlite();
        final JsonObject gone = send("{'id':'s2','type':'sql','sql':'SELECT * FROM T1'}");
        assertFalse(gone.get("success").getAsBoolean());
        assertEquals("42704", gone.get("sql_state").getAsString());
        assertTrue(gone.get("error").getAsString().contains("no such table"), gone.toString());
    }

    /**
     * SQLite errors are reported with the closest Db2 SQLSTATE and SQL code.
     */
    @Test
    void testErrorMapping() {
        final JsonObject syntax = send("{'id':'e1','type':'sql','sql':'SELEC 1'}");
        assertEquals("42601", syntax.get("sql_state").getAsString());
        assertEquals(-104, syntax.get("sql_rc").getAsInt());

        send("{'id':'c','type':'sql','sql':'CREATE TABLE T2 (A INTEGER PRIMARY KEY)'}");
        send("{'id':'i1','type':'sql','sql':'INSERT INTO T2 VALUES (1)'}");
        final JsonObject duplicate = send("{'id':'i2','type':'sql','sql':'INSERT INTO T2 VALUES (1)'}");
        assertEquals("23505", duplicate.get("sql_state").getAsString());
        assertEquals(-803, duplicate.get("sql_rc").getAsInt());

        final JsonObject column = send("{'id':'s','type':'sql','sql':'SELECT NOPE FROM T2'}");
        assertEquals("42703", column.get("sql_state").getAsString());
    }

    /**
     * A prepared query reports its columns before it runs, and returns the matching rows when executed.
     */
    @Test
    void testPreparedQuery() {
        send("{'id':'c','type':'sql','sql':'CREATE TABLE T3 (ID INTEGER, NAME VARCHAR(20))'}");
        final JsonObject batch = send("{'id':'b','type':'prepare_sql_execute','sql':'INSERT INTO T3 VALUES (?, ?)','parameters':[[1,'one'],[2,'two'],[3,null]]}");
        assertTrue(batch.get("success").getAsBoolean(), batch.toString());
        assertEquals(3, batch.get("update_count").getAsLong());

        final JsonObject prepared = send("{'id':'p','type':'prepare_sql','sql':'SELECT ID, NAME FROM T3 WHERE ID >= ? ORDER BY ID'}");
        assertTrue(prepared.get("success").getAsBoolean(), prepared.toString());
        assertEquals(1, prepared.get("parameter_count").getAsInt());
        final JsonArray columns = prepared.getAsJsonObject("metadata").getAsJsonArray("columns");
        assertEquals("ID", columns.get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("INTEGER", columns.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("VARCHAR", columns.get(1).getAsJsonObject().get("type").getAsString());
        assertEquals(20, columns.get(1).getAsJsonObject().get("precision").getAsInt());

        final JsonObject executed = send("{'id':'x','type':'execute','cont_id':'p','parameters':[2],'terse':true}");
        assertTrue(executed.get("success").getAsBoolean(), executed.toString());
        final JsonArray data = executed.getAsJsonArray("data");
        assertEquals(2, data.size());
        assertEquals(2, data.get(0).getAsJsonArray().get(0).getAsInt());
        assertEquals("two", data.get(0).getAsJsonArray().get(1).getAsString());
        assertTrue(data.get(1).getAsJsonArray().get(1).isJsonNull());
    }

    /**
     * A rules file can turn the store on.
     *
     * @throws IOException
     *             if the rules cannot be parsed
     */
    @Test
    void testRulesFileEnablesSqlite() throws IOException {
        final MockDatabase database = MockDatabase.fromJson(new StringReader("{\"sqlite\": true}"));
        try {
            assertTrue(database.isSqliteEnabled());
            assertEquals(SqlRule.Kind.RESULT_SET, database.findSqlRule("SELECT 1 AS ONE", null).getKind());
        } finally {
            database.disableSqlite();
        }
        assertFalse(new MockDatabase().isSqliteEnabled());
    }

    /**
     * Rules take priority over SQLite, so the built-in sample data is still there.
     */
    @Test
    void testRulesTakePriority() {
        final JsonObject dummy = send("{'id':'d','type':'sql','sql':'SELECT * FROM SYSIBM.SYSDUMMY1'}");
        assertTrue(dummy.get("success").getAsBoolean());
        assertEquals("Y", dummy.getAsJsonArray("data").get(0).getAsJsonObject().get("IBMREQD").getAsString());

        final JsonObject department = send("{'id':'s','type':'sql','sql':'SELECT * FROM SAMPLE.DEPARTMENT'}");
        assertEquals(14, department.getAsJsonArray("data").size());
    }

    /**
     * Schemas can be created, used, and dropped, and dropping one discards its tables.
     */
    @Test
    void testSchemas() {
        assertTrue(send("{'id':'1','type':'sql','sql':'CREATE SCHEMA MYLIB'}").get("success").getAsBoolean());
        assertTrue(send("{'id':'2','type':'sql','sql':'CREATE TABLE MYLIB.T (A INTEGER)'}").get("success").getAsBoolean());
        assertEquals(1, send("{'id':'3','type':'sql','sql':'INSERT INTO mylib.t VALUES (42)'}").get("update_count").getAsLong());
        assertEquals(42, send("{'id':'4','type':'sql','sql':'SELECT A FROM MyLib.T'}").getAsJsonArray("data").get(0).getAsJsonObject().get("A").getAsInt());

        final JsonObject again = send("{'id':'5','type':'sql','sql':'CREATE SCHEMA MYLIB'}");
        assertFalse(again.get("success").getAsBoolean());
        assertEquals("42710", again.get("sql_state").getAsString());

        assertTrue(send("{'id':'6','type':'sql','sql':'DROP SCHEMA MYLIB CASCADE'}").get("success").getAsBoolean());
        assertEquals("42704", send("{'id':'7','type':'sql','sql':'SELECT A FROM MYLIB.T'}").get("sql_state").getAsString());
        assertTrue(send("{'id':'8','type':'sql','sql':'COMMIT'}").get("success").getAsBoolean());
    }
}
