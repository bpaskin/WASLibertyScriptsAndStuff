package com.ibm.example.vault;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ClientInfoStatus;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLClientInfoException;
import java.sql.SQLDataException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Factory for JDBC proxy objects ({@link java.sql.Connection},
 * {@link java.sql.Statement}, {@link java.sql.PreparedStatement},
 * {@link java.sql.ResultSet}, {@link java.sql.DatabaseMetaData},
 * {@link java.sql.ResultSetMetaData}) backed by a {@link VaultClient}.
 *
 * <p>Each object is a {@link java.lang.reflect.Proxy} that dispatches method
 * calls through an inner {@link Handler}. Unimplemented optional JDBC methods
 * throw {@link java.sql.SQLFeatureNotSupportedException} rather than silently
 * returning fake success values, making mis-use visible immediately.
 *
 * <p>All proxy method invocations are synchronised on the handler instance, so
 * concurrent access to a single connection is safe (though not recommended).
 */
final class JdbcObjects {
    private static final Logger LOG = Logger.getLogger(JdbcObjects.class.getName());

    private JdbcObjects() {}

    /**
     * Creates a new JDBC {@link Connection} proxy backed by the given Vault client.
     *
     * @param client authenticated Vault client; ownership is transferred to the
     *               returned connection and the client will be closed with it
     * @return a ready-to-use {@link Connection}
     */
    static Connection connection(VaultClient client) {
        LOG.log(Level.FINE, "connection: creating JDBC Connection proxy for base={0}",
                client.config.base());
        Conn handler = new Conn(client);
        handler.self = proxy(Connection.class, handler);
        return handler.self;
    }

    static Conn pooledHandle(VaultClient client, VaultPooledConnection pool) {
        Conn handler = new Conn(client, pool);
        handler.self = proxy(Connection.class, handler);
        return handler;
    }

    /**
     * Creates a JDK dynamic proxy of the given JDBC interface type, delegating
     * all invocations to {@code handler}.
     */
    private static <T> T proxy(Class<T> type, Handler handler) {
        return type.cast(Proxy.newProxyInstance(JdbcObjects.class.getClassLoader(), new Class<?>[]{type}, handler));
    }

    /**
     * Base invocation handler that intercepts the universal {@link Object} methods
     * ({@code toString}, {@code hashCode}, {@code equals}) and the JDBC wrapper
     * methods ({@code isWrapperFor}, {@code unwrap}) before delegating to the
     * type-specific {@link #call} implementation.
     */
    private abstract static class Handler implements InvocationHandler {
        final VaultPooledConnection pool;
        Handler() { this(null); }
        Handler(VaultPooledConnection pool) { this.pool = pool; }

        @Override public final Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            // A pooled session and all of its logical children share a lock, so
            // returning or retiring a session cannot race an in-flight query.
            synchronized (pool == null ? this : pool) {
                try {
                    return dispatch(proxy, method, args);
                } catch (SQLException error) {
                    failed(error);
                    throw error;
                }
            }
        }

        void failed(SQLException error) {
            if (pool != null) pool.connectionError(error);
        }

        private Object dispatch(Object proxy, Method method, Object[] args) throws SQLException {
            Object[] a = args == null ? new Object[0] : args;
            switch (method.getName()) {
                case "toString": return "VaultJDBC " + proxy.getClass().getInterfaces()[0].getSimpleName();
                case "hashCode": return System.identityHashCode(proxy);
                case "equals": return proxy == a[0];
                case "isWrapperFor": return a[0] instanceof Class<?> && ((Class<?>) a[0]).isInstance(proxy);
                case "unwrap": {
                    if (a[0] instanceof Class<?> && ((Class<?>) a[0]).isInstance(proxy)) {
                        return proxy;
                    }
                    throw unsupported("unwrap");
                }
                default: return call(method.getName(), a);
            }
        }
        abstract Object call(String name, Object[] args) throws SQLException;
    }

    /**
     * Handler for {@link Connection} proxies.
     *
     * <p>Tracks open statements so they can be closed when the connection is
     * closed. Delegates all Vault I/O to the underlying {@link VaultClient}.
     */
    static final class Conn extends Handler {
        final VaultClient client;
        final Set<Stmt> statements = new HashSet<>();
        Connection self;
        boolean closed;
        SQLWarning warnings;
        Conn(VaultClient client) { this(client, null); }
        Conn(VaultClient client, VaultPooledConnection pool) { super(pool); this.client = client; }
        void open() throws SQLException {
            if (closed) {
                throw new SQLException("Connection is closed", "08003");
            }
            if (pool != null) pool.checkUsable();
        }

        @Override Object call(String n, Object[] a) throws SQLException {
            // Short-circuit lifecycle queries before the open() guard so callers can
            // always ask whether a connection is closed, even after closing it.
            if (n.equals("isClosed")) return closed;
            if (n.equals("close")) { close(); return null; }
            if (n.equals("isValid")) {
                int timeout = (int) a[0];
                if (timeout < 0) {
                    throw VaultConfig.invalid("Validation timeout must be non-negative");
                }
                if (closed) return false;
                if (pool != null) {
                    try { pool.checkUsable(); }
                    catch (SQLException unusable) { return false; }
                }
                boolean valid = client.isValid(timeout);
                if (!valid && pool != null) {
                    pool.connectionError(new SQLException("Vault session validation failed", "08006"));
                }
                return valid;
            }
            // This method declares SQLClientInfoException, not SQLException.
            // Handle it before the generic guard to preserve that contract on
            // closed or failed sessions (including through the dynamic proxy).
            if (n.equals("setClientInfo")) { setClientInfo(a); return null; }
            // All remaining operations require the connection to be open.
            open();
            switch (n) {
                case "createStatement": case "prepareStatement": {
                    boolean prepared = n.equals("prepareStatement");
                    int offset = prepared ? 1 : 0;
                    // Validate result-set type/concurrency/holdability options if supplied.
                    checkOptions(a, offset);
                    // Parse the SQL at prepare time for PreparedStatement; defer to
                    // execute time for plain Statement.
                    SecretQuery query = prepared ? SecretQuery.parse((String) a[0]) : null;
                    LOG.log(Level.FINE, "{0}: creating {1}", new Object[]{n, prepared ? "PreparedStatement sql=" + a[0] : "Statement"});
                    Stmt stmt = new Stmt(this, query);
                    stmt.self = prepared ? proxy(PreparedStatement.class, stmt) : proxy(Statement.class, stmt);
                    statements.add(stmt);
                    return stmt.self;
                }
                case "getMetaData": return proxy(DatabaseMetaData.class, new DbMeta(this));
                case "isReadOnly": case "getAutoCommit": return true;
                case "setReadOnly": case "setAutoCommit": {
                    if (!(boolean) a[0]) {
                        throw unsupported(n + "(false)");
                    }
                    return null;
                }
                case "getTransactionIsolation": return Connection.TRANSACTION_NONE;
                case "setTransactionIsolation": {
                    if ((int) a[0] != Connection.TRANSACTION_NONE) {
                        throw unsupported(n);
                    }
                    return null;
                }
                case "getHoldability": return ResultSet.CLOSE_CURSORS_AT_COMMIT;
                case "setHoldability": {
                    if ((int) a[0] != ResultSet.CLOSE_CURSORS_AT_COMMIT) {
                        throw unsupported(n);
                    }
                    return null;
                }
                // Vault exposes only scalar strings, with no SQL user-defined
                // types or client-info properties. Containers still need to
                // capture and restore these empty defaults during checkout.
                case "getTypeMap": return new HashMap<String, Class<?>>();
                case "setTypeMap": {
                    if (a[0] == null) throw VaultConfig.invalid("Type map must not be null");
                    if (!((Map<?, ?>) a[0]).isEmpty()) {
                        throw unsupported("Non-empty SQL type maps");
                    }
                    return null;
                }
                case "getClientInfo": return a.length == 0 ? new Properties() : null;
                case "getWarnings": return warnings;
                case "clearWarnings": warnings = null; return null;
                case "getCatalog": return null;
                case "getSchema": return "vault";
                default: throw unsupported(n);
            }
        }

        private void setClientInfo(Object[] a) throws SQLClientInfoException {
            Map<String, ClientInfoStatus> failed = new HashMap<>();
            try {
                open();
            } catch (SQLException error) {
                throw new SQLClientInfoException(error.getMessage(), error.getSQLState(), failed, error);
            }
            if (a[0] == null) {
                throw new SQLClientInfoException("Client info argument must not be null", "HY024", failed);
            }
            if (a.length == 1) {
                for (String name : ((Properties) a[0]).stringPropertyNames()) {
                    failed.put(name, ClientInfoStatus.REASON_UNKNOWN_PROPERTY);
                }
            } else if (a[1] != null) {
                failed.put((String) a[0], ClientInfoStatus.REASON_UNKNOWN_PROPERTY);
            }
            // An empty property set or clearing an unset property is a valid
            // reset. Do not claim to store arbitrary client info in Vault.
            if (!failed.isEmpty()) {
                SQLWarning warning = new SQLWarning("Vault does not support client info properties", "01S00");
                if (warnings == null) warnings = warning;
                else warnings.setNextWarning(warning);
                throw new SQLClientInfoException("Vault does not support client info properties", "0A000", failed);
            }
        }

        void close() throws SQLException {
            close(true);
        }

        void close(boolean notifyPool) throws SQLException {
            if (closed) {
                return;
            }
            LOG.fine("Connection.close: closing connection handle");
            closed = true;
            warnings = null;
            // Close all open statements before revoking the token so any in-flight
            // reads finish (or time out) before the token disappears.
            for (Stmt stmt : new ArrayList<>(statements)) stmt.close();
            if (pool == null) {
                // Standalone connections own the physical Vault session.
                client.close();
            } else if (notifyPool) {
                pool.logicalClosed(this);
            }
            LOG.fine("Connection.close: connection closed");
        }

        private static void checkOptions(Object[] a, int offset) throws SQLException {
            if (a.length == offset) return;
            if ((a.length != offset + 2 && a.length != offset + 3)
                    || !(a[offset] instanceof Integer) || !(a[offset + 1] instanceof Integer)
                    || (int) a[offset] != ResultSet.TYPE_FORWARD_ONLY || (int) a[offset + 1] != ResultSet.CONCUR_READ_ONLY
                    || (a.length == offset + 3 && (int) a[offset + 2] != ResultSet.CLOSE_CURSORS_AT_COMMIT))
                throw unsupported("Only forward-only, read-only result sets without generated keys are supported");
        }
    }

    /**
     * Handler for {@link java.sql.Statement} and {@link java.sql.PreparedStatement}
     * proxies.
     *
     * <p>For prepared statements a {@link SecretQuery} is parsed at prepare time;
     * parameter values are stored in {@link #parameters} and used at execute time.
     * For plain statements the SQL is parsed on each execution.
     */
    private static final class Stmt extends Handler {
        final Conn connection;
        final SecretQuery preparedQuery;
        final String[] parameters;
        Statement self;
        Row result;
        boolean closed;
        boolean closeOnCompletion;
        int queryTimeout;
        int maxRows;
        int fetchSize;

        Stmt(Conn connection, SecretQuery query) {
            super(connection.pool);
            this.connection = connection;
            this.preparedQuery = query;
            this.parameters = query == null ? new String[0] : new String[query.parameterCount()];
        }

        @Override void failed(SQLException error) {
            if (pool != null) {
                pool.connectionError(error, preparedQuery == null ? null : (PreparedStatement) self);
            }
        }

        void open() throws SQLException {
            connection.open();
            if (closed) {
                throw new SQLException("Statement is closed", "HY010");
            }
        }

        @Override Object call(String n, Object[] a) throws SQLException {
            if (n.equals("isClosed")) return closed || connection.closed;
            if (n.equals("close")) { close(); return null; }
            open();
            switch (n) {
                case "executeQuery": case "execute": {
                    SecretQuery query;
                    if (preparedQuery == null) {
                        // Plain Statement: parse the SQL passed to executeQuery/execute.
                        if (a.length != 1) {
                            throw unsupported(n);
                        }
                        query = SecretQuery.parse((String) a[0]);
                        // Parameterised queries require PreparedStatement so that
                        // parameter values are bound separately and never interpolated.
                        if (query.parameterCount() != 0) {
                            throw new SQLException("Use PreparedStatement for parameterized queries", "07001");
                        }
                    } else {
                        // PreparedStatement: use the query parsed at prepare time.
                        if (a.length != 0) {
                            throw new SQLException("Do not pass SQL to an existing PreparedStatement", "HY010");
                        }
                        query = preparedQuery;
                    }
                    // Ensure all parameter slots have been bound before executing.
                    for (String parameter : parameters) {
                        if (parameter == null) {
                            throw new SQLException("All query parameters must be set to non-null strings", "07001");
                        }
                    }
                    // Close any previous ResultSet — JDBC specifies that re-executing a
                    // statement implicitly closes the previous result.
                    if (result != null) { result.close(); result = null; }
                    open();
                    // Resolve path and field from bound parameters or configured defaults.
                    String path = query.parameterCount() > 0 ? parameters[0] : connection.client.config.secretPath();
                    String field = query.parameterCount() > 1 ? parameters[1] : connection.client.config.passwordField();
                    LOG.log(Level.FINE, "executeQuery: path={0} field={1} queryTimeout={2}s",
                            new Object[]{path, field, queryTimeout});
                    // Perform the Vault KV read — this is the only network call per execute.
                    String value = connection.client.read(path, field, queryTimeout);
                    LOG.fine("executeQuery: secret retrieved, wrapping in ResultSet");
                    // Wrap the returned secret string in a single-row ResultSet proxy.
                    result = new Row(this, value);
                    result.self = proxy(ResultSet.class, result);
                    return n.equals("execute") ? Boolean.TRUE : result.self;
                }
                case "setString": case "setObject": {
                    if (preparedQuery == null || a.length != 2) {
                        throw unsupported(n);
                    }
                    int index = (int) a[0];
                    if (index < 1 || index > parameters.length) {
                        throw new SQLException("Invalid parameter index", "07009");
                    }
                    if (!(a[1] instanceof String)) {
                        throw new SQLDataException("Parameters must be non-null strings", "22005");
                    }
                    parameters[index - 1] = (String) a[1];
                    return null;
                }
                case "clearParameters": { java.util.Arrays.fill(parameters, null); return null; }
                case "getConnection": return connection.self;
                case "getResultSet": return result == null || result.closed ? null : result.self;
                case "getUpdateCount": return -1;
                case "getLargeUpdateCount": return -1L;
                case "getMoreResults": {
                    if (a.length > 0 && (int) a[0] != Statement.CLOSE_CURRENT_RESULT && (int) a[0] != Statement.CLOSE_ALL_RESULTS) {
                        throw unsupported(n);
                    }
                    if (result != null) { result.close(); result = null; }
                    return false;
                }
                case "getMetaData": return columnMetadata();
                case "getQueryTimeout": return queryTimeout;
                case "setQueryTimeout": { queryTimeout = nonNegative((int) a[0]); return null; }
                case "getMaxRows": return maxRows;
                case "setMaxRows": { maxRows = nonNegative((int) a[0]); return null; }
                case "getFetchSize": return fetchSize;
                case "setFetchSize": { fetchSize = nonNegative((int) a[0]); return null; }
                case "getFetchDirection": return ResultSet.FETCH_FORWARD;
                case "setFetchDirection": {
                    if ((int) a[0] != ResultSet.FETCH_FORWARD) {
                        throw unsupported(n);
                    }
                    return null;
                }
                case "getResultSetType": return ResultSet.TYPE_FORWARD_ONLY;
                case "getResultSetConcurrency": return ResultSet.CONCUR_READ_ONLY;
                case "getResultSetHoldability": return ResultSet.CLOSE_CURSORS_AT_COMMIT;
                case "getMaxFieldSize": return 0;
                case "setMaxFieldSize": {
                    if ((int) a[0] != 0) {
                        throw unsupported(n);
                    }
                    return null;
                }
                case "closeOnCompletion": { closeOnCompletion = true; return null; }
                case "isCloseOnCompletion": return closeOnCompletion;
                case "isPoolable": return false;
                case "setPoolable": {
                    if ((boolean) a[0]) {
                        throw unsupported(n);
                    }
                    return null;
                }
                case "getWarnings": case "clearWarnings": return null;
                default: throw unsupported(n);
            }
        }

        void close() {
            if (closed) {
                return;
            }
            LOG.fine("Statement.close: closing statement");
            closed = true;
            if (result != null) {
                result.close();
                result = null;
            }
            java.util.Arrays.fill(parameters, null);
            connection.statements.remove(this);
            if (pool != null && preparedQuery != null) {
                pool.statementClosed((PreparedStatement) self);
            }
        }
    }

    /**
     * Handler for {@link java.sql.ResultSet} proxies.
     *
     * <p>Holds a single row containing the string value retrieved from Vault.
     * The cursor starts before the row ({@code position == 0}), advances to the
     * row on the first {@code next()} call, and moves past it on the second.
     */
    private static final class Row extends Handler {
        final Stmt statement;
        ResultSet self;
        String value;
        int position;
        boolean closed;
        boolean read;
        Row(Stmt statement, String value) {
            super(statement.pool);
            this.statement = statement;
            this.value = value;
        }

        @Override Object call(String n, Object[] a) throws SQLException {
            if (n.equals("isClosed")) return closed || statement.closed || statement.connection.closed;
            if (n.equals("close")) { close(); return null; }
            statement.open();
            if (closed) {
                throw new SQLException("ResultSet is closed", "HY010");
            }
            switch (n) {
                case "next": { position = Math.min(2, position + 1); read = false; return position == 1; }
                case "getString": case "getObject": {
                    column(a[0]);
                    if (position != 1) {
                        throw new SQLException("ResultSet cursor is not on a row", "24000");
                    }
                    if (a.length == 2 && a[1] != String.class && a[1] != Object.class) {
                        throw unsupported(n);
                    }
                    read = true;
                    return value;
                }
                case "findColumn": { column(a[0]); return 1; }
                case "wasNull": {
                    if (!read) {
                        throw new SQLException("Read a column before calling wasNull", "HY010");
                    }
                    return false;
                }
                case "getRow": return position == 1 ? 1 : 0;
                case "isBeforeFirst": return position == 0;
                case "isAfterLast": return position == 2;
                case "isFirst": case "isLast": return position == 1;
                case "getMetaData": return columnMetadata();
                case "getStatement": return statement.self;
                case "getType": return ResultSet.TYPE_FORWARD_ONLY;
                case "getConcurrency": return ResultSet.CONCUR_READ_ONLY;
                case "getHoldability": return ResultSet.CLOSE_CURSORS_AT_COMMIT;
                case "getFetchDirection": return ResultSet.FETCH_FORWARD;
                case "getFetchSize": return 1;
                case "setFetchSize": { nonNegative((int) a[0]); return null; }
                case "setFetchDirection": {
                    if ((int) a[0] != ResultSet.FETCH_FORWARD) {
                        throw unsupported(n);
                    }
                    return null;
                }
                case "rowUpdated": case "rowInserted": case "rowDeleted": return false;
                case "getWarnings": case "clearWarnings": return null;
                default: throw unsupported(n);
            }
        }

        void close() {
            if (closed) {
                return;
            }
            closed = true;
            value = null;
            if (statement.closeOnCompletion && !statement.closed) {
                statement.close();
            }
        }

        private static void column(Object selector) throws SQLException {
            if ((selector instanceof Integer && (Integer) selector == 1)
                    || (selector instanceof String && ((String) selector).equalsIgnoreCase("password"))) return;
            throw new SQLException("Column must be 1 or password", "07009");
        }
    }

    /**
     * Handler for {@link java.sql.DatabaseMetaData} proxies.
     *
     * <p>Returns static driver and capability metadata. Unsupported discovery
     * methods (table/column enumeration, etc.) throw
     * {@link java.sql.SQLFeatureNotSupportedException}.
     */
    private static final class DbMeta extends Handler {
        final Conn connection;
        DbMeta(Conn connection) { super(connection.pool); this.connection = connection; }
        @Override Object call(String n, Object[] a) throws SQLException {
            connection.open();
            switch (n) {
                case "getConnection": return connection.self;
                case "getURL": return VaultDriver.PREFIX + connection.client.config.base();
                case "getDatabaseProductName": return "HashiCorp Vault KV";
                case "getDatabaseProductVersion": return "KV " + connection.client.config.kvVersion();
                case "getDriverName": return "Vault Secret JDBC Driver";
                case "getDriverVersion": return "1.0.2";
                case "getDriverMajorVersion": return 1;
                case "getDriverMinorVersion": return 0;
                case "getJDBCMajorVersion": return 4;
                case "getJDBCMinorVersion": return 2;
                case "isReadOnly": case "allProceduresAreCallable": case "allTablesAreSelectable": return n.equals("isReadOnly");
                case "supportsTransactions": case "supportsBatchUpdates": case "supportsStoredProcedures": case "supportsSavepoints": case "supportsGetGeneratedKeys": case "supportsMultipleResultSets": case "supportsMultipleOpenResults": case "supportsStatementPooling": case "supportsANSI92EntryLevelSQL": return false;
                case "supportsResultSetType": return (int) a[0] == ResultSet.TYPE_FORWARD_ONLY;
                case "supportsResultSetConcurrency": return (int) a[0] == ResultSet.TYPE_FORWARD_ONLY && (int) a[1] == ResultSet.CONCUR_READ_ONLY;
                case "supportsResultSetHoldability": return (int) a[0] == ResultSet.CLOSE_CURSORS_AT_COMMIT;
                case "getResultSetHoldability": return ResultSet.CLOSE_CURSORS_AT_COMMIT;
                case "getDefaultTransactionIsolation": return Connection.TRANSACTION_NONE;
                case "supportsTransactionIsolationLevel": return (int) a[0] == Connection.TRANSACTION_NONE;
                case "getSQLStateType": return DatabaseMetaData.sqlStateSQL;
                case "getIdentifierQuoteString": return " ";
                default: throw unsupported(n);
            }
        }
    }

    /**
     * Returns a {@link java.sql.ResultSetMetaData} proxy describing the single
     * {@code password VARCHAR} column common to all result sets produced by this driver.
     */
    private static ResultSetMetaData columnMetadata() {
        return proxy(ResultSetMetaData.class, new Handler() {
            @Override Object call(String n, Object[] a) throws SQLException {
                if (n.equals("getColumnCount")) return 1;
                if (a.length == 1) Row.column(a[0]);
                switch (n) {
                    case "getColumnName": case "getColumnLabel": return "password";
                    case "getColumnType": return Types.VARCHAR;
                    case "getColumnTypeName": return "VARCHAR";
                    case "getColumnClassName": return String.class.getName();
                    case "getTableName": return "vault_secret";
                    case "getSchemaName": return "vault";
                    case "getCatalogName": return "";
                    case "isNullable": return ResultSetMetaData.columnNoNulls;
                    case "isReadOnly": case "isCaseSensitive": return true;
                    case "isAutoIncrement": case "isCurrency": case "isSearchable": case "isSigned": case "isWritable": case "isDefinitelyWritable": return false;
                    case "getColumnDisplaySize": case "getPrecision": case "getScale": return 0;
                    default: throw unsupported(n);
                }
            }
        });
    }

    /**
     * Validates that {@code value} is non-negative (≥ 0).
     *
     * @param value the value to check
     * @return the value unchanged
     * @throws SQLException with state {@code HY024} if {@code value < 0}
     */
    private static int nonNegative(int value) throws SQLException {
        if (value < 0) {
            throw VaultConfig.invalid("Value must be non-negative");
        }
        return value;
    }

    /**
     * Creates a {@link java.sql.SQLFeatureNotSupportedException} with state
     * {@code 0A000} for JDBC operations not implemented by this driver.
     *
     * @param operation descriptive name of the unsupported operation
     * @return the exception (callers use {@code throw unsupported(...)})
     */
    private static SQLFeatureNotSupportedException unsupported(String operation) {
        return new SQLFeatureNotSupportedException("Unsupported JDBC operation: " + operation, "0A000");
    }
}
