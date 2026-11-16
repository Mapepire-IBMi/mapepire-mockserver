package io.github.mapepire_ibmi.mockserver.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import io.github.mapepire_ibmi.Query;
import io.github.mapepire_ibmi.SqlJob;
import io.github.mapepire_ibmi.mockserver.MockServer;
import io.github.mapepire_ibmi.mockserver.MockServerConfig;
import io.github.mapepire_ibmi.mockserver.SqlRule;
import io.github.mapepire_ibmi.types.DaemonServer;
import io.github.mapepire_ibmi.types.QueryOptions;
import io.github.mapepire_ibmi.types.QueryResult;

/**
 * Drives the mock server's SQLite database through the official Mapepire Java client: real tables, real inserts
 * with parameter markers, and real queries, alongside the built-in rules. Tagged <code>java-client</code> so it
 * can be skipped with <code>-DexcludedGroups=java-client</code>.
 */
@Tag("java-client")
@Timeout(300)
class SqliteJavaClientTest {

    private static final int BATCH_SIZE = 500;
    private static final int FETCH_SIZE = 13;
    private static final int ROW_COUNT = 5000;
    private static final long TIMEOUT_SECONDS = 60;

    private static MockServer s_server;

    private SqlJob m_job;

    /**
     * Start one server for the whole class.
     *
     * @throws IOException
     *             if the server cannot start
     */
    @BeforeAll
    static void startServer() throws IOException {
        final MockServerConfig config = new MockServerConfig().setHost("localhost").setPort(0);
        s_server = new MockServer(config);
        s_server.start();
    }

    @AfterAll
    static void stopServer() {
        s_server.close();
    }

    /**
     * Make a schema name that starts with <code>Jesse</code> and is unlikely to collide with any other.
     *
     * @return the schema name
     */
    private static String randomSchemaName() {
        final String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        final Random random = new SecureRandom();
        final StringBuilder ret = new StringBuilder("Jesse");
        for (int i = 0; i < 5; ++i) {
            ret.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return ret.toString();
    }

    @AfterEach
    void closeJob() {
        if (null != m_job) {
            m_job.close();
        }
    }

    @BeforeEach
    void connect() throws Exception {
        m_job = new SqlJob();
        m_job.connect(new DaemonServer("localhost", s_server.getPort(), MockServerConfig.MOCK_USER, MockServerConfig.MOCK_PASSWORD, false)).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Built-in rules still answer their statements, ahead of the SQLite database.
     *
     * @throws Exception
     *             if the client cannot communicate with the server
     */
    @Test
    void testBuiltInRulesStillApply() throws Exception {
        final QueryResult<Object> result = m_job.execute("SELECT * FROM SYSIBM.SYSDUMMY1").get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(result.getSuccess());
        assertEquals(1, result.getData().size());
        assertEquals("Y", ((Map<?, ?>) result.getData().get(0)).get("IBMREQD"));
    }

    /**
     * Create a randomly named schema and a table in it, insert {@value #ROW_COUNT} rows through a prepared
     * statement with parameter markers, then read every row back {@value #FETCH_SIZE} at a time.
     *
     * @throws Exception
     *             if the client cannot communicate with the server
     */
    @Test
    void testInsertAndFetchManyRows() throws Exception {
        final String schema = randomSchemaName();
        final String table = schema + ".ITEMS";
        assertTrue(m_job.execute("CREATE SCHEMA " + schema).get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getSuccess());
        try {
            assertTrue(m_job.execute("CREATE TABLE " + table + " (ID INTEGER NOT NULL PRIMARY KEY, NAME VARCHAR(50) NOT NULL, PRICE DECIMAL(9, 2))").get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getSuccess());

            final String insert = "INSERT INTO " + table + " (ID, NAME, PRICE) VALUES (?, ?, ?)";
            long inserted = 0;
            for (int start = 1; start <= ROW_COUNT; start += BATCH_SIZE) {
                final List<Object> batch = new ArrayList<Object>();
                for (int id = start; id < start + BATCH_SIZE && id <= ROW_COUNT; ++id) {
                    batch.add(Arrays.<Object> asList(id, "Item " + id, id * 1.25));
                }
                final Query query = m_job.query(insert, new QueryOptions(false, batch));
                final QueryResult<Object> result = query.execute().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                assertTrue(result.getSuccess());
                inserted += result.getUpdateCount();
                query.close().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
            assertEquals(ROW_COUNT, inserted);

            final Query select = m_job.query("SELECT ID, NAME, PRICE FROM " + table + " ORDER BY ID");
            QueryResult<Object> block = select.execute(FETCH_SIZE).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertTrue(block.getHasResults());
            assertEquals(3, block.getMetadata().getColumnCount());
            int expectedId = 1;
            int blocks = 0;
            while (true) {
                ++blocks;
                final int remaining = ROW_COUNT - expectedId + 1;
                assertEquals(Math.min(FETCH_SIZE, remaining), block.getData().size(), "rows in block " + blocks);
                for (final Object row : block.getData()) {
                    final Map<?, ?> values = (Map<?, ?>) row;
                    assertEquals(expectedId, ((Number) values.get("ID")).intValue());
                    assertEquals("Item " + expectedId, values.get("NAME"));
                    assertEquals(expectedId * 1.25, ((Number) values.get("PRICE")).doubleValue(), 0.001);
                    ++expectedId;
                }
                if (block.getIsDone()) {
                    break;
                }
                block = select.fetchMore(FETCH_SIZE).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
            select.close().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertEquals(ROW_COUNT + 1, expectedId);
            assertEquals((ROW_COUNT + FETCH_SIZE - 1) / FETCH_SIZE, blocks);
        } finally {
            assertTrue(m_job.execute("DROP SCHEMA " + schema + " CASCADE").get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getSuccess());
        }
        assertEquals(SqlRule.Kind.ERROR, s_server.getDatabase().findSqlRule("SELECT * FROM " + table, null).getKind(), "the schema's data is gone once it is dropped");
    }
}
