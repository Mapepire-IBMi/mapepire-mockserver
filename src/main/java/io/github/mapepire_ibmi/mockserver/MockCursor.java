package io.github.mapepire_ibmi.mockserver;

import java.util.ArrayList;
import java.util.List;

/**
 * An open, forward-only position within a {@link MockResultSet}, used to hand rows out in blocks across a
 * query's initial reply and any subsequent <code>sqlmore</code> requests.
 */
public class MockCursor {

    private int m_position = 0;
    private final MockResultSet m_resultSet;

    public MockCursor(final MockResultSet _resultSet) {
        m_resultSet = _resultSet;
    }

    public MockResultSet getResultSet() {
        return m_resultSet;
    }

    public synchronized boolean isDone() {
        return m_position >= m_resultSet.getRows().size();
    }

    /**
     * Fetch the next block of rows and advance the cursor past them.
     *
     * @param _maxRows
     *            the maximum number of rows to return; values below 1 are treated as 1
     * @param _isTerse
     *            when <code>true</code>, rows are returned as arrays rather than objects
     * @return the rows, formatted for a reply's <code>data</code> array; empty once the cursor is exhausted
     */
    public synchronized List<Object> nextBlock(final int _maxRows, final boolean _isTerse) {
        final int end = Math.min(m_resultSet.getRows().size(), m_position + Math.max(1, _maxRows));
        final List<Object> ret = new ArrayList<Object>(Math.max(0, end - m_position));
        while (m_position < end) {
            ret.add(m_resultSet.formatRow(m_position, _isTerse));
            ++m_position;
        }
        return ret;
    }
}
