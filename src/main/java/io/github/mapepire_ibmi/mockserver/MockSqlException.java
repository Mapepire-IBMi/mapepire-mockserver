package io.github.mapepire_ibmi.mockserver;

/**
 * A simulated SQL failure. In addition to the <code>error</code> field, error replies built from this exception
 * carry the <code>sql_state</code> and <code>sql_rc</code> fields, as the real server does for a
 * <code>java.sql.SQLException</code>.
 */
public class MockSqlException extends MockRequestException {

    private static final long serialVersionUID = 1L;

    private final int m_sqlRc;
    private final String m_sqlState;

    /**
     * Create a simulated SQL failure.
     *
     * @param _message
     *            the human-readable error description
     * @param _sqlState
     *            the SQLSTATE to report, for example <code>42704</code>
     * @param _sqlRc
     *            the native SQL return code to report, for example <code>-204</code>
     */
    public MockSqlException(final String _message, final String _sqlState, final int _sqlRc) {
        super(_message);
        m_sqlState = _sqlState;
        m_sqlRc = _sqlRc;
    }

    public int getSqlRc() {
        return m_sqlRc;
    }

    public String getSqlState() {
        return m_sqlState;
    }
}
