package io.github.mapepire_ibmi.mockserver;

import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * A per-connection tracer. Trace records are kept in memory, so that they can be returned to the client by a
 * <code>gettracedata</code> request, and are optionally echoed to a console stream.
 * <p>
 * Which records are kept depends on the trace level, which the client controls with <code>setconfig</code>. The
 * levels mirror the real server's: <code>OFF</code>, <code>ON</code> (everything), <code>ERRORS</code>,
 * <code>DATASTREAM</code> (requests, replies and errors), and <code>INPUT_AND_ERRORS</code>.
 */
public class MockTracer {

    private static final List<String> s_destinations = Collections.unmodifiableList(Arrays.asList("FILE", "IN_MEM"));
    private static final List<String> s_levels = Collections.unmodifiableList(Arrays.asList("OFF", "ON", "ERRORS", "DATASTREAM", "INPUT_AND_ERRORS"));

    private final PrintStream m_console;
    private final String m_connectionId;
    private final StringBuilder m_data = new StringBuilder();
    private String m_dest = "IN_MEM";
    private String m_jtOpenDest = "IN_MEM";
    private String m_jtOpenLevel = "OFF";
    private String m_level = "OFF";

    /**
     * Create a tracer.
     *
     * @param _connectionId
     *            an identifier for the connection, included in every record
     * @param _console
     *            a stream to echo every record to regardless of trace level, or <code>null</code> for none
     */
    public MockTracer(final String _connectionId, final PrintStream _console) {
        m_connectionId = _connectionId;
        m_console = _console;
    }

    /**
     * Validate a trace destination supplied by a client.
     *
     * @param _dest
     *            the destination, in any case
     * @return the canonical upper-case destination, or <code>null</code> if it is not a known destination
     */
    public static String canonicalizeDest(final String _dest) {
        final String dest = _dest.trim().toUpperCase(Locale.ROOT);
        return s_destinations.contains(dest) ? dest : null;
    }

    /**
     * Validate a trace level supplied by a client.
     *
     * @param _level
     *            the level, in any case
     * @return the canonical upper-case level, or <code>null</code> if it is not a known level
     */
    public static String canonicalizeLevel(final String _level) {
        final String level = _level.trim().toUpperCase(Locale.ROOT);
        return s_levels.contains(level) ? level : null;
    }

    public String getConnectionId() {
        return m_connectionId;
    }

    public synchronized String getData() {
        return m_data.toString();
    }

    /**
     * Describe the trace destination the way the real server does: <code>IN_MEM</code>, or the absolute path of
     * the trace file. The mock server never writes trace files; the path is reported for fidelity only.
     *
     * @return the destination description
     */
    public synchronized String getDestString() {
        if ("FILE".equals(m_dest)) {
            return "/tmp/mapepire-mock/" + m_connectionId + ".html";
        }
        return m_dest;
    }

    public synchronized String getJtOpenDest() {
        return m_jtOpenDest;
    }

    public synchronized String getJtOpenLevel() {
        return m_jtOpenLevel;
    }

    public synchronized String getLevel() {
        return m_level;
    }

    /**
     * Write one trace record.
     *
     * @param _category
     *            the record category, such as <code>IN</code> or <code>ERR</code>
     * @param _message
     *            the record text
     * @param _isKept
     *            whether the current trace level keeps this record in memory
     */
    private synchronized void log(final String _category, final String _message, final boolean _isKept) {
        if (!_isKept && null == m_console) {
            return;
        }
        final String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).format(new Date());
        final String record = String.format("[%s] [%s] [%s] %s", timestamp, m_connectionId, _category, _message);
        if (_isKept) {
            m_data.append(record).append('\n');
        }
        if (null != m_console) {
            m_console.println(record);
        }
    }

    /**
     * Record an incoming request.
     *
     * @param _request
     *            the raw request text. Requests never carry credentials (those travel only in the WebSocket
     *            handshake), so the text is safe to record.
     */
    public void logDatastreamIn(final String _request) {
        final String level = getLevel();
        log("IN", _request, "ON".equals(level) || "DATASTREAM".equals(level) || "INPUT_AND_ERRORS".equals(level));
    }

    /**
     * Record an outgoing reply.
     *
     * @param _reply
     *            the raw reply text
     */
    public void logDatastreamOut(final String _reply) {
        final String level = getLevel();
        log("OUT", _reply, "ON".equals(level) || "DATASTREAM".equals(level));
    }

    public void logErr(final String _message) {
        log("ERR", _message, !"OFF".equals(getLevel()));
    }

    /**
     * Record an error, including its stack trace.
     *
     * @param _e
     *            the error
     */
    public void logErr(final Throwable _e) {
        final StringWriter stack = new StringWriter();
        try (final PrintWriter writer = new PrintWriter(stack)) {
            _e.printStackTrace(writer);
        }
        logErr(stack.toString());
    }

    public void logInfo(final String _message) {
        log("INFO", _message, "ON".equals(getLevel()));
    }

    public synchronized void setDest(final String _dest) {
        m_dest = _dest;
    }

    public synchronized void setJtOpenDest(final String _jtOpenDest) {
        m_jtOpenDest = _jtOpenDest;
    }

    public synchronized void setJtOpenLevel(final String _jtOpenLevel) {
        m_jtOpenLevel = _jtOpenLevel;
    }

    public synchronized void setLevel(final String _level) {
        m_level = _level;
    }
}
