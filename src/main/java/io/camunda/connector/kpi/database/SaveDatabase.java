package io.camunda.connector.kpi.database;

import io.camunda.connector.api.error.ConnectorException;
import io.camunda.connector.kpi.KpiError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class SaveDatabase {
    private final Logger logger = LoggerFactory.getLogger(SaveDatabase.class.getName());

    /**
     * Save the KPI record as one row of tableName, via a connection cached by JdbcConnectionFactory.
     *
     * @param jdbcString the JDBC connection string
     * @param tableName  the table to insert into
     * @param kpiRecord  the computed KPI record: field name -> value
     * @throws ConnectorException if the connection cannot be opened, the table does not exist,
     *                            no kpiRecord field matches any column of the table, or the insert fails
     */
    public void save(String jdbcString, String tableName, Map<String, Object> kpiRecord) throws ConnectorException {
        try {
            Connection connection = JdbcConnectionFactory.getInstance().getConnection(jdbcString);
            ResolvedTable resolvedTable = introspectTable(connection, tableName);
            Map<String, Object> rowData = buildRowData(resolvedTable.columnTypes(), kpiRecord);
            executeInsert(connection, resolvedTable.tableName(), rowData);
        } catch (SQLException e) {
            throw new ConnectorException(KpiError.OPERATION_EXECUTION,
                    "Error saving KPI record to table [" + tableName + "]: " + e.getMessage());
        }
    }

    /**
     * @param connection an open JDBC connection
     * @param tableName  the table to introspect
     * @return the resolved table name (the candidate that actually matched) and its columns (name -> java.sql.Types SQL type)
     * @throws ConnectorException if tableName does not exist (tried as given, then upper-case, then lower-case,
     *                            since databases differ on unquoted identifier case-folding)
     * @throws SQLException       if the JDBC driver cannot read the table's metadata
     */
    private ResolvedTable introspectTable(Connection connection, String tableName) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        for (String candidateName : List.of(tableName, tableName.toUpperCase(), tableName.toLowerCase())) {
            Map<String, Integer> columnTypes = new LinkedHashMap<>();
            try (ResultSet resultSet = metaData.getColumns(null, null, candidateName, null)) {
                while (resultSet.next()) {
                    columnTypes.put(resultSet.getString("COLUMN_NAME"), resultSet.getInt("DATA_TYPE"));
                }
            }
            if (!columnTypes.isEmpty()) {
                return new ResolvedTable(candidateName, columnTypes);
            }
        }
        throw new ConnectorException(KpiError.BAD_INPUTPARAMETER,
                "Table [" + tableName + "] does not exist, or has no columns");
    }

    /**
     * Keep only the kpiRecord fields that match one of the table's columns (case-insensitive), keyed by
     * the column's actual name so executeInsert can use it as-is in the generated SQL.
     *
     * @param columnTypes the table's columns, as returned by introspectTable
     * @param kpiRecord   the computed KPI record: field name -> value
     * @return column name -> value, for every kpiRecord field that matches a column
     */
    private Map<String, Object> buildRowData(Map<String, Integer> columnTypes, Map<String, Object> kpiRecord) {
        Map<String, Object> rowData = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : kpiRecord.entrySet()) {
            for (String columnName : columnTypes.keySet()) {
                if (columnName.equalsIgnoreCase(entry.getKey())) {
                    rowData.put(columnName, entry.getValue());
                    break;
                }
            }
        }
        return rowData;
    }

    /**
     * @param connection an open JDBC connection
     * @param tableName  the table to insert into
     * @param rowData    column name -> value to insert, as built by buildRowData
     * @throws ConnectorException if rowData is empty (no kpiRecord field matched any column) or the insert fails
     */
    private void executeInsert(Connection connection, String tableName, Map<String, Object> rowData) throws ConnectorException {
        if (rowData.isEmpty()) {
            throw new ConnectorException(KpiError.DATABASE_NOT_MATCH, "No column of table [" + tableName + "] matches any field of the KPI record");
        }
        // tableName/column names come from DatabaseMetaData.getColumns(), i.e. exactly what the catalog stores.
        // If the table/columns were created quoted (preserving mixed/upper case), an unquoted reference here would
        // get folded to lowercase by the SQL parser and fail to resolve - quoting keeps the exact catalog case.
        String columns = rowData.keySet().stream().map(c -> "\"" + c + "\"").collect(Collectors.joining(", "));
        String placeholders = rowData.keySet().stream().map(c -> "?").collect(Collectors.joining(", "));
        String sql = "INSERT INTO \"" + tableName + "\" (" + columns + ") VALUES (" + placeholders + ")";

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            for (Object value : rowData.values()) {
                // the postgres JDBC driver can infer a SQL type for LocalDate/LocalTime/LocalDateTime/OffsetDateTime,
                // but not for java.time.Instant (not part of the JDBC 4.2 type-mapping table) - convert it to
                // java.sql.Timestamp, which the driver maps to TIMESTAMPTZ, before handing it to setObject.
                Object jdbcValue = value instanceof Instant instant ? Timestamp.from(instant) : value;
                statement.setObject(index++, jdbcValue);
            }
            statement.executeUpdate();
            logger.info("Executed [{}]", sql);
        } catch (Exception e) {
            try {
                logger.error("Can't execute [{}] sql in database [{}]", sql, connection.getCatalog(), e);
                throw new ConnectorException(KpiError.SQL_ERROR, "During sql " + sql + " on database " + connection.getCatalog());
            } catch (SQLException e1) {
                logger.error("getCatalog failed during exception log " + e1.getMessage() + " : ", e1);
            }
        }
    }

    private record ResolvedTable(String tableName, Map<String, Integer> columnTypes) {
    }
}
