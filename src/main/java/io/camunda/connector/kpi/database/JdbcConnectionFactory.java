package io.camunda.connector.kpi.database;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Caches one JDBC connection per distinct connection string, so repeated KPI executions against the
 * same database reuse an open connection instead of opening a new one every time. No pooling library,
 * no Spring: a plain in-memory cache, guarded by synchronized methods since a runtime may execute jobs
 * concurrently.
 * <p>
 * Each cached connection tracks its last-usage time; checkConnection() closes and evicts whichever
 * connections have been idle longer than the given duration.
 */
public class JdbcConnectionFactory {

    private static final JdbcConnectionFactory INSTANCE = new JdbcConnectionFactory();
    private final Map<String, CachedConnection> connectionsByJdbcString = new HashMap<>();

    public static JdbcConnectionFactory getInstance() {
        return INSTANCE;
    }

    /**
     * Get a connection for jdbcString: reuse the cached one if it is still open, otherwise open a new
     * one (via DriverManager - the JDBC driver for the target database must already be on the
     * classpath) and cache it. Updates the connection's last-usage time either way.
     *
     * @param jdbcString the JDBC connection string
     * @return an open connection
     * @throws SQLException if a new connection needs to be opened and that fails
     */
    public synchronized Connection getConnection(String jdbcString) throws SQLException {
        CachedConnection cached = connectionsByJdbcString.get(jdbcString);
        if (cached != null && !cached.connection.isClosed()) {
            cached.lastUsageMs = System.currentTimeMillis();
            return cached.connection;
        }
        Connection connection = DriverManager.getConnection(jdbcString);
        connectionsByJdbcString.put(jdbcString, new CachedConnection(connection));
        return connection;
    }

    /**
     * Close and evict every cached connection whose last usage is older than maxIdle. A connection
     * that is already closed (e.g. dropped by the database side) is evicted too, closing being a no-op.
     *
     * @param maxIdle the maximum idle duration before a connection is closed
     */
    public synchronized void checkConnection(Duration maxIdle) {
        long now = System.currentTimeMillis();
        connectionsByJdbcString.entrySet().removeIf(entry -> {
            CachedConnection cached = entry.getValue();
            boolean idleTooLong = now - cached.lastUsageMs > maxIdle.toMillis();
            if (idleTooLong) {
                try {
                    cached.connection.close();
                } catch (SQLException e) {
                    // already unusable either way, we're evicting it from the cache regardless
                }
            }
            return idleTooLong;
        });
    }

    private static class CachedConnection {
        private final Connection connection;
        private long lastUsageMs;

        private CachedConnection(Connection connection) {
            this.connection = connection;
            this.lastUsageMs = System.currentTimeMillis();
        }
    }
}
