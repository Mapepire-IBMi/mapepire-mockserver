package io.github.mapepire_ibmi.mockserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Exercises the protocol engine directly, with no network involved. Each test sends request text to a
 * {@link MockSession} and checks the reply against the Mapepire protocol.
 */
class MockSessionTest {

    private static final String DEPARTMENT_BY_ADMIN = "SELECT * FROM SAMPLE.DEPARTMENT WHERE ADMRDEPT = ?";

    private MockServerConfig m_config;
    private MockSession m_session;

    /**
     * Turn a request written with single quotes (for readability in Java source) into JSON and send it.
     *
     * @param _request
     *            the request, with <code>'</code> in place of <code>"</code>
     * @return the parsed reply
     */
    private JsonObject send(final String _request) {
        final String reply = m_session.handle(_request.replace('\'', '"'));
        assertNotNull(reply);
        return JsonParser.parseString(reply).getAsJsonObject();
    }

    @BeforeEach
    void setUp() {
        m_config = new MockServerConfig();
        m_session = new MockSession(m_config, new MockTracer("test", null));
    }

    @Test
    void testBlankRequestHasNoReply() {
        assertNull(m_session.handle("   \n"));
    }

    /**
     * A batch execution adds up the update counts of every parameter set.
     */
    @Test
    void testBatchExecution() {
        final JsonObject reply = send("{'id':'b1','type':'prepare_sql_execute','sql':'INSERT INTO SAMPLE.DEPARTMENT VALUES (?, ?, NULL, ?, NULL)','parameters':[['X01','ONE','A00'],['X02','TWO','A00'],['X03','THREE','A00']]}");
        assertTrue(reply.get("success").getAsBoolean(), reply.toString());
        assertEquals(3, reply.get("batch_added").getAsInt());
        assertEquals(3, reply.get("update_count").getAsLong());
        assertFalse(reply.get("has_results").getAsBoolean());
        assertTrue(reply.get("is_done").getAsBoolean());
    }

    /**
     * The <code>cl</code> request reports the command's messages and fails with the escape message's text.
     */
    @Test
    void testClCommand() {
        final JsonObject ok = send("{'id':'c1','type':'cl','cmd':'CRTLIB LIB(MYLIB)'}");
        assertTrue(ok.get("success").getAsBoolean());
        final JsonObject message = ok.getAsJsonArray("data").get(0).getAsJsonObject();
        assertEquals("CPC2102", message.get("id").getAsString());
        assertEquals("COMPLETION", message.get("type").getAsString());
        assertTrue(message.has("time"));
        assertTrue(message.has("sendingProgram"));

        final JsonObject failed = send("{'id':'c2','type':'cl','cmd':'DLTLIB LIB(NOSUCHLIB)'}");
        assertFalse(failed.get("success").getAsBoolean());
        assertEquals("CL command execution failed: [CPF2110] Library NOSUCHLIB not found.", failed.get("error").getAsString());
        assertEquals("38501", failed.get("sql_state").getAsString());
        assertEquals("CPF2110", failed.getAsJsonArray("data").get(0).getAsJsonObject().get("id").getAsString());
    }

    /**
     * A connect request starts a new job and discards any statements from the previous one.
     */
    @Test
    void testConnect() {
        send("{'id':'q1','type':'sql','sql':'SELECT * FROM SAMPLE.DEPARTMENT','rows':1}");
        final JsonObject reply = send("{'id':'1','type':'connect','technique':'TCP','application':'unit test'}");
        assertTrue(reply.get("success").getAsBoolean());
        assertEquals("1", reply.get("id").getAsString());
        assertTrue(reply.get("job").getAsString().matches("\\d{6}/QUSER/QZDASOINIT"));
        assertTrue(reply.has("execution_time"));
        assertEquals(reply.get("job").getAsString(), m_session.getJobName());

        final JsonObject more = send("{'id':'m1','type':'sqlmore','cont_id':'q1'}");
        assertEquals("invalid correlation ID", more.get("error").getAsString());
    }

    @Test
    void testConnectRejectsUnknownTechnique() {
        final JsonObject reply = send("{'id':'1','type':'connect','technique':'carrier-pigeon'}");
        assertFalse(reply.get("success").getAsBoolean());
        assertEquals("Invalid connection technique specified", reply.get("error").getAsString());
    }

    /**
     * Requests that refer to a statement need a valid <code>cont_id</code>.
     */
    @Test
    void testContinuationErrors() {
        assertEquals("Correlation ID not specified", send("{'id':'m1','type':'sqlmore'}").get("error").getAsString());
        assertEquals("invalid correlation ID", send("{'id':'m2','type':'sqlmore','cont_id':'nope'}").get("error").getAsString());
        assertEquals("Correlation ID not specified", send("{'id':'c1','type':'sqlclose'}").get("error").getAsString());
        send("{'id':'q1','type':'sql','sql':'SELECT * FROM SYSIBM.SYSDUMMY1'}");
        // execute only applies to prepared statements, not to plain sql requests
        assertEquals("invalid correlation ID", send("{'id':'e1','type':'execute','cont_id':'q1','parameters':[]}").get("error").getAsString());
    }

    /**
     * Visual Explain data is always returned; the statement's own rows only when <code>run</code> is set.
     */
    @Test
    void testDove() {
        final JsonObject explainOnly = send("{'id':'d1','type':'dove','sql':'SELECT * FROM SAMPLE.DEPARTMENT'}");
        assertTrue(explainOnly.get("success").getAsBoolean(), explainOnly.toString());
        assertEquals(2, explainOnly.getAsJsonArray("vedata").size());
        assertEquals(5, explainOnly.getAsJsonObject("vemetadata").get("column_count").getAsInt());
        assertFalse(explainOnly.has("data"));
        assertTrue(explainOnly.get("is_done").getAsBoolean());

        final JsonObject run = send("{'id':'d2','type':'dove','sql':'SELECT * FROM SAMPLE.DEPARTMENT','run':true,'rows':3}");
        assertEquals(3, run.getAsJsonArray("data").size());
        assertFalse(run.get("is_done").getAsBoolean());

        final JsonObject failed = send("{'id':'d3','type':'dove','sql':'SELECT * FROM NOPE.NOPE'}");
        assertEquals("42704", failed.get("sql_state").getAsString());
    }

    /**
     * An error reply carries <code>error</code>, <code>sql_state</code> and <code>sql_rc</code>, and the failed
     * statement can still be closed.
     */
    @Test
    void testErrorReply() {
        final JsonObject reply = send("{'id':'q1','type':'sql','sql':'SELECT * FROM NOPE.NOPE'}");
        assertFalse(reply.get("success").getAsBoolean());
        assertTrue(reply.get("error").getAsString().startsWith("[SQL0204]"));
        assertEquals("42704", reply.get("sql_state").getAsString());
        assertEquals(-204, reply.get("sql_rc").getAsInt());
        assertTrue(reply.has("execution_time"));
        assertTrue(send("{'id':'c1','type':'sqlclose','cont_id':'q1'}").get("success").getAsBoolean());

        final JsonObject garbage = send("{'id':'q2','type':'sql','sql':'FROBNICATE THE DATABASE'}");
        assertEquals("42601", garbage.get("sql_state").getAsString());
        assertEquals(-104, garbage.get("sql_rc").getAsInt());
    }

    @Test
    void testExit() {
        assertFalse(m_session.isEnded());
        assertTrue(send("{'id':'x','type':'exit'}").get("success").getAsBoolean());
        assertTrue(m_session.isEnded());
    }

    /**
     * The documentation requests return placeholder documents that name the command.
     */
    @Test
    void testGetClDocAndCmdXml() {
        final JsonObject doc = send("{'id':'1','type':'getcldoc','path':'QSYS/CRTLIB'}");
        assertTrue(doc.get("html").getAsString().contains("QSYS/CRTLIB"));
        assertTrue(doc.get("uim").getAsString().contains("QSYS/CRTLIB"));
        final JsonObject xml = send("{'id':'2','type':'getcmdxml','path':'QSYS/CRTLIB'}");
        assertTrue(xml.get("xml").getAsString().contains("CmdName=\"QSYS/CRTLIB\""));
        assertFalse(send("{'id':'3','type':'getcldoc'}").get("success").getAsBoolean());
    }

    /**
     * Requests that are not JSON objects, or lack <code>id</code> or <code>type</code>, get generated IDs.
     */
    @Test
    void testMalformedRequests() {
        final JsonObject unparseable = JsonParser.parseString(m_session.handle("this is not json {")).getAsJsonObject();
        assertTrue(unparseable.get("id").getAsString().startsWith("unparseable_"));
        assertEquals("this is not json {", unparseable.get("unparseable").getAsString());
        assertFalse(unparseable.get("success").getAsBoolean());

        final JsonObject incomplete = send("{'type':'ping'}");
        assertTrue(incomplete.get("id").getAsString().startsWith("incomplete_"));
        assertTrue(incomplete.has("incomplete"));
        assertFalse(incomplete.get("success").getAsBoolean());

        final JsonObject unknown = send("{'id':'u1','type':'teleport'}");
        assertEquals("u1", unknown.get("id").getAsString());
        assertEquals("Unknown request type: teleport", unknown.get("error").getAsString());
    }

    /**
     * Null cell values are serialized explicitly rather than omitted.
     */
    @Test
    void testNullsAreSerialized() {
        final String reply = m_session.handle("{\"id\":\"q1\",\"type\":\"sql\",\"sql\":\"SELECT * FROM SAMPLE.DEPARTMENT\",\"rows\":1}");
        assertTrue(reply.contains("\"LOCATION\":null"), reply);
    }

    /**
     * The number of parameter values must match the number of parameter markers.
     */
    @Test
    void testParameterCountMismatch() {
        final JsonObject tooFew = send("{'id':'p1','type':'prepare_sql_execute','sql':'" + DEPARTMENT_BY_ADMIN + "','parameters':[]}");
        assertEquals("07001", tooFew.get("sql_state").getAsString());
        final JsonObject tooMany = send("{'id':'p2','type':'prepare_sql_execute','sql':'" + DEPARTMENT_BY_ADMIN + "','parameters':['A00','B01']}");
        assertEquals("07009", tooMany.get("sql_state").getAsString());
    }

    @Test
    void testPing() {
        final JsonObject reply = send("{'id':'hc1','type':'ping'}");
        assertTrue(reply.get("alive").getAsBoolean());
        assertTrue(reply.get("db_alive").getAsBoolean());
        assertTrue(reply.get("success").getAsBoolean());
    }

    /**
     * A prepared statement describes its parameters, and each execution chooses rows by parameter value.
     */
    @Test
    void testPrepareAndExecute() {
        final JsonObject prepared = send("{'id':'p1','type':'prepare_sql','sql':'" + DEPARTMENT_BY_ADMIN + "'}");
        assertTrue(prepared.get("success").getAsBoolean(), prepared.toString());
        assertEquals(1, prepared.get("parameter_count").getAsInt());
        final JsonObject metadata = prepared.getAsJsonObject("metadata");
        assertEquals(5, metadata.get("column_count").getAsInt());
        assertEquals("IN", metadata.getAsJsonArray("parameters").get(0).getAsJsonObject().get("mode").getAsString());
        assertFalse(prepared.has("data"));

        final JsonObject e01 = send("{'id':'e1','type':'execute','cont_id':'p1','parameters':['E01']}");
        assertEquals(7, e01.getAsJsonArray("data").size());
        assertTrue(e01.get("is_done").getAsBoolean());
        assertTrue(e01.has("output_parms"));

        final JsonObject a00 = send("{'id':'e2','type':'execute','cont_id':'p1','parameters':['A00'],'rows':2}");
        assertEquals(2, a00.getAsJsonArray("data").size());
        assertFalse(a00.get("is_done").getAsBoolean());
        final JsonObject more = send("{'id':'m1','type':'sqlmore','cont_id':'p1','rows':10}");
        assertEquals(3, more.getAsJsonArray("data").size());
        assertTrue(more.get("is_done").getAsBoolean());

        final JsonObject none = send("{'id':'e3','type':'execute','cont_id':'p1','parameters':['ZZZ']}");
        assertEquals(0, none.getAsJsonArray("data").size());
        assertTrue(none.get("is_done").getAsBoolean());
    }

    /**
     * <code>prepare_sql_execute</code> combines the prepare and execute replies.
     */
    @Test
    void testPrepareSqlExecute() {
        final JsonObject reply = send("{'id':'pse1','type':'prepare_sql_execute','sql':'" + DEPARTMENT_BY_ADMIN + "','parameters':['A00'],'terse':true}");
        assertTrue(reply.get("success").getAsBoolean(), reply.toString());
        assertEquals(1, reply.get("parameter_count").getAsInt());
        assertTrue(reply.get("has_results").getAsBoolean());
        final JsonArray data = reply.getAsJsonArray("data");
        assertEquals(5, data.size());
        assertEquals("A00", data.get(0).getAsJsonArray().get(0).getAsString());
    }

    /**
     * Credentials can appear in JDBC properties, so they are never written to the trace.
     */
    @Test
    void testPropsAreNotTraced() {
        send("{'id':'1','type':'setconfig','tracelevel':'ON'}");
        send("{'id':'2','type':'connect','props':'naming=system;password=hunter2'}");
        final String trace = send("{'id':'3','type':'gettracedata'}").get("tracedata").getAsString();
        assertTrue(trace.contains("connect"), trace);
        assertFalse(trace.contains("hunter2"), trace);
    }

    /**
     * The <code>rccl</code> request reports failures without SQL error fields.
     */
    @Test
    void testRcclCommand() {
        final JsonObject failed = send("{'id':'r1','type':'rccl','cmd':'BADCMD'}");
        assertFalse(failed.get("success").getAsBoolean());
        assertEquals("CL command execution failed: Unknown error", failed.get("error").getAsString());
        assertFalse(failed.has("sql_state"));
        assertEquals(2, failed.getAsJsonArray("data").size());
    }

    /**
     * Trace settings are validated, applied, and reported back.
     */
    @Test
    void testSetConfigAndTraceData() {
        final JsonObject config = send("{'id':'1','type':'setconfig','tracedest':'in_mem','tracelevel':'datastream','jtopentracelevel':'ERRORS'}");
        assertTrue(config.get("success").getAsBoolean(), config.toString());
        assertEquals("IN_MEM", config.get("tracedest").getAsString());
        assertEquals("DATASTREAM", config.get("tracelevel").getAsString());
        assertEquals("IN_MEM", config.get("jtopentracedest").getAsString());
        assertEquals("ERRORS", config.get("jtopentracelevel").getAsString());

        send("{'id':'hello','type':'ping'}");
        final JsonObject data = send("{'id':'2','type':'gettracedata'}");
        assertTrue(data.get("tracedata").getAsString().contains("\"id\":\"hello\""));
        assertEquals("", data.get("jtopentracedata").getAsString());

        final JsonObject bad = send("{'id':'3','type':'setconfig','tracedest':'FILE','tracelevel':'LOUD'}");
        assertEquals("Invalid trace level specified: LOUD", bad.get("error").getAsString());
        // Nothing is applied when any value is invalid
        assertEquals("IN_MEM", send("{'id':'4','type':'setconfig'}").get("tracedest").getAsString());

        assertTrue(send("{'id':'5','type':'setconfig','tracedest':'FILE'}").get("tracedest").getAsString().startsWith("/"));
    }

    /**
     * Rows come back in blocks of the requested size, and <code>sqlmore</code> continues where the previous
     * block ended.
     */
    @Test
    void testSqlAndSqlMore() {
        final JsonObject first = send("{'id':'q1','type':'sql','sql':'select * from sample.department','rows':5}");
        assertTrue(first.get("success").getAsBoolean(), first.toString());
        assertTrue(first.get("has_results").getAsBoolean());
        assertEquals(-1, first.get("update_count").getAsInt());
        assertFalse(first.get("is_done").getAsBoolean());
        final JsonObject metadata = first.getAsJsonObject("metadata");
        assertEquals(5, metadata.get("column_count").getAsInt());
        assertEquals(m_session.getJobName(), metadata.get("job").getAsString());
        assertEquals("DEPTNO", metadata.getAsJsonArray("columns").get(0).getAsJsonObject().get("name").getAsString());
        final JsonArray data = first.getAsJsonArray("data");
        assertEquals(5, data.size());
        assertEquals("SPIFFY COMPUTER SERVICE DIV.", data.get(0).getAsJsonObject().get("DEPTNAME").getAsString());

        final JsonObject second = send("{'id':'m1','type':'sqlmore','cont_id':'q1','rows':5}");
        assertEquals("m1", second.get("id").getAsString());
        assertEquals(5, second.getAsJsonArray("data").size());
        assertFalse(second.get("is_done").getAsBoolean());

        final JsonObject third = send("{'id':'m2','type':'sqlmore','cont_id':'q1'}");
        assertEquals(4, third.getAsJsonArray("data").size());
        assertTrue(third.get("is_done").getAsBoolean());

        assertTrue(send("{'id':'c1','type':'sqlclose','cont_id':'q1'}").get("success").getAsBoolean());
        assertEquals("invalid correlation ID", send("{'id':'m3','type':'sqlmore','cont_id':'q1'}").get("error").getAsString());
    }

    /**
     * A statement that does not produce a result set reports only an update count.
     */
    @Test
    void testSqlUpdate() {
        final JsonObject reply = send("{'id':'u1','type':'sql','sql':'DELETE FROM SAMPLE.DEPARTMENT WHERE DEPTNO = 1;'}");
        assertTrue(reply.get("success").getAsBoolean());
        assertFalse(reply.get("has_results").getAsBoolean());
        assertEquals(1, reply.get("update_count").getAsInt());
        assertFalse(reply.has("metadata"));
        assertFalse(reply.has("data"));

        final JsonObject unmatched = send("{'id':'u2','type':'sql','sql':'CREATE TABLE QTEMP.T (C INT)'}");
        assertTrue(unmatched.get("success").getAsBoolean());
        assertEquals(0, unmatched.get("update_count").getAsInt());
    }

    /**
     * Terse mode returns rows as arrays in column order.
     */
    @Test
    void testTerseRows() {
        final JsonObject reply = send("{'id':'q1','type':'sql','sql':'SELECT * FROM SYSIBM.SYSDUMMY1','terse':true}");
        final JsonArray row = reply.getAsJsonArray("data").get(0).getAsJsonArray();
        assertEquals(1, row.size());
        assertEquals("Y", row.get(0).getAsString());
        assertTrue(reply.get("is_done").getAsBoolean());
    }

    /**
     * The version reply uses the real server's field names.
     */
    @Test
    void testVersionAndJob() {
        m_config.setVersion("9.9.9").setBuildDate("2030-01-01");
        final JsonObject version = send("{'id':'v1','type':'getversion'}");
        assertEquals("9.9.9", version.get("version").getAsString());
        assertEquals("2030-01-01", version.get("build_date").getAsString());
        assertEquals(m_session.getJobName(), send("{'id':'j1','type':'getdbjob'}").get("job").getAsString());
    }
}
