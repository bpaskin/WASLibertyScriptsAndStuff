package com.ibm.example.vault;

import java.io.PrintWriter;
import java.sql.SQLException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.util.Properties;
import java.util.logging.Logger;
import javax.sql.ConnectionPoolDataSource;
import javax.sql.PooledConnection;

/**
 * Non-XA factory for application-server-managed Vault connections.
 *
 * <p>Use this class as the traditional WebSphere JDBC provider implementation.
 * All Vault-specific JavaBean properties use String setters. Configure this as
 * a non-transactional data source: Vault does not implement JDBC transactions.
 * The application server owns the pool; this class creates physical sessions.
 */
public final class VaultConnectionPoolDataSource implements ConnectionPoolDataSource {
    private final Properties properties = new Properties();
    private String url;
    private int loginTimeout;
    private PrintWriter logWriter;

    public VaultConnectionPoolDataSource() { }

    @Override public PooledConnection getPooledConnection() throws SQLException {
        Properties snapshot;
        String target;
        int timeout;
        synchronized (this) {
            snapshot = (Properties) properties.clone();
            target = url;
            timeout = loginTimeout;
        }
        return open(target, snapshot, timeout);
    }

    @Override public PooledConnection getPooledConnection(String user, String password) throws SQLException {
        Properties snapshot;
        String target;
        int timeout;
        synchronized (this) {
            snapshot = (Properties) properties.clone();
            target = url;
            timeout = loginTimeout;
        }
        // Caller-supplied (e.g. J2C alias) credentials are never stored in the factory.
        snapshot.remove("user");
        snapshot.remove("password");
        if (user != null) snapshot.setProperty("user", user);
        if (password != null) snapshot.setProperty("password", password);
        return open(target, snapshot, timeout);
    }

    private PooledConnection open(String target, Properties snapshot, int timeout) throws SQLException {
        if (target == null || !target.startsWith(VaultDriver.PREFIX)) {
            throw VaultConfig.invalid("URL must start with jdbc:vault:");
        }
        VaultConfig config = VaultConfig.parse(target, snapshot);
        String user = snapshot.getProperty("user");
        String password = snapshot.getProperty("password");
        if (user == null || VaultConfig.isBlank(user) || password == null || password.isEmpty()) {
            throw new SQLInvalidAuthorizationSpecException("Both user and password properties are required", "28000");
        }
        return new VaultPooledConnection(VaultClient.login(config, user, password, timeout));
    }

    @Override public synchronized PrintWriter getLogWriter() { return logWriter; }
    @Override public synchronized void setLogWriter(PrintWriter writer) { logWriter = writer; }
    @Override public synchronized int getLoginTimeout() { return loginTimeout; }
    @Override public synchronized void setLoginTimeout(int seconds) throws SQLException {
        if (seconds < 0) throw VaultConfig.invalid("Login timeout must be non-negative");
        loginTimeout = seconds;
    }
    @Override public Logger getParentLogger() { return Logger.getLogger("com.ibm.example.vault"); }

    public synchronized String getURL() { return url; }
    public synchronized void setURL(String value) { url = value; }

    private synchronized String get(String key, String fallback) {
        return properties.getProperty(key, fallback);
    }
    private synchronized void set(String key, String value) {
        if (value == null) properties.remove(key);
        else properties.setProperty(key, value);
    }

    public String getUser() { return get("user", null); }
    public void setUser(String value) { set("user", value); }
    // Write-only JavaBean property to avoid password disclosure during introspection.
    public void setPassword(String value) { set("password", value); }
    public String getAuthMount() { return get("authMount", "userpass"); }
    public void setAuthMount(String value) { set("authMount", value); }
    public String getMount() { return get("mount", "secret"); }
    public void setMount(String value) { set("mount", value); }
    public String getKvVersion() { return get("kvVersion", "2"); }
    public void setKvVersion(String value) { set("kvVersion", value); }
    public String getNamespace() { return get("namespace", ""); }
    public void setNamespace(String value) { set("namespace", value); }
    public String getSecretPath() { return get("secretPath", null); }
    public void setSecretPath(String value) { set("secretPath", value); }
    public String getPasswordField() { return get("passwordField", "password"); }
    public void setPasswordField(String value) { set("passwordField", value); }
    public String getSecretVersion() { return get("secretVersion", null); }
    public void setSecretVersion(String value) { set("secretVersion", value); }
    public String getConnectTimeoutSeconds() { return get("connectTimeoutSeconds", "10"); }
    public void setConnectTimeoutSeconds(String value) { set("connectTimeoutSeconds", value); }
    public String getRequestTimeoutSeconds() { return get("requestTimeoutSeconds", "15"); }
    public void setRequestTimeoutSeconds(String value) { set("requestTimeoutSeconds", value); }
    public String getAllowHttp() { return get("allowHttp", "false"); }
    public void setAllowHttp(String value) { set("allowHttp", value); }
    public String getRevokeOnClose() { return get("revokeOnClose", "true"); }
    public void setRevokeOnClose(String value) { set("revokeOnClose", value); }
}
