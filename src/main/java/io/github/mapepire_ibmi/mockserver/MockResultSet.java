package io.github.mapepire_ibmi.mockserver;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

/**
 * An immutable, simulated result set: a list of columns and the rows of values for them. Cell values are kept as
 * Gson elements so that whatever JSON type a rule specifies (string, number, boolean, or null) is sent back to the
 * client unchanged.
 */
public class MockResultSet {

    private final List<MockColumn> m_columns;
    private final List<List<JsonElement>> m_rows;

    /**
     * Create a result set.
     *
     * @param _columns
     *            the columns, in order
     * @param _rows
     *            the rows; each row holds one value per column, in column order
     */
    public MockResultSet(final List<MockColumn> _columns, final List<List<JsonElement>> _rows) {
        m_columns = Collections.unmodifiableList(new ArrayList<MockColumn>(_columns));
        final List<List<JsonElement>> rows = new ArrayList<List<JsonElement>>(_rows.size());
        for (final List<JsonElement> row : _rows) {
            rows.add(Collections.unmodifiableList(new ArrayList<JsonElement>(row)));
        }
        m_rows = Collections.unmodifiableList(rows);
    }

    /**
     * Build a result set from JSON rule definitions.
     *
     * @param _columns
     *            an array of column definitions (see {@link MockColumn#fromJson(JsonObject)})
     * @param _rows
     *            an array of rows, or <code>null</code> for no rows. Each row is either an array of values in column
     *            order, or an object keyed by column name (missing columns become <code>null</code>).
     * @return the result set
     * @throws IllegalArgumentException
     *             if a column or row definition is malformed
     */
    public static MockResultSet fromJson(final JsonArray _columns, final JsonArray _rows) {
        final List<MockColumn> columns = new ArrayList<MockColumn>();
        for (final JsonElement column : _columns) {
            if (!column.isJsonObject()) {
                throw new IllegalArgumentException("Column definition must be an object: " + column);
            }
            columns.add(MockColumn.fromJson(column.getAsJsonObject()));
        }
        final List<List<JsonElement>> rows = new ArrayList<List<JsonElement>>();
        if (null != _rows) {
            for (final JsonElement row : _rows) {
                rows.add(parseRow(columns, row));
            }
        }
        return new MockResultSet(columns, rows);
    }

    /**
     * Convert one JSON row definition into a list of values in column order.
     *
     * @param _columns
     *            the result set's columns
     * @param _row
     *            the row definition, either an array of values or an object keyed by column name
     * @return the row values, one per column
     * @throws IllegalArgumentException
     *             if the row is neither an array nor an object, or has the wrong number of values
     */
    private static List<JsonElement> parseRow(final List<MockColumn> _columns, final JsonElement _row) {
        final List<JsonElement> ret = new ArrayList<JsonElement>(_columns.size());
        if (_row.isJsonArray()) {
            final JsonArray values = _row.getAsJsonArray();
            if (values.size() != _columns.size()) {
                throw new IllegalArgumentException("Row has " + values.size() + " values but there are " + _columns.size() + " columns: " + _row);
            }
            for (final JsonElement value : values) {
                ret.add(value);
            }
            return ret;
        }
        if (_row.isJsonObject()) {
            final JsonObject values = _row.getAsJsonObject();
            for (final MockColumn column : _columns) {
                final JsonElement value = values.get(column.getName());
                ret.add(null == value ? JsonNull.INSTANCE : value);
            }
            return ret;
        }
        throw new IllegalArgumentException("Row must be an array or an object: " + _row);
    }

    /**
     * Format one row for a reply's <code>data</code> array.
     *
     * @param _rowIndex
     *            the zero-based row index
     * @param _isTerse
     *            when <code>true</code>, the row is an array of values; otherwise it is an object keyed by column
     *            name
     * @return the formatted row
     */
    public Object formatRow(final int _rowIndex, final boolean _isTerse) {
        final List<JsonElement> row = m_rows.get(_rowIndex);
        if (_isTerse) {
            return row;
        }
        final Map<String, JsonElement> ret = new LinkedHashMap<String, JsonElement>();
        for (int i = 0; i < m_columns.size(); ++i) {
            ret.put(m_columns.get(i).getName(), row.get(i));
        }
        return ret;
    }

    public List<MockColumn> getColumns() {
        return m_columns;
    }

    public List<List<JsonElement>> getRows() {
        return m_rows;
    }

    /**
     * Build the <code>metadata</code> reply object describing this result set.
     *
     * @param _jobName
     *            the simulated job name to report
     * @return the metadata, keyed by protocol field name
     */
    public Map<String, Object> toMetadata(final String _jobName) {
        final Map<String, Object> ret = new LinkedHashMap<String, Object>();
        ret.put("column_count", m_columns.size());
        ret.put("job", _jobName);
        final List<Map<String, Object>> columns = new ArrayList<Map<String, Object>>();
        for (final MockColumn column : m_columns) {
            columns.add(column.toMetadata());
        }
        ret.put("columns", columns);
        return ret;
    }
}
