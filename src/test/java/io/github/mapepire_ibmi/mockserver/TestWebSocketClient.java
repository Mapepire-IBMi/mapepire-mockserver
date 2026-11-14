package io.github.mapepire_ibmi.mockserver;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.framing.CloseFrame;
import org.java_websocket.handshake.ServerHandshake;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * A minimal WebSocket client for exercising the mock server over the network without any Mapepire client
 * library. Replies are queued as they arrive and read back with {@link #receive()}.
 */
public class TestWebSocketClient extends WebSocketClient {

    private static final long TIMEOUT_SECONDS = 10;

    private volatile int m_closeCode = 0;
    private final CountDownLatch m_closedLatch = new CountDownLatch(1);
    private final BlockingQueue<String> m_messages = new LinkedBlockingQueue<String>();

    /**
     * Create a client. Call {@link #connectBlocking(long, TimeUnit)} to connect.
     *
     * @param _uri
     *            the server URI
     * @param _headers
     *            the HTTP headers to send with the upgrade request
     */
    public TestWebSocketClient(final URI _uri, final Map<String, String> _headers) {
        super(_uri, _headers);
    }

    /**
     * Build an HTTP Basic <code>Authorization</code> header.
     *
     * @param _user
     *            the user
     * @param _password
     *            the password
     * @return a header map holding only the <code>Authorization</code> header
     */
    public static Map<String, String> basicAuth(final String _user, final String _password) {
        final Map<String, String> ret = new HashMap<String, String>();
        ret.put("Authorization", "Basic " + Base64.getEncoder().encodeToString((_user + ":" + _password).getBytes(StandardCharsets.UTF_8)));
        return ret;
    }

    /**
     * Connect to a running mock server, trusting its bundled certificate when it uses TLS.
     *
     * @param _server
     *            the server
     * @param _headers
     *            the HTTP headers to send with the upgrade request
     * @return the connected client
     * @throws Exception
     *             if the connection cannot be established
     */
    public static TestWebSocketClient connect(final MockServer _server, final Map<String, String> _headers) throws Exception {
        final TestWebSocketClient ret = create(_server, "/db/", _headers);
        if (!ret.connectBlocking(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw new IOException("Connection to " + _server.getUri() + " was refused");
        }
        return ret;
    }

    /**
     * Create, but do not connect, a client for a running mock server.
     *
     * @param _server
     *            the server
     * @param _path
     *            the request path, for example <code>/db/</code>
     * @param _headers
     *            the HTTP headers to send with the upgrade request
     * @return the unconnected client
     * @throws Exception
     *             if the TLS configuration cannot be created
     */
    public static TestWebSocketClient create(final MockServer _server, final String _path, final Map<String, String> _headers) throws Exception {
        final String scheme = _server.getConfig().isSecure() ? "wss" : "ws";
        final TestWebSocketClient ret = new TestWebSocketClient(new URI(scheme + "://localhost:" + _server.getPort() + _path), _headers);
        if (_server.getConfig().isSecure()) {
            ret.setSocketFactory(createTrustingContext().getSocketFactory());
        }
        return ret;
    }

    /**
     * Build a TLS context that trusts only the mock server's bundled self-signed certificate.
     *
     * @return the TLS context
     * @throws IOException
     *             if the certificate resource cannot be read
     * @throws GeneralSecurityException
     *             if the TLS context cannot be created
     */
    public static SSLContext createTrustingContext() throws IOException, GeneralSecurityException {
        final KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        trustStore.load(null, null);
        try (final InputStream in = TestWebSocketClient.class.getResourceAsStream(MockServerConfig.BUNDLED_CERTIFICATE_RESOURCE)) {
            final Certificate cert = CertificateFactory.getInstance("X.509").generateCertificate(in);
            trustStore.setCertificateEntry("mockserver", cert);
        }
        final TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);
        final SSLContext ret = SSLContext.getInstance("TLS");
        ret.init(null, tmf.getTrustManagers(), null);
        return ret;
    }

    /**
     * Attempt a connection and report whether the server rejected it, either by refusing the upgrade or by
     * closing the connection straight away with a policy-violation code.
     *
     * @param _server
     *            the server
     * @param _path
     *            the request path
     * @param _headers
     *            the HTTP headers to send with the upgrade request
     * @return <code>true</code> if the upgrade was refused, or the connection was closed with code 1008 (policy
     *         violation)
     * @throws Exception
     *             if the client cannot be created
     */
    public static boolean isRejected(final MockServer _server, final String _path, final Map<String, String> _headers) throws Exception {
        final TestWebSocketClient client = create(_server, _path, _headers);
        if (!client.connectBlocking(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            return true;
        }
        if (!client.awaitClosed()) {
            client.closeBlocking();
            return false;
        }
        return CloseFrame.POLICY_VALIDATION == client.getCloseCode();
    }

    public boolean awaitClosed() throws InterruptedException {
        return m_closedLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    public int getCloseCode() {
        return m_closeCode;
    }

    @Override
    public void onClose(final int _code, final String _reason, final boolean _isRemote) {
        m_closeCode = _code;
        m_closedLatch.countDown();
    }

    @Override
    public void onError(final Exception _e) {
        // A failed connection also triggers onClose, which is what the tests wait on
    }

    @Override
    public void onMessage(final String _message) {
        m_messages.add(_message);
    }

    @Override
    public void onOpen(final ServerHandshake _handshake) {
        // Nothing to do; connectBlocking() reports the outcome
    }

    /**
     * Wait for the next reply.
     *
     * @return the reply, parsed
     * @throws Exception
     *             if no reply arrives in time
     */
    public JsonObject receive() throws Exception {
        final String message = m_messages.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (null == message) {
            throw new IOException("No reply within " + TIMEOUT_SECONDS + " seconds");
        }
        return JsonParser.parseString(message).getAsJsonObject();
    }

    /**
     * Send a request and wait for the next reply.
     *
     * @param _request
     *            the request text
     * @return the reply, parsed
     * @throws Exception
     *             if no reply arrives in time
     */
    public JsonObject request(final String _request) throws Exception {
        send(_request);
        return receive();
    }
}
