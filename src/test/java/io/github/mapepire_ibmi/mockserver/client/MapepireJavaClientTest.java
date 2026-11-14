package io.github.mapepire_ibmi.mockserver.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.github.mapepire_ibmi.Pool;
import io.github.mapepire_ibmi.Query;
import io.github.mapepire_ibmi.SqlJob;
import io.github.mapepire_ibmi.mockserver.ClRule;
import io.github.mapepire_ibmi.mockserver.MockServer;
import io.github.mapepire_ibmi.mockserver.MockServerConfig;
import io.github.mapepire_ibmi.mockserver.SqlRule;
import io.github.mapepire_ibmi.types.CLCommandResult;
import io.github.mapepire_ibmi.types.ConnectionResult;
import io.github.mapepire_ibmi.types.DaemonServer;
import io.github.mapepire_ibmi.types.ExplainResults;
import io.github.mapepire_ibmi.types.GetTraceDataResult;
import io.github.mapepire_ibmi.types.JobStatus;
import io.github.mapepire_ibmi.types.PoolOptions;
import io.github.mapepire_ibmi.types.QueryOptions;
import io.github.mapepire_ibmi.types.QueryResult;
import io.github.mapepire_ibmi.types.ServerTraceDest;
import io.github.mapepire_ibmi.types.ServerTraceLevel;
import io.github.mapepire_ibmi.types.SetConfigResult;
import io.github.mapepire_ibmi.types.SqlCLCommandResult;
import io.github.mapepire_ibmi.types.TransactionEndType;
import io.github.mapepire_ibmi.types.VersionCheckResult;
import io.github.mapepire_ibmi.types.exceptions.RequestTimeoutException;

/**
 * Drives the mock server through the official Mapepire Java client (<code>mapepire-sdk</code>), over TLS with
 * the bundled self-signed certificate, to confirm the mock is a drop-in stand-in for a real server. Tagged
 * <code>java-client</code> so it can be skipped with <code>-DexcludedGroups=java-client</code>.
 */
@Tag("java-client")
@Timeout(120)
class MapepireJavaClientTest {

    private static final String DEPARTMENTS = "SELECT * FROM SAMPLE.DEPARTMENT";
    private static final String PASSWORD = MockServerConfig.MOCK_PASSWORD;
    private static final long REJECTED_CONNECT_WAIT_SECONDS = 3;
    private static final String SLOW_QUERY = "SELECT * FROM MOCK.SLOW";
    private static final long TIMEOUT_SECONDS = 30;
    private static final String USER = MockServerConfig.MOCK_USER;

    private static MockServer s_server;

    private SqlJob m_job;

    /**
     * Start one server for the whole class, with a deliberately slow statement.
     *
     * @throws IOException
     *             if the server cannot start
     */
    @BeforeAll
    static void startServer() throws IOException {
        final MockServerConfig config = new MockServerConfig().setHost("localhost").setPort(0);
        s_server = new MockServer(config);
        s_server.getDatabase().addSqlRule(SqlRule.updateCount("select \\* from mock\\.slow", 0).withDelay(3000));
        s_server.getDatabase().addClRule(ClRule.failure("sndpgmmsg\\b.*", ClRule.message("CPF9898", "ESCAPE", 40, "Mock escape message.")));
        s_server.start();
    }

    @AfterAll
    static void stopServer() {
        s_server.close();
    }

    private static DaemonServer credentials(final String _password) {
        return new DaemonServer("localhost", s_server.getPort(), USER, _password, false);
    }

    /**
     * Unwrap the cause of a failed future, failing the test if the future did not fail.
     *
     * @param _call
     *            the call whose future is expected to fail
     * @return the cause of the failure
     */
    private static Throwable getFailure(final FutureCall _call) {
        final ExecutionException e = assertThrows(ExecutionException.class, () -> _call.get());
        return e.getCause();
    }

    /**
     * Read the PEM certificate that matches the server's bundled keystore.
     *
     * @return the certificate text
     * @throws IOException
     *             if the resource cannot be read
     */
    private static String readBundledCertificate() throws IOException {
        try (final InputStream in = MockServer.class.getResourceAsStream(MockServerConfig.BUNDLED_CERTIFICATE_RESOURCE); final Scanner scanner = new Scanner(in, StandardCharsets.UTF_8.name())) {
            return scanner.useDelimiter("\\A").next();
        }
    }

    @AfterEach
    void closeJob() {
        m_job.close();
    }

    @BeforeEach
    void connectJob() throws Exception {
        m_job = new SqlJob();
        final ConnectionResult result = m_job.connect(credentials(PASSWORD)).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(result.getSuccess());
    }

    /**
     * CL commands report their messages through both the SQL-based and the command-call request types.
     *
     * @throws Exception
     *             if the client cannot communicate with the server
     */
    @Test
    void testClCommands() throws Exception {
        final SqlCLCommandResult ok = m_job.clCommand("CRTLIB LIB(MOCKLIB)").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(ok.getSuccess());
        assertEquals("CPC2102", ok.getData().get(0).getId());
        assertEquals("COMPLETION", ok.getData().get(0).getType());

        final CLCommandResult failed = m_job.rcclCommand("SNDPGMMSG MSG('boom') MSGTYPE(*ESCAPE)").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertFalse(failed.getSuccess());
        assertEquals("CL command execution failed: [CPF9898] Mock escape message.", failed.getError());
        assertEquals(40, failed.getData().get(0).getSeverity());
    }

    /**
     * A connection reports a job name and leaves the job ready for work, and closing it ends the job. Both mock
     * accounts can connect.
     *
     * @throws Exception
     *             if the client cannot communicate with the server
     */
    @Test
    void testConnectAndClose() throws Exception {
        assertEquals(JobStatus.Ready, m_job.getStatus());
        final VersionCheckResult version = m_job.getVersion().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(version.getSuccess());
        assertEquals(s_server.getConfig().getVersion(), version.getVersion());

        final SqlJob other = new SqlJob();
        // The second account, with the user profile in lower case: profiles are not case-sensitive
        final DaemonServer mock2 = new DaemonServer("localhost", s_server.getPort(), "mock2", MockServerConfig.MOCK2_PASSWORD, false);
        final ConnectionResult result = other.connect(mock2).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(result.getJob().matches("\\d{6}/QUSER/QZDASOINIT"), result.getJob());
        other.close();
        assertEquals(JobStatus.Ended, other.getStatus());
    }

    /**
     * The client verifies the bundled certificate when it is supplied as the trusted CA.
     *
     * @throws Exception
     *             if the client cannot communicate with the server
     */
    @Test
    void testConnectWithTrustedCertificate() throws Exception {
        final SqlJob verified = new SqlJob();
        final DaemonServer creds = new DaemonServer("localhost", s_server.getPort(), USER, PASSWORD, true, readBundledCertificate());
        try {
            assertTrue(verified.connect(creds).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getSuccess());
            assertEquals(1, verified.execute("SELECT * FROM SYSIBM.SYSDUMMY1").get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getData().size());
        } finally {
            verified.close();
        }
    }

    /**
     * Wrong credentials are refused during the WebSocket upgrade, so the job never becomes usable.
     * <p>
     * mapepire-sdk 2.0.0 does not complete its connect future when the upgrade is refused (it disposes the job
     * but leaves the future pending; fixed on the client's main branch), so the future either fails or times out
     * depending on the client version. Either way the job must end up {@link JobStatus#Ended}.
     *
     * @throws InterruptedException
     *             if the test is interrupted
     */
    @Test
    void testConnectWithWrongPassword() throws InterruptedException {
        final SqlJob rejected = new SqlJob();
        try {
            final Exception e = assertThrows(Exception.class, () -> rejected.connect(credentials("wrong")).get(REJECTED_CONNECT_WAIT_SECONDS, TimeUnit.SECONDS));
            assertTrue(e instanceof ExecutionException || e instanceof TimeoutException, e.toString());
            assertEquals(JobStatus.Ended, rejected.getStatus());
        } finally {
            rejected.close();
        }
    }

    /**
     * Visual Explain returns the configured explain data along with the statement's results.
     *
     * @throws Exception
     *             if the client cannot communicate with the server
     */
    @Test
    void testExplain() throws Exception {
        final ExplainResults<?> results = m_job.explain(DEPARTMENTS).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(results.getSuccess());
        assertEquals(5, results.getVemetadata().getColumnCount());
        assertEquals(2, ((List<?>) results.getVedata()).size());
        assertEquals(14, results.getData().size());
    }

    /**
     * A plain query returns every row, with metadata, when it fits in one block.
     *
     * @throws Exception
     *             if the client cannot communicate with the server
     */
    @Test
    void testExecuteQuery() throws Exception {
        final QueryResult<Object> result = m_job.execute(DEPARTMENTS).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(result.getSuccess());
        assertTrue(result.getHasResults());
        assertTrue(result.getIsDone());
        assertEquals(14, result.getData().size());
        assertEquals(5, result.getMetadata().getColumnCount());
        assertEquals("DEPTNO", result.getMetadata().getColumns().get(0).getName());
        final Map<?, ?> first = (Map<?, ?>) result.getData().get(0);
        assertEquals("A00", first.get("DEPTNO"));
        assertTrue(first.containsKey("LOCATION"));
    }

    /**
     * SQL failures surface as {@link SQLException}s carrying the SQLSTATE.
     */
    @Test
    void testExecuteQueryError() {
        final Throwable cause = getFailure(() -> m_job.execute("SELECT * FROM NOPE.NOPE").get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        final SQLException sqlException = assertInstanceOf(SQLException.class, cause);
        assertEquals("42704", sqlException.getSQLState());
        assertTrue(sqlException.getMessage().contains("SQL0204"), sqlException.getMessage());
    }

    /**
     * A statement without a result set reports its update count.
     *
     * @throws Exception
     *             if the client cannot communicate with the server
     */
    @Test
    void testExecuteUpdate() throws Exception {
        final QueryResult<Object> result = m_job.execute("UPDATE SAMPLE.DEPARTMENT SET LOCATION = NULL").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(result.getSuccess());
        assertFalse(result.getHasResults());
        assertEquals(1, result.getUpdateCount());
    }

    /**
     * Rows are fetched in blocks with <code>fetchMore</code> until the query is done.
     *
     * @throws Exception
     *             if the client cannot communicate with the server
     */
    @Test
    void testFetchMore() throws Exception {
        final Query query = m_job.query(DEPARTMENTS);
        final QueryResult<Object> first = query.execute(5).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(5, first.getData().size());
        assertFalse(first.getIsDone());
        final QueryResult<Object> second = query.fetchMore().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(5, second.getData().size());
        assertFalse(second.getIsDone());
        final QueryResult<Object> third = query.fetchMore().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(4, third.getData().size());
        assertTrue(third.getIsDone());
        assertTrue(query.close().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).contains("\"success\":true"));
    }

    /**
     * A pool opens several jobs against the server and runs queries on them.
     *
     * @throws Exception
     *             if the client cannot communicate with the server
     */
    @Test
    void testPool() throws Exception {
        final Pool pool = new Pool(new PoolOptions(credentials(PASSWORD), 3, 2));
        try {
            pool.init().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertEquals(2, pool.getActiveJobCount());
            final QueryResult<Object> result = pool.execute(DEPARTMENTS).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertEquals(14, result.getData().size());
        } finally {
            pool.end();
        }
    }

    /**
     * Parameterized queries are answered according to the parameter values.
     *
     * @throws Exception
     *             if the client cannot communicate with the server
     */
    @Test
    void testPreparedQuery() throws Exception {
        final String sql = "SELECT * FROM SAMPLE.DEPARTMENT WHERE ADMRDEPT = ?";
        final QueryResult<Object> e01 = m_job.execute(sql, new QueryOptions(false, Arrays.<Object> asList("E01"))).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(7, e01.getData().size());
        assertEquals(1, e01.getParameterCount());
        final QueryResult<Object> a00 = m_job.execute(sql, new QueryOptions(false, Arrays.<Object> asList("A00"))).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(5, a00.getData().size());
    }

    /**
     * A statement slower than the client's request timeout fails with {@link RequestTimeoutException}.
     */
    @Test
    void testRequestTimeout() {
        m_job.setRequestTimeout(500);
        final Throwable cause = getFailure(() -> m_job.execute(SLOW_QUERY).get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertInstanceOf(RequestTimeoutException.class, cause);
    }

    /**
     * Terse results are lists of values rather than maps.
     *
     * @throws Exception
     *             if the client cannot communicate with the server
     */
    @Test
    void testTerseResults() throws Exception {
        final QueryResult<Object> result = m_job.execute("SELECT * FROM SYSIBM.SYSDUMMY1", new QueryOptions(true, null)).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(Arrays.asList("Y"), result.getData().get(0));
    }

    /**
     * Trace settings round-trip, and trace data reflects the requests sent.
     *
     * @throws Exception
     *             if the client cannot communicate with the server
     */
    @Test
    void testTracing() throws Exception {
        final SetConfigResult config = m_job.setTraceConfig(ServerTraceDest.IN_MEM, ServerTraceLevel.DATASTREAM, null, null).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals("IN_MEM", config.getTraceDest());
        assertEquals(ServerTraceLevel.DATASTREAM, config.getTraceLevel());
        m_job.execute("SELECT * FROM SYSIBM.SYSDUMMY1").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        final GetTraceDataResult trace = m_job.getTraceData().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertNotNull(trace.getTraceData());
        assertTrue(trace.getTraceData().contains("SYSIBM.SYSDUMMY1"), trace.getTraceData());
    }

    /**
     * The client's transaction helpers work against the built-in rules.
     *
     * @throws Exception
     *             if the client cannot communicate with the server
     */
    @Test
    void testTransactions() throws Exception {
        assertEquals(0, m_job.getPendingTransactions().get(TIMEOUT_SECONDS, TimeUnit.SECONDS).intValue());
        assertTrue(m_job.endTransaction(TransactionEndType.COMMIT).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getSuccess());
        assertTrue(m_job.endTransaction(TransactionEndType.ROLLBACK).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getSuccess());
    }

    /**
     * A call that waits on a client future.
     */
    @FunctionalInterface
    private interface FutureCall {
        Object get() throws Exception;
    }
}
