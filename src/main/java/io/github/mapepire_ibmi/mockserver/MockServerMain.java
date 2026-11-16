package io.github.mapepire_ibmi.mockserver;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Command-line entry point for the mock server. Run with <code>--help</code> for usage.
 */
public final class MockServerMain {

    private static final String USAGE = String.join("\n",
            "Usage: java -jar mapepire-mockserver-jar-with-dependencies.jar [options]",
            "",
            "Options:",
            "  -p, --port <port>              Port to listen on (default: 7892, or the PORT environment variable)",
            "      --host <address>           Address to bind to (default: 0.0.0.0)",
            "      --unsecure                 Serve plain ws:// instead of wss:// (also: MP_UNSECURE=true)",
            "      --keystore <file>          Keystore holding the TLS certificate (default: bundled self-signed certificate)",
            "      --keystore-password <pw>   Keystore password (or the MP_MOCK_KEYSTORE_PASSWORD environment variable)",
            "      --keystore-type <type>     Keystore type (default: PKCS12)",
            "  -r, --rules <file>             JSON file of SQL and CL rules (see README)",
            "      --sqlite                   Run SQL that matches no rule against an in-memory SQLite database",
            "  -v, --verbose                Echo all trace records to standard output",
            "      --version                  Print the version and exit",
            "  -h, --help                     Print this help and exit",
            "",
            "Clients must authenticate as MOCK (password: mockingbird) or MOCK2 (password: mockeroni).",
            "User profiles are not case-sensitive; passwords are.");

    private MockServerMain() {
    }

    /**
     * Start the mock server and keep it running until the JVM is stopped.
     *
     * @param _args
     *            the command-line arguments
     */
    public static void main(final String[] _args) {
        final List<String> args = Arrays.asList(_args);
        if (args.contains("-h") || args.contains("--help")) {
            System.out.println(USAGE);
            return;
        }
        if (args.contains("--version")) {
            final MockServerConfig defaults = new MockServerConfig();
            System.out.println("mapepire-mockserver " + defaults.getVersion() + " (built " + defaults.getBuildDate() + ")");
            return;
        }
        final MockServerConfig config;
        try {
            config = parseArgs(_args, System.getenv(), System.out);
        } catch (final IllegalArgumentException | IOException _e) {
            System.err.println("Error: " + _e.getMessage());
            System.err.println();
            System.err.println(USAGE);
            System.exit(1);
            return;
        }
        try {
            final MockServer server = new MockServer(config);
            server.start();
            Runtime.getRuntime().addShutdownHook(new Thread(server::close, "mapepire-mock-shutdown"));
            System.out.println("Mapepire mock server " + config.getVersion() + " listening on " + (config.isSecure() ? "wss" : "ws") + "://" + config.getHost() + ":" + server.getPort() + "/db/");
        } catch (final IOException _e) {
            System.err.println("Error: " + _e.getMessage());
            System.exit(2);
        }
    }

    /**
     * Build a server configuration from command-line arguments and environment variables. Arguments take
     * precedence over environment variables.
     *
     * @param _args
     *            the command-line arguments
     * @param _env
     *            the environment variables
     * @param _console
     *            the stream that <code>--verbose</code> echoes trace records to
     * @return the configuration
     * @throws IllegalArgumentException
     *             if an argument is unknown, missing its value, or has an invalid value
     * @throws IOException
     *             if the rules file cannot be read or is not valid
     */
    public static MockServerConfig parseArgs(final String[] _args, final Map<String, String> _env, final PrintStream _console) throws IOException {
        final MockServerConfig ret = new MockServerConfig();
        if (null != _env.get("PORT")) {
            ret.setPort(parsePort(_env.get("PORT")));
        }
        if ("true".equalsIgnoreCase(_env.get("MP_UNSECURE"))) {
            ret.setSecure(false);
        }
        File keyStoreFile = null;
        String keyStorePassword = _env.get("MP_MOCK_KEYSTORE_PASSWORD");
        String keyStoreType = "PKCS12";
        boolean isUsingSqlite = false;
        for (int i = 0; i < _args.length; ++i) {
            final String arg = _args[i];
            switch (arg) {
                case "-p":
                case "--port":
                    ret.setPort(parsePort(getValue(_args, ++i, arg)));
                    break;
                case "--host":
                    ret.setHost(getValue(_args, ++i, arg));
                    break;
                case "--unsecure":
                    ret.setSecure(false);
                    break;
                case "--keystore":
                    keyStoreFile = new File(getValue(_args, ++i, arg));
                    break;
                case "--keystore-password":
                    keyStorePassword = getValue(_args, ++i, arg);
                    break;
                case "--keystore-type":
                    keyStoreType = getValue(_args, ++i, arg);
                    break;
                case "-r":
                case "--rules":
                    ret.setDatabase(MockDatabase.fromFile(new File(getValue(_args, ++i, arg))));
                    break;
                case "--sqlite":
                    isUsingSqlite = true;
                    break;
                case "-v":
                case "--verbose":
                    ret.setConsole(_console);
                    break;
                default:
                    throw new IllegalArgumentException("Unknown option: " + arg);
            }
        }
        if (isUsingSqlite) {
            // Applied after the loop so that it holds whether it comes before or after --rules
            ret.getDatabase().enableSqlite();
        }
        if (null != keyStoreFile) {
            if (null == keyStorePassword) {
                throw new IllegalArgumentException("--keystore requires --keystore-password (or MP_MOCK_KEYSTORE_PASSWORD)");
            }
            ret.setKeyStore(keyStoreFile, keyStorePassword.toCharArray(), keyStoreType);
        }
        return ret;
    }

    /**
     * Read the value following an option.
     *
     * @param _args
     *            the command-line arguments
     * @param _index
     *            the index of the value
     * @param _option
     *            the option the value belongs to, for the error message
     * @return the value
     * @throws IllegalArgumentException
     *             if there is no value
     */
    private static String getValue(final String[] _args, final int _index, final String _option) {
        if (_index >= _args.length) {
            throw new IllegalArgumentException(_option + " requires a value");
        }
        return _args[_index];
    }

    /**
     * Parse and range-check a port number.
     *
     * @param _value
     *            the text to parse
     * @return the port
     * @throws IllegalArgumentException
     *             if the text is not a number from 0 to 65535
     */
    private static int parsePort(final String _value) {
        final int port;
        try {
            port = Integer.parseInt(_value.trim());
        } catch (final NumberFormatException _e) {
            throw new IllegalArgumentException("Invalid port: " + _value);
        }
        if (0 > port || 65535 < port) {
            throw new IllegalArgumentException("Invalid port: " + _value);
        }
        return port;
    }
}
