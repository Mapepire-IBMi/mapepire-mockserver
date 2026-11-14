package io.github.mapepire_ibmi.mockserver;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests command-line and environment-variable handling.
 */
class MockServerMainTest {

    @TempDir
    File m_tempDir;

    private static MockServerConfig parse(final Map<String, String> _env, final String... _args) throws IOException {
        return MockServerMain.parseArgs(_args, _env, System.out);
    }

    private static MockServerConfig parse(final String... _args) throws IOException {
        return parse(Collections.<String, String> emptyMap(), _args);
    }

    /**
     * Command-line options override the corresponding environment variables.
     *
     * @throws IOException
     *             never
     */
    @Test
    void testArgumentsOverrideEnvironment() throws IOException {
        final Map<String, String> env = new HashMap<String, String>();
        env.put("PORT", "9000");
        env.put("MP_UNSECURE", "TRUE");
        final MockServerConfig fromEnv = parse(env);
        assertEquals(9000, fromEnv.getPort());
        assertFalse(fromEnv.isSecure());
        assertEquals(9100, parse(env, "--port", "9100").getPort());
    }

    /**
     * Only the two mock accounts are accepted, and there are no options to configure others.
     *
     * @throws IOException
     *             never
     */
    @Test
    void testCredentials() throws IOException {
        final MockServerConfig config = parse();
        assertTrue(config.isAuthorized("MOCK", "mockingbird".toCharArray()));
        assertTrue(config.isAuthorized("mock", "mockingbird".toCharArray()));
        assertTrue(config.isAuthorized("MOCK2", "mockeroni".toCharArray()));
        assertTrue(config.isAuthorized("mOcK2", "mockeroni".toCharArray()));
        assertFalse(config.isAuthorized("MOCK", "Mockingbird".toCharArray()));
        assertFalse(config.isAuthorized("MOCK2", "mockingbird".toCharArray()));
        assertFalse(config.isAuthorized("anyone", "anything".toCharArray()));
        assertFalse(config.isAuthorized(null, "mockingbird".toCharArray()));
        assertFalse(config.isAuthorized("MOCK", null));
        assertThrows(IllegalArgumentException.class, () -> parse("--user", "bob", "--password", "pw"));
    }

    /**
     * With no options, the server listens securely on the default port.
     *
     * @throws IOException
     *             never
     */
    @Test
    void testDefaults() throws IOException {
        final MockServerConfig config = parse();
        assertEquals(7892, config.getPort());
        assertEquals(MockServerConfig.DEFAULT_PORT, config.getPort());
        assertTrue(config.isSecure());
        assertNull(config.getConsole());
        assertNull(config.getKeyStoreFile());
    }

    /**
     * Invalid options are reported rather than ignored.
     */
    @Test
    void testInvalidArguments() {
        assertThrows(IllegalArgumentException.class, () -> parse("--bogus"));
        assertThrows(IllegalArgumentException.class, () -> parse("--port"));
        assertThrows(IllegalArgumentException.class, () -> parse("--port", "seventy"));
        assertThrows(IllegalArgumentException.class, () -> parse("--port", "70000"));
        assertThrows(IllegalArgumentException.class, () -> parse("--keystore", "x.p12"));
        assertThrows(IOException.class, () -> parse("--rules", new File(m_tempDir, "missing.json").getPath()));
    }

    /**
     * The remaining options are applied to the configuration.
     *
     * @throws IOException
     *             if the rules file cannot be written
     */
    @Test
    void testOtherOptions() throws IOException {
        final File rules = new File(m_tempDir, "rules.json");
        Files.write(rules.toPath(), "{\"include_defaults\": false}".getBytes(StandardCharsets.UTF_8));
        final MockServerConfig config = parse("-p", "0", "--host", "127.0.0.1", "--unsecure", "-r", rules.getPath(), "-v", "--keystore", "my.jks", "--keystore-password", "pw", "--keystore-type", "JKS");
        assertEquals(0, config.getPort());
        assertEquals("127.0.0.1", config.getHost());
        assertFalse(config.isSecure());
        assertSame(System.out, config.getConsole());
        assertEquals("my.jks", config.getKeyStoreFile().getName());
        assertEquals("JKS", config.getKeyStoreType());
        assertEquals(SqlRule.Kind.ERROR, config.getDatabase().findSqlRule("SELECT * FROM SAMPLE.DEPARTMENT", null).getKind());
    }
}
