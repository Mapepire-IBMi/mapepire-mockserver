package io.github.mapepire_ibmi.mockserver;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.Properties;

/**
 * Settings for a {@link MockServer}. Setters return <code>this</code> so they can be chained. A configuration
 * should be fully set up before the server is started; changing it afterwards has undefined results, except for
 * the rules held by the {@link MockDatabase}, which may be changed at any time.
 */
public class MockServerConfig {

    /** The classpath location of the PEM certificate matching the bundled keystore. */
    public static final String BUNDLED_CERTIFICATE_RESOURCE = "/io/github/mapepire_ibmi/mockserver/mockserver-cert.pem";

    /** The classpath location of the self-signed keystore used when no keystore is configured. */
    public static final String BUNDLED_KEYSTORE_RESOURCE = "/io/github/mapepire_ibmi/mockserver/mockserver.p12";

    /** The port the mock server listens on by default. */
    public static final int DEFAULT_PORT = 7892;

    private static final String VERSION_RESOURCE = "/io/github/mapepire_ibmi/mockserver/version.properties";

    private String m_buildDate;
    private PrintStream m_console = null;
    private MockDatabase m_database = null;
    private String m_host = "0.0.0.0";
    private boolean m_isSecure = true;
    private File m_keyStoreFile = null;
    private char[] m_keyStorePassword = null;
    private String m_keyStoreType = "PKCS12";
    private char[] m_password = null;
    private int m_port = DEFAULT_PORT;
    private String m_user = null;
    private String m_version;

    /**
     * Create a configuration with default settings: TLS enabled with the bundled self-signed certificate,
     * listening on all interfaces on port {@value #DEFAULT_PORT}, accepting any credentials, and answering with
     * the built-in default rules.
     */
    public MockServerConfig() {
        final Properties versionProps = loadVersionProperties();
        m_version = versionProps.getProperty("version", "0.0.0");
        m_buildDate = versionProps.getProperty("build_date", "unknown");
    }

    /**
     * Read the version information generated into the build.
     *
     * @return the properties; empty if the resource is missing
     */
    private static Properties loadVersionProperties() {
        final Properties ret = new Properties();
        try (final InputStream in = MockServerConfig.class.getResourceAsStream(VERSION_RESOURCE)) {
            if (null != in) {
                ret.load(in);
            }
        } catch (final IOException _e) {
            // The defaults applied by the caller are an acceptable answer to a getversion request
            System.err.println("Unable to read version information: " + _e.getMessage());
        }
        return ret;
    }

    public String getBuildDate() {
        return m_buildDate;
    }

    public PrintStream getConsole() {
        return m_console;
    }

    /**
     * Get the database, creating one with the built-in default rules if none has been set.
     *
     * @return the database
     */
    public synchronized MockDatabase getDatabase() {
        if (null == m_database) {
            m_database = new MockDatabase();
        }
        return m_database;
    }

    public String getHost() {
        return m_host;
    }

    public File getKeyStoreFile() {
        return m_keyStoreFile;
    }

    public char[] getKeyStorePassword() {
        return m_keyStorePassword;
    }

    public String getKeyStoreType() {
        return m_keyStoreType;
    }

    public int getPort() {
        return m_port;
    }

    public String getUser() {
        return m_user;
    }

    public String getVersion() {
        return m_version;
    }

    /**
     * Decide whether a set of credentials is accepted. When no user has been configured, any user with a
     * non-empty password is accepted. Otherwise the user must match (ignoring case, as IBM i user profiles do)
     * and the password must match exactly.
     *
     * @param _user
     *            the user from the client's Basic authentication header
     * @param _password
     *            the password from the client's Basic authentication header
     * @return <code>true</code> if the credentials are accepted
     */
    public boolean isAuthorized(final String _user, final char[] _password) {
        if (null == m_user) {
            return true;
        }
        return m_user.equalsIgnoreCase(_user) && Arrays.equals(m_password, _password);
    }

    public boolean isSecure() {
        return m_isSecure;
    }

    public MockServerConfig setBuildDate(final String _buildDate) {
        m_buildDate = _buildDate;
        return this;
    }

    /**
     * Echo trace records from every connection to a stream, regardless of each connection's trace level.
     *
     * @param _console
     *            the stream, or <code>null</code> to disable echoing
     * @return this configuration
     */
    public MockServerConfig setConsole(final PrintStream _console) {
        m_console = _console;
        return this;
    }

    /**
     * Require clients to authenticate as a specific user. When this is never called, any credentials are
     * accepted.
     *
     * @param _user
     *            the user profile name; compared ignoring case
     * @param _password
     *            the password; compared exactly. The array is copied.
     * @return this configuration
     */
    public MockServerConfig setCredentials(final String _user, final char[] _password) {
        m_user = _user;
        m_password = null == _password ? null : _password.clone();
        return this;
    }

    public synchronized MockServerConfig setDatabase(final MockDatabase _database) {
        m_database = _database;
        return this;
    }

    public MockServerConfig setHost(final String _host) {
        m_host = _host;
        return this;
    }

    /**
     * Use a keystore other than the bundled self-signed one for TLS.
     *
     * @param _keyStoreFile
     *            the keystore file, or <code>null</code> for the bundled keystore
     * @param _keyStorePassword
     *            the keystore password; the array is copied
     * @param _keyStoreType
     *            the keystore type, for example <code>PKCS12</code> or <code>JKS</code>
     * @return this configuration
     */
    public MockServerConfig setKeyStore(final File _keyStoreFile, final char[] _keyStorePassword, final String _keyStoreType) {
        m_keyStoreFile = _keyStoreFile;
        m_keyStorePassword = null == _keyStorePassword ? null : _keyStorePassword.clone();
        m_keyStoreType = _keyStoreType;
        return this;
    }

    /**
     * Set the port to listen on.
     *
     * @param _port
     *            the port, or 0 to have the operating system choose a free port (see {@link MockServer#getPort()})
     * @return this configuration
     */
    public MockServerConfig setPort(final int _port) {
        m_port = _port;
        return this;
    }

    /**
     * Choose between <code>wss://</code> (the default, matching the real server) and plain <code>ws://</code>.
     *
     * @param _isSecure
     *            <code>true</code> to use TLS
     * @return this configuration
     */
    public MockServerConfig setSecure(final boolean _isSecure) {
        m_isSecure = _isSecure;
        return this;
    }

    public MockServerConfig setVersion(final String _version) {
        m_version = _version;
        return this;
    }
}
