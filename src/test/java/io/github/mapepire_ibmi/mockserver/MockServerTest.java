package io.github.mapepire_ibmi.mockserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Exercises the mock server over real WebSocket connections, using only a bare WebSocket client.
 */
@Timeout(60)
class MockServerTest {

    private static final String PING = "{\"id\":\"p1\",\"type\":\"ping\"}";

    /**
     * Start a server on a free port.
     *
     * @param _config
     *            the configuration; its port is overridden with 0
     * @return the running server
     * @throws IOException
     *             if the server cannot start
     */
    private static MockServer start(final MockServerConfig _config) throws IOException {
        final MockServer ret = new MockServer(_config.setHost("127.0.0.1").setPort(0));
        ret.start();
        return ret;
    }

    /**
     * Only the two mock accounts are accepted. User profiles are matched ignoring case; passwords are
     * case-sensitive. Anything else is refused during the upgrade.
     *
     * @throws Exception
     *             if the test cannot communicate with the server
     */
    @Test
    void testAuthentication() throws Exception {
        try (final MockServer server = start(new MockServerConfig().setSecure(false))) {
            for (final String[] creds : new String[][] { { "MOCK", "mockingbird" }, { "mock", "mockingbird" }, { "MOCK2", "mockeroni" }, { "Mock2", "mockeroni" } }) {
                final TestWebSocketClient client = TestWebSocketClient.connect(server, TestWebSocketClient.basicAuth(creds[0], creds[1]));
                assertTrue(client.request(PING).get("success").getAsBoolean());
                client.closeBlocking();
            }
            //@formatter:off
            final String[][] rejected = {
                    { "MOCK", "MOCKINGBIRD" },    // passwords are case-sensitive
                    { "MOCK", "mockeroni" },      // the other account's password
                    { "MOCK2", "mockingbird" },
                    { "MOCK3", "mockingbird" },   // no such user
                    { "anyone", "anything" },
                    { "MOCK", "" } };
            //@formatter:on
            for (final String[] creds : rejected) {
                assertTrue(TestWebSocketClient.isRejected(server, "/db/", TestWebSocketClient.basicAuth(creds[0], creds[1])), creds[0] + "/" + creds[1]);
            }
            assertTrue(TestWebSocketClient.isRejected(server, "/db/", new HashMap<String, String>()));
            final HashMap<String, String> bearer = new HashMap<String, String>();
            bearer.put("Authorization", "Bearer abc");
            assertTrue(TestWebSocketClient.isRejected(server, "/db/", bearer));
        }
    }

    /**
     * Each connection is an independent session with its own job.
     *
     * @throws Exception
     *             if the test cannot communicate with the server
     */
    @Test
    void testConnectionsAreIndependent() throws Exception {
        try (final MockServer server = start(new MockServerConfig().setSecure(false))) {
            final TestWebSocketClient first = TestWebSocketClient.connect(server);
            final TestWebSocketClient second = TestWebSocketClient.connect(server);
            final String firstJob = first.request("{\"id\":\"1\",\"type\":\"connect\"}").get("job").getAsString();
            final String secondJob = second.request("{\"id\":\"1\",\"type\":\"connect\"}").get("job").getAsString();
            assertNotEquals(firstJob, secondJob);

            first.request("{\"id\":\"q1\",\"type\":\"sql\",\"sql\":\"SELECT * FROM SAMPLE.DEPARTMENT\",\"rows\":1}");
            final JsonObject more = second.request("{\"id\":\"m1\",\"type\":\"sqlmore\",\"cont_id\":\"q1\"}");
            assertEquals("invalid correlation ID", more.get("error").getAsString());
            first.closeBlocking();
            second.closeBlocking();
        }
    }

    /**
     * The server closes the connection after replying to an <code>exit</code> request.
     *
     * @throws Exception
     *             if the test cannot communicate with the server
     */
    @Test
    void testExitClosesConnection() throws Exception {
        try (final MockServer server = start(new MockServerConfig().setSecure(false))) {
            final TestWebSocketClient client = TestWebSocketClient.connect(server);
            final JsonObject reply = client.request("{\"id\":\"99\",\"type\":\"exit\"}");
            assertEquals("99", reply.get("id").getAsString());
            assertTrue(reply.get("success").getAsBoolean());
            assertTrue(client.awaitClosed());
        }
    }

    /**
     * Rules added while the server is running take effect immediately.
     *
     * @throws Exception
     *             if the test cannot communicate with the server
     */
    @Test
    void testRulesCanChangeWhileRunning() throws Exception {
        try (final MockServer server = start(new MockServerConfig().setSecure(false).setDatabase(new MockDatabase(false)))) {
            final TestWebSocketClient client = TestWebSocketClient.connect(server);
            final String query = "{\"id\":\"q%d\",\"type\":\"sql\",\"sql\":\"SELECT * FROM SYSIBM.SYSDUMMY1\"}";
            assertFalse(client.request(String.format(query, 1)).get("success").getAsBoolean());
            server.getDatabase().addSqlRule(SqlRule.resultSet("select \\* from sysibm\\.sysdummy1", MockResultSet.fromJson(new JsonArray(), null)));
            assertTrue(client.request(String.format(query, 2)).get("success").getAsBoolean());
            client.closeBlocking();
        }
    }

    /**
     * By default the server uses TLS with its bundled certificate, which is valid for <code>localhost</code>.
     *
     * @throws Exception
     *             if the test cannot communicate with the server
     */
    @Test
    void testSecureByDefault() throws Exception {
        try (final MockServer server = start(new MockServerConfig())) {
            assertTrue(server.getUri().startsWith("wss://localhost:"));
            final TestWebSocketClient client = TestWebSocketClient.connect(server);
            final JsonObject reply = client.request(PING);
            assertEquals("p1", reply.get("id").getAsString());
            assertTrue(reply.get("success").getAsBoolean());
            client.closeBlocking();

            // Rejecting a TLS connection must not disturb the server (see Java-WebSocket issue #190)
            assertTrue(TestWebSocketClient.isRejected(server, "/db/", new HashMap<String, String>()));
            final TestWebSocketClient after = TestWebSocketClient.connect(server);
            assertTrue(after.request(PING).get("success").getAsBoolean());
            after.closeBlocking();
        }
    }

    /**
     * Starting a second server on a port that is already in use fails with an exception rather than silently.
     *
     * @throws Exception
     *             if the first server cannot start
     */
    @Test
    void testStartFailsWhenPortInUse() throws Exception {
        try (final MockServer first = start(new MockServerConfig().setSecure(false))) {
            try (final MockServer second = new MockServer(new MockServerConfig().setSecure(false).setHost("127.0.0.1").setPort(first.getPort()))) {
                assertThrows(IOException.class, second::start);
            }
        }
    }

    /**
     * Only the <code>/db/</code> endpoint accepts WebSocket connections.
     *
     * @throws Exception
     *             if the test cannot communicate with the server
     */
    @Test
    void testWrongPathRejected() throws Exception {
        try (final MockServer server = start(new MockServerConfig().setSecure(false))) {
            assertTrue(TestWebSocketClient.isRejected(server, "/other/", TestWebSocketClient.mockAuth()));
            final TestWebSocketClient client = TestWebSocketClient.create(server, "/db", TestWebSocketClient.mockAuth());
            assertTrue(client.connectBlocking(10, TimeUnit.SECONDS));
            client.closeBlocking();
        }
    }
}
