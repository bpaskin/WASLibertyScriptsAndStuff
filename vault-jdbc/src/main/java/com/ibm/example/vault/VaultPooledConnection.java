package com.ibm.example.vault;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.sql.ConnectionEvent;
import javax.sql.ConnectionEventListener;
import javax.sql.PooledConnection;
import javax.sql.StatementEvent;
import javax.sql.StatementEventListener;

/** One authenticated Vault session owned by an application server's pool. */
final class VaultPooledConnection implements PooledConnection {
    private final VaultClient client;
    private final List<ConnectionEventListener> connectionListeners = new ArrayList<>();
    private final List<StatementEventListener> statementListeners = new ArrayList<>();
    private JdbcObjects.Conn active;
    private boolean closed;
    private boolean failed;

    VaultPooledConnection(VaultClient client) { this.client = client; }

    @Override public synchronized Connection getConnection() throws SQLException {
        if (closed) throw new SQLException("Pooled connection is closed", "08003");
        if (failed) throw new SQLException("Pooled connection is no longer usable", "08006");
        // JDBC permits only one live logical handle per physical connection.
        if (active != null) active.close(false);
        checkUsable();
        active = JdbcObjects.pooledHandle(client, this);
        return active.self;
    }

    @Override public synchronized void close() throws SQLException {
        if (closed) return;
        closed = true;
        try {
            if (active != null) active.close(false);
        } finally {
            active = null;
            connectionListeners.clear();
            statementListeners.clear();
            // Only physical close revokes the token (unless revokeOnClose=false).
            client.close();
        }
    }

    synchronized void logicalClosed(JdbcObjects.Conn handle) {
        if (active != handle) return;
        active = null;
        if (!closed && !failed) {
            ConnectionEvent event = new ConnectionEvent(this);
            for (ConnectionEventListener listener : new ArrayList<>(connectionListeners)) {
                listener.connectionClosed(event);
            }
        }
    }

    synchronized void connectionError(SQLException error) {
        connectionError(error, null);
    }

    synchronized void connectionError(SQLException error, PreparedStatement statement) {
        if (closed || failed || !isFatal(error)) return;
        // Mark the session unusable before calling listeners, which may close
        // the logical handle or reenter the pool during statement-error cleanup.
        failed = true;
        List<ConnectionEventListener> listeners = new ArrayList<>(connectionListeners);
        if (statement != null) {
            StatementEvent statementEvent = new StatementEvent(this, statement, error);
            for (StatementEventListener listener : new ArrayList<>(statementListeners)) {
                listener.statementErrorOccurred(statementEvent);
            }
        }
        ConnectionEvent event = new ConnectionEvent(this, error);
        for (ConnectionEventListener listener : listeners) {
            listener.connectionErrorOccurred(event);
        }
    }

    static boolean isFatal(SQLException error) {
        String state = error.getSQLState();
        // A stale logical handle (08003) must not evict a later borrower.
        return state != null && ((state.startsWith("08") && !state.equals("08003"))
                || state.equals("28000") || state.equals("HYT00"));
    }

    synchronized void checkUsable() throws SQLException {
        if (closed) throw new SQLException("Pooled connection is closed", "08003");
        if (failed) throw new SQLException("Pooled connection is no longer usable", "08006");
    }

    synchronized void statementClosed(PreparedStatement statement) {
        StatementEvent event = new StatementEvent(this, statement);
        for (StatementEventListener listener : new ArrayList<>(statementListeners)) {
            listener.statementClosed(event);
        }
    }

    @Override public synchronized void addConnectionEventListener(ConnectionEventListener listener) {
        if (listener != null && !closed) connectionListeners.add(listener);
    }
    @Override public synchronized void removeConnectionEventListener(ConnectionEventListener listener) {
        connectionListeners.remove(listener);
    }
    @Override public synchronized void addStatementEventListener(StatementEventListener listener) {
        if (listener != null && !closed) statementListeners.add(listener);
    }
    @Override public synchronized void removeStatementEventListener(StatementEventListener listener) {
        statementListeners.remove(listener);
    }
}
