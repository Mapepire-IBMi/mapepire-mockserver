package io.github.mapepire_ibmi.mockserver;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonObject;

/**
 * Describes one column of a simulated result set. Serialized into the <code>columns</code> array of a
 * <code>metadata</code> reply object using the same field names as the real server's ColumnMetaData.
 */
public class MockColumn {

    private final int m_displaySize;
    private final boolean m_isAutoIncrement;
    private final boolean m_isReadOnly;
    private final boolean m_isWriteable;
    private final String m_label;
    private final String m_name;
    private final int m_nullable;
    private final int m_precision;
    private final int m_scale;
    private final String m_table;
    private final String m_type;

    /**
     * Create a nullable, read-only column with defaults for everything other than its name, type, precision and
     * scale.
     *
     * @param _name
     *            the column name, which is also used as its label
     * @param _type
     *            the SQL type name, for example <code>VARCHAR</code>
     * @param _precision
     *            the precision or character length
     * @param _scale
     *            the number of digits after the decimal point
     */
    public MockColumn(final String _name, final String _type, final int _precision, final int _scale) {
        this(_name, _name, _type, _precision, _scale, _precision, 1, "", false, true, false);
    }

    /**
     * Create a column with every attribute specified.
     *
     * @param _name
     *            the column name
     * @param _label
     *            the column label
     * @param _type
     *            the SQL type name
     * @param _precision
     *            the precision or character length
     * @param _scale
     *            the number of digits after the decimal point
     * @param _displaySize
     *            the maximum display width
     * @param _nullable
     *            the JDBC nullability: 0 (not nullable), 1 (nullable), or 2 (unknown)
     * @param _table
     *            the name of the table the column belongs to
     * @param _isAutoIncrement
     *            whether the column is auto-incrementing
     * @param _isReadOnly
     *            whether the column is read-only
     * @param _isWriteable
     *            whether the column is writeable
     */
    public MockColumn(final String _name, final String _label, final String _type, final int _precision, final int _scale, final int _displaySize, final int _nullable, final String _table, final boolean _isAutoIncrement, final boolean _isReadOnly, final boolean _isWriteable) {
        m_name = _name;
        m_label = _label;
        m_type = _type;
        m_precision = _precision;
        m_scale = _scale;
        m_displaySize = _displaySize;
        m_nullable = _nullable;
        m_table = _table;
        m_isAutoIncrement = _isAutoIncrement;
        m_isReadOnly = _isReadOnly;
        m_isWriteable = _isWriteable;
    }

    /**
     * Build a column from its JSON rule definition. Only <code>name</code> is required; the other attributes
     * default to values appropriate for the column's type.
     *
     * @param _json
     *            the column definition
     * @return the column
     * @throws IllegalArgumentException
     *             if the definition has no <code>name</code>
     */
    public static MockColumn fromJson(final JsonObject _json) {
        final String name = JsonUtils.getString(_json, "name", null);
        if (null == name || name.isEmpty()) {
            throw new IllegalArgumentException("Column definition is missing 'name': " + _json);
        }
        final String type = JsonUtils.getString(_json, "type", "VARCHAR").toUpperCase(Locale.ROOT);
        final int precision = JsonUtils.getInt(_json, "precision", getDefaultPrecision(type));
        final int scale = JsonUtils.getInt(_json, "scale", 0);
        //@formatter:off
        return new MockColumn(
                name,
                JsonUtils.getString(_json, "label", name),
                type,
                precision,
                scale,
                JsonUtils.getInt(_json, "display_size", precision),
                JsonUtils.getInt(_json, "nullable", 1),
                JsonUtils.getString(_json, "table", ""),
                JsonUtils.getBoolean(_json, "autoIncrement", false),
                JsonUtils.getBoolean(_json, "readOnly", true),
                JsonUtils.getBoolean(_json, "writeable", false));
        //@formatter:on
    }

    /**
     * Choose a plausible precision for a column type when a rule does not specify one.
     *
     * @param _type
     *            the upper-case SQL type name
     * @return the default precision
     */
    private static int getDefaultPrecision(final String _type) {
        switch (_type) {
            case "SMALLINT":
                return 5;
            case "INTEGER":
                return 10;
            case "BIGINT":
                return 19;
            case "DATE":
                return 10;
            case "TIME":
                return 8;
            case "TIMESTAMP":
                return 26;
            case "DECIMAL":
            case "NUMERIC":
                return 15;
            default:
                return 255;
        }
    }

    public String getName() {
        return m_name;
    }

    /**
     * Build the JSON representation of this column used in reply <code>metadata</code>.
     *
     * @return the column attributes, keyed by protocol field name, in the real server's order
     */
    public Map<String, Object> toMetadata() {
        final Map<String, Object> ret = new LinkedHashMap<String, Object>();
        ret.put("name", m_name);
        ret.put("type", m_type);
        ret.put("display_size", m_displaySize);
        ret.put("label", m_label);
        ret.put("precision", m_precision);
        ret.put("scale", m_scale);
        ret.put("autoIncrement", m_isAutoIncrement);
        ret.put("nullable", m_nullable);
        ret.put("readOnly", m_isReadOnly);
        ret.put("writeable", m_isWriteable);
        ret.put("table", m_table);
        return ret;
    }
}
