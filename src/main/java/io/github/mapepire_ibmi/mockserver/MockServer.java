package io.github.mapepire_ibmi.mockserver;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import org.java_websocket.WebSocket;
import org.java_websocket.drafts.Draft;
import org.java_websocket.exceptions.InvalidDataException;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.handshake.ServerHandshakeBuilder;
import org.java_websocket.server.DefaultSSLWebSocketServerFactory;
import org.java_websocket.server.WebSocketServer;

/**
 * A WebSocket server that speaks the Mapepire protocol, answering requests from a {@link MockDatabase} of rules
 * rather than from a real IBM i system.
 * <p>
 * Like the real server, it accepts WebSocket connections on <code>/db/</code>, requires an HTTP Basic
 * <code>Authorization</code> header on the upgrade request, and by default uses TLS (<code>wss://</code>). Each
 * connection gets its own {@link MockSession}.
 *
 * <pre>
 * try (MockServer server = new MockServer(new MockServerConfig().setPort(0))) {
 *     server.start();
 *     // connect a client to server.getUri()
 * }
 * </pre>
 */
public class MockServer implements AutoCloseable {

    /** The password of the bundled self-signed keystore. It protects nothing secret. */
    public static final String BUNDLED_KEYSTORE_PASSWORD = "mapepire-mock";

    private static final long STARTUP_TIMEOUT_SECONDS = 30;
    private static final int STOP_TIMEOUT_MILLIS = 2000;

    private static final AtomicInteger s_connectionCounter = new AtomicInteger(1);

    private final MockServerConfig m_config;
    private final Endpoint m_endpoint;
    private final CountDownLatch m_startedLatch = new CountDownLatch(1);
    private volatile Exception m_startupError = null;

    /**
     * Create a server. Nothing listens until {@link #start()} is called.
     *
     * @param _config
     *            the server configuration
     * @throws IOException
     *             if TLS is enabled and the keystore cannot be loaded
     */
    public MockServer(final MockServerConfig _config) throws IOException {
        m_config = _config;
        disableWebSocketLibraryAssertions();
        m_endpoint = new Endpoint(new InetSocketAddress(_config.getHost(), _config.getPort()));
        m_endpoint.setReuseAddr(true);
        m_endpoint.setTcpNoDelay(true);
        if (_config.isSecure()) {
            m_endpoint.setWebSocketFactory(new DefaultSSLWebSocketServerFactory(createSslContext(_config)));
        }
    }

    /**
     * Build the TLS context from the configured keystore, or the bundled self-signed keystore if none is
     * configured.
     *
     * @param _config
     *            the server configuration
     * @return the TLS context
     * @throws IOException
     *             if the keystore cannot be read or used
     */
    private static SSLContext createSslContext(final MockServerConfig _config) throws IOException {
        final boolean isBundled = null == _config.getKeyStoreFile();
        final char[] password = isBundled ? BUNDLED_KEYSTORE_PASSWORD.toCharArray() : _config.getKeyStorePassword();
        try (final InputStream in = isBundled ? MockServer.class.getResourceAsStream(MockServerConfig.BUNDLED_KEYSTORE_RESOURCE) : new FileInputStream(_config.getKeyStoreFile())) {
            if (null == in) {
                throw new IOException("Bundled keystore not found: " + MockServerConfig.BUNDLED_KEYSTORE_RESOURCE);
            }
            final KeyStore keyStore = KeyStore.getInstance(isBundled ? "PKCS12" : _config.getKeyStoreType());
            keyStore.load(in, password);
            final KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore, password);
            final SSLContext ret = SSLContext.getInstance("TLS");
            ret.init(kmf.getKeyManagers(), null, null);
            return ret;
        } catch (final GeneralSecurityException _e) {
            // The message names the failing operation; it never contains the keystore password
            throw new IOException("Unable to initialize TLS: " + _e.getMessage(), _e);
        }
    }

    /**
     * Turn off Java assertions in the Java-WebSocket library. Its TLS channel asserts on a state that occurs
     * routinely when a peer closes a TLS connection (Java-WebSocket issue #190); the code after the assertion
     * handles that state correctly, but with assertions enabled (as under Maven Surefire, or any JVM run with
     * <code>-ea</code>) the assertion error kills the server's selector thread. This must run before the library's
     * TLS channel class is loaded, which happens when the first TLS connection is accepted.
     */
    private static void disableWebSocketLibraryAssertions() {
        final ClassLoader loader = WebSocketServer.class.getClassLoader();
        if (null != loader) {
            loader.setPackageAssertionStatus("org.java_websocket", false);
        }
    }

    /**
     * Stop the server, waiting briefly for open connections to close.
     */
    @Override
    public void close() {
        try {
            m_endpoint.stop(STOP_TIMEOUT_MILLIS);
        } catch (final InterruptedException _e) {
            Thread.currentThread().interrupt();
        }
    }

    public MockServerConfig getConfig() {
        return m_config;
    }

    public MockDatabase getDatabase() {
        return m_config.getDatabase();
    }

    /**
     * Get the port the server is listening on. When the configured port is 0, this is the port the operating
     * system chose, once {@link #start()} has returned.
     *
     * @return the port
     */
    public int getPort() {
        return m_endpoint.getPort();
    }

    /**
     * Get the URI clients should connect to, using <code>localhost</code> as the host name.
     *
     * @return the URI, for example <code>wss://localhost:7892/db/</code>
     */
    public String getUri() {
        return (m_config.isSecure() ? "wss" : "ws") + "://localhost:" + getPort() + "/db/";
    }

    /**
     * Start listening, and wait until the server is accepting connections.
     *
     * @throws IOException
     *             if the server could not bind to its address or did not start in time
     */
    public void start() throws IOException {
        m_endpoint.start();
        try {
            if (!m_startedLatch.await(STARTUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IOException("Mock server did not start within " + STARTUP_TIMEOUT_SECONDS + " seconds");
            }
        } catch (final InterruptedException _e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for the mock server to start", _e);
        }
        if (null != m_startupError) {
            throw new IOException("Mock server failed to start: " + m_startupError.getMessage(), m_startupError);
        }
    }

    /**
     * The WebSocket endpoint. Handles the upgrade handshake (authentication and path checks) and routes each
     * text frame to the connection's {@link MockSession}.
     */
    private class Endpoint extends WebSocketServer {

        Endpoint(final InetSocketAddress _address) {
            super(_address);
        }

        /**
         * Parse and check the credentials in a Basic <code>Authorization</code> header. The checks mirror the
         * real server's: the header must be present, use the Basic scheme, and decode to a non-empty user and
         * password separated by a colon.
         *
         * @param _header
         *            the header value, or <code>null</code> if absent
         * @return the authenticated user
         * @throws InvalidDataException
         *             if the header is missing or malformed, or the credentials are not accepted
         */
        private String authenticate(final String _header) throws InvalidDataException {
            if (null == _header || _header.isEmpty()) {
                throw new InvalidDataException(CloseFrame.POLICY_VALIDATION, "Authorization header missing");
            }
            if (!_header.startsWith("Basic ")) {
                throw new InvalidDataException(CloseFrame.POLICY_VALIDATION, "Invalid Authorization header");
            }
            final byte[] decoded;
            try {
                decoded = Base64.getDecoder().decode(_header.substring(6).trim());
            } catch (final IllegalArgumentException _e) {
                throw new InvalidDataException(CloseFrame.POLICY_VALIDATION, "Invalid Authorization header");
            }
            int colon = -1;
            for (int i = 0; i < decoded.length; ++i) {
                if (':' == decoded[i]) {
                    colon = i;
                    break;
                }
            }
            if (0 >= colon || decoded.length - 1 == colon) {
                Arrays.fill(decoded, (byte) 0);
                throw new InvalidDataException(CloseFrame.POLICY_VALIDATION, "Invalid Authorization header");
            }
            final String user = new String(decoded, 0, colon, StandardCharsets.UTF_8);
            final CharBuffer chars = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(decoded, colon + 1, decoded.length - colon - 1));
            final char[] password = new char[chars.remaining()];
            chars.get(password);
            try {
                if (!m_config.isAuthorized(user, password)) {
                    throw new InvalidDataException(CloseFrame.POLICY_VALIDATION, "Invalid credentials for user " + user);
                }
            } finally {
                Arrays.fill(password, '\0');
                if (chars.hasArray()) {
                    Arrays.fill(chars.array(), '\0');
                }
                Arrays.fill(decoded, (byte) 0);
            }
            return user;
        }

        @Override
        public void onClose(final WebSocket _conn, final int _code, final String _reason, final boolean _isRemote) {
            final Object attachment = _conn.getAttachment();
            if (attachment instanceof MockSession) {
                ((MockSession) attachment).getTracer().logInfo("Connection closed (code=" + _code + ", reason=" + _reason + ", remote=" + _isRemote + ")");
            }
        }

        /**
         * Record a failure. A failure with no connection happens before the server is listening (for example,
         * the port is in use), and is reported by {@link MockServer#start()}.
         */
        @Override
        public void onError(final WebSocket _conn, final Exception _e) {
            if (null == _conn) {
                m_startupError = _e;
                m_startedLatch.countDown();
                return;
            }
            final Object attachment = _conn.getAttachment();
            if (attachment instanceof MockSession) {
                ((MockSession) attachment).getTracer().logErr(_e);
            } else if (null != m_config.getConsole()) {
                m_config.getConsole().println("WebSocket error: " + _e);
            }
        }

        @Override
        public void onMessage(final WebSocket _conn, final ByteBuffer _message) {
            onMessage(_conn, StandardCharsets.UTF_8.decode(_message).toString());
        }

        /**
         * Pass a request to the connection's session, send the reply, and close the connection if the request
         * was an <code>exit</code>.
         */
        @Override
        public void onMessage(final WebSocket _conn, final String _message) {
            final Object attachment = _conn.getAttachment();
            if (!(attachment instanceof MockSession)) {
                return;
            }
            final MockSession session = (MockSession) attachment;
            final String reply = session.handle(_message);
            if (null != reply) {
                _conn.send(reply);
            }
            if (session.isEnded()) {
                _conn.close(CloseFrame.NORMAL, "exit");
            }
        }

        /**
         * Create the session for a newly upgraded connection.
         */
        @Override
        public void onOpen(final WebSocket _conn, final ClientHandshake _handshake) {
            final String user;
            try {
                user = authenticate(_handshake.getFieldValue("Authorization"));
            } catch (final InvalidDataException _e) {
                // Already checked during the handshake, so this only happens if the checks disagree
                _conn.close(CloseFrame.POLICY_VALIDATION, _e.getMessage());
                return;
            }
            final String connectionId = "conn" + s_connectionCounter.getAndIncrement();
            final MockTracer tracer = new MockTracer(connectionId, m_config.getConsole());
            tracer.logInfo("Connection opened for user " + user + " from " + _conn.getRemoteSocketAddress());
            _conn.setAttachment(new MockSession(m_config, tracer));
        }

        @Override
        public void onStart() {
            m_startedLatch.countDown();
        }

        /**
         * Reject upgrade requests for the wrong path or without acceptable credentials. A rejected upgrade is
         * answered with an HTTP error instead of <code>101 Switching Protocols</code>, so the client never sees an
         * open connection.
         */
        @Override
        public ServerHandshakeBuilder onWebsocketHandshakeReceivedAsServer(final WebSocket _conn, final Draft _draft, final ClientHandshake _request) throws InvalidDataException {
            final ServerHandshakeBuilder ret = super.onWebsocketHandshakeReceivedAsServer(_conn, _draft, _request);
            final String path = _request.getResourceDescriptor();
            if (null == path || !("/db".equals(path) || path.startsWith("/db/"))) {
                throw new InvalidDataException(CloseFrame.POLICY_VALIDATION, "Not found: " + path);
            }
            authenticate(_request.getFieldValue("Authorization"));
            return ret;
        }
    }
}
