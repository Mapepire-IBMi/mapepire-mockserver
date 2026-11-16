package io.github.mapepire_ibmi.mockserver;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Serves one Mapepire connection over a pair of streams, normally standard input and output, the way the real
 * server's single mode (<code>--single</code>) does. There is no WebSocket, no TLS, and no authentication.
 * <p>
 * Each line of input is one JSON request, read as UTF-8. Blank lines and lines starting with <code>//</code> are
 * skipped without a reply. Each reply is written to the output as one line of UTF-8 text and flushed straight
 * away, so nothing else should be written to the output stream. Processing ends after the reply to an
 * <code>exit</code> request, or when the input ends.
 */
public class MockSingleMode {

    /** The exit status the real server uses when the input ends without an <code>exit</code> request. */
    public static final int EXIT_STATUS_END_OF_INPUT = 12;

    /** The exit status the real server uses after replying to an <code>exit</code> request. */
    public static final int EXIT_STATUS_EXIT_REQUEST = 0;

    private static final AtomicInteger s_counter = new AtomicInteger(1);

    private final BufferedReader m_in;
    private final OutputStream m_out;
    private final MockSession m_session;

    /**
     * Prepare to serve a connection.
     *
     * @param _config
     *            the server configuration, which supplies the rules, the SQLite database, version information,
     *            and the console that trace records are echoed to (if any). Network settings are ignored.
     * @param _in
     *            the stream requests are read from
     * @param _out
     *            the stream replies are written to
     */
    public MockSingleMode(final MockServerConfig _config, final InputStream _in, final OutputStream _out) {
        m_in = new BufferedReader(new InputStreamReader(_in, StandardCharsets.UTF_8));
        m_out = _out;
        m_session = new MockSession(_config, new MockTracer("single" + s_counter.getAndIncrement(), _config.getConsole()));
    }

    public MockSession getSession() {
        return m_session;
    }

    /**
     * Process requests until an <code>exit</code> request has been answered or the input ends.
     *
     * @return {@link #EXIT_STATUS_EXIT_REQUEST} after an <code>exit</code> request, or
     *         {@link #EXIT_STATUS_END_OF_INPUT} if the input ended first
     * @throws IOException
     *             if a request cannot be read or a reply cannot be written
     */
    public int run() throws IOException {
        String line;
        while (null != (line = m_in.readLine())) {
            if (line.startsWith("//")) {
                m_session.getTracer().logDatastreamIn(line);
                continue;
            }
            final String reply = m_session.handle(line);
            if (null != reply) {
                m_out.write((reply + "\n").getBytes(StandardCharsets.UTF_8));
                m_out.flush();
            }
            if (m_session.isEnded()) {
                m_session.getTracer().logInfo("Exiting as requested");
                return EXIT_STATUS_EXIT_REQUEST;
            }
        }
        m_session.getTracer().logInfo("End of input");
        return EXIT_STATUS_END_OF_INPUT;
    }
}
