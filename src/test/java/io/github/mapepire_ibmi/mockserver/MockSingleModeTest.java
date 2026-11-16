package io.github.mapepire_ibmi.mockserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tests single mode, in which one connection is served over standard input and output.
 */
@Timeout(120)
class MockSingleModeTest {

    @TempDir
    File m_tempDir;

    /**
     * Join requests into the text a client would send, one per line. Requests are written with <code>'</code> in
     * place of <code>"</code> for readability.
     *
     * @param _lines
     *            the request lines
     * @return the input bytes, in UTF-8
     */
    private static byte[] input(final String... _lines) {
        final StringBuilder ret = new StringBuilder();
        for (final String line : _lines) {
            ret.append(line.replace('\'', '"')).append('\n');
        }
        return ret.toString().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Split the output into parsed replies, one per line.
     *
     * @param _out
     *            the output bytes, in UTF-8
     * @return the replies
     */
    private static List<JsonObject> replies(final byte[] _out) {
        final List<JsonObject> ret = new ArrayList<JsonObject>();
        final String text = new String(_out, StandardCharsets.UTF_8);
        if (text.isEmpty()) {
            return ret;
        }
        assertTrue(text.endsWith("\n"), "every reply ends with a line break");
        for (final String line : text.substring(0, text.length() - 1).split("\n", -1)) {
            ret.add(JsonParser.parseString(line).getAsJsonObject());
        }
        return ret;
    }

    /**
     * Read everything from a stream.
     *
     * @param _in
     *            the stream
     * @return the bytes read
     * @throws IOException
     *             if the stream cannot be read
     */
    private static byte[] readAll(final InputStream _in) throws IOException {
        final ByteArrayOutputStream ret = new ByteArrayOutputStream();
        final byte[] buffer = new byte[8192];
        int count;
        while (0 <= (count = _in.read(buffer))) {
            ret.write(buffer, 0, count);
        }
        return ret.toByteArray();
    }

    /**
     * Start the mock server's main class as a separate process.
     *
     * @param _stderr
     *            the file to send the process's standard error to, so that it can never fill up and block the
     *            process
     * @param _args
     *            the command-line arguments
     * @return the process
     * @throws IOException
     *             if the process cannot be started
     */
    private static Process startMain(final File _stderr, final String... _args) throws IOException {
        final List<String> command = new ArrayList<String>();
        command.add(System.getProperty("java.home") + File.separator + "bin" + File.separator + "java");
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(MockServerMain.class.getName());
        command.addAll(Arrays.asList(_args));
        return new ProcessBuilder(command).redirectError(_stderr).start();
    }

    /**
     * When the input ends without an <code>exit</code> request, processing stops with the real server's status.
     *
     * @throws IOException
     *             never
     */
    @Test
    void testEndOfInput() throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final int status = new MockSingleMode(new MockServerConfig(), new ByteArrayInputStream(input("{'id':'p1','type':'ping'}")), out).run();
        assertEquals(MockSingleMode.EXIT_STATUS_END_OF_INPUT, status);
        assertEquals(1, replies(out.toByteArray()).size());
        assertEquals(MockSingleMode.EXIT_STATUS_END_OF_INPUT, new MockSingleMode(new MockServerConfig(), new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream()).run());
    }

    /**
     * Requests are answered in order, one reply per line. Blank lines and <code>//</code> comments get no reply,
     * and nothing after an <code>exit</code> request is read.
     *
     * @throws IOException
     *             never
     */
    @Test
    void testRequestsAndExit() throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        //@formatter:off
        final byte[] in = input(
                "{'id':'c1','type':'connect'}",
                "",
                "   ",
                "// a comment, which gets no reply",
                "{'id':'q1','type':'sql','sql':'SELECT * FROM SYSIBM.SYSDUMMY1'}",
                "not json",
                "{'id':'x1','type':'exit'}",
                "{'id':'after','type':'ping'}");
        //@formatter:on
        final MockSingleMode single = new MockSingleMode(new MockServerConfig(), new ByteArrayInputStream(in), out);
        assertEquals(MockSingleMode.EXIT_STATUS_EXIT_REQUEST, single.run());
        final List<JsonObject> replies = replies(out.toByteArray());
        assertEquals(4, replies.size());
        assertEquals("c1", replies.get(0).get("id").getAsString());
        assertEquals(single.getSession().getJobName(), replies.get(0).get("job").getAsString());
        assertEquals("Y", replies.get(1).getAsJsonArray("data").get(0).getAsJsonObject().get("IBMREQD").getAsString());
        assertFalse(replies.get(2).get("success").getAsBoolean());
        assertTrue(replies.get(2).get("id").getAsString().startsWith("unparseable_"));
        assertEquals("x1", replies.get(3).get("id").getAsString());
        assertTrue(replies.get(3).get("success").getAsBoolean());
    }

    /**
     * Requests and replies are UTF-8, and the SQLite database works as it does over a WebSocket.
     *
     * @throws IOException
     *             never
     */
    @Test
    void testSqliteAndUtf8() throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        //@formatter:off
        final byte[] in = input(
                "{'id':'1','type':'sql','sql':'CREATE TABLE QTEMP.GREETINGS (TEXT VARCHAR(20))'}",
                "{'id':'2','type':'prepare_sql_execute','sql':'INSERT INTO QTEMP.GREETINGS VALUES (?)','parameters':[['Grüße'],['こんにちは']]}",
                "{'id':'3','type':'sql','sql':'SELECT TEXT FROM QTEMP.GREETINGS','rows':1}",
                "{'id':'4','type':'sqlmore','cont_id':'3','rows':1}");
        //@formatter:on
        new MockSingleMode(new MockServerConfig(), new ByteArrayInputStream(in), out).run();
        final List<JsonObject> replies = replies(out.toByteArray());
        assertEquals(4, replies.size());
        assertEquals(2, replies.get(1).get("update_count").getAsLong());
        assertEquals("Grüße", replies.get(2).getAsJsonArray("data").get(0).getAsJsonObject().get("TEXT").getAsString());
        assertFalse(replies.get(2).get("is_done").getAsBoolean());
        assertEquals("こんにちは", replies.get(3).getAsJsonArray("data").get(0).getAsJsonObject().get("TEXT").getAsString());
        assertTrue(replies.get(3).get("is_done").getAsBoolean());
    }

    /**
     * The <code>--single</code> option selects single mode without needing any network options.
     *
     * @throws IOException
     *             never
     */
    @Test
    void testSingleOption() throws IOException {
        assertTrue(MockServerMain.parseArgs(new String[] { "--single" }, Collections.<String, String> emptyMap(), System.err).isSingleMode());
        assertFalse(MockServerMain.parseArgs(new String[0], Collections.<String, String> emptyMap(), System.err).isSingleMode());
    }

    /**
     * Run the real entry point as a separate process with <code>--single --verbose</code>. Standard output holds
     * only the replies, trace records go to standard error, and the process exits with the real server's status.
     *
     * @throws Exception
     *             if the process cannot be run
     */
    @Test
    void testStandaloneProcess() throws Exception {
        final File stderrFile = new File(m_tempDir, "stderr.txt");
        final Process process = startMain(stderrFile, "--single", "--verbose");
        try (final OutputStream stdin = process.getOutputStream()) {
            stdin.write(input("{'id':'v1','type':'getversion'}", "{'id':'q1','type':'sql','sql':'SELECT * FROM SAMPLE.DEPARTMENT'}", "{'id':'x1','type':'exit'}"));
        }
        final byte[] stdout = readAll(process.getInputStream());
        assertTrue(process.waitFor(60, TimeUnit.SECONDS));
        final List<String> stderr = Files.readAllLines(stderrFile.toPath(), StandardCharsets.UTF_8);
        assertEquals(MockSingleMode.EXIT_STATUS_EXIT_REQUEST, process.exitValue(), String.join("\n", stderr));

        final List<JsonObject> replies = replies(stdout);
        assertEquals(3, replies.size(), new String(stdout, StandardCharsets.UTF_8));
        assertEquals(new MockServerConfig().getVersion(), replies.get(0).get("version").getAsString());
        assertEquals(14, replies.get(1).getAsJsonArray("data").size());
        assertEquals("x1", replies.get(2).get("id").getAsString());
        assertTrue(stderr.stream().anyMatch(_line -> _line.contains("getversion")), String.join("\n", stderr));
    }

    /**
     * When standard input ends without an <code>exit</code> request, the process exits with status 12.
     *
     * @throws Exception
     *             if the process cannot be run
     */
    @Test
    void testStandaloneProcessEndOfInput() throws Exception {
        final Process process = startMain(new File(m_tempDir, "stderr.txt"), "--single");
        process.getOutputStream().close();
        assertEquals(0, readAll(process.getInputStream()).length);
        assertTrue(process.waitFor(60, TimeUnit.SECONDS));
        assertEquals(MockSingleMode.EXIT_STATUS_END_OF_INPUT, process.exitValue());
    }
}
