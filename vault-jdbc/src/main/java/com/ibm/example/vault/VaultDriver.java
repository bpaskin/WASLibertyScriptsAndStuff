package com.ibm.example.vault;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * JDBC {@link Driver} implementation for HashiCorp Vault KV secrets.
 *
 * <p>Registered automatically via the Java Service Provider Interface; the
 * {@code META-INF/services/java.sql.Driver} file names this class so that
 * {@link DriverManager} discovers it when the JAR is on the classpath.
 *
 * <p>JDBC URL format: {@code jdbc:vault:https://host:port}
 *
 * <p>Required connection properties:
 * <ul>
 *   <li>{@code user} — Vault username</li>
 *   <li>{@code password} — Vault login password</li>
 * </ul>
 *
 * <p>Optional properties and their defaults are listed in
 * {@link #getPropertyInfo(String, Properties)}.
 */
public final class VaultDriver implements Driver {
    /** Prefix that all Vault JDBC URLs must start with. */
    public static final String PREFIX = "jdbc:vault:";

    private static final Logger LOG = Logger.getLogger(VaultDriver.class.getName());

    static {
        try {
            DriverManager.registerDriver(new VaultDriver());
            LOG.fine("VaultDriver registered with DriverManager");
        } catch (SQLException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /**
     * Creates a JDBC connection backed by a Vault client token obtained via the
     * configured auth mount.
     *
     * <p>Returns {@code null} for URLs not recognised by this driver (as required
     * by the JDBC specification).
     *
     * @param url  JDBC URL of the form {@code jdbc:vault:https://host:port}
     * @param info connection properties; must supply {@code user} and {@code password}
     * @return a new {@link Connection}, or {@code null} if the URL is not handled
     * @throws SQLException if authentication fails or the configuration is invalid
     */
    @Override
    public Connection connect(String url, Properties info) throws SQLException {
        if (!acceptsURL(url)) {
            LOG.log(Level.FINE, "connect: URL not handled by this driver: {0}", url);
            return null;
        }

        LOG.log(Level.FINE, "connect: accepted URL {0}", url);
        Properties p = info == null ? new Properties() : info;
        VaultConfig config = VaultConfig.parse(url, p);
        String user = p.getProperty("user");
        String password = p.getProperty("password");

        if (user == null || VaultConfig.isBlank(user) || password == null || password.isEmpty()) {
            throw new SQLInvalidAuthorizationSpecException("Both user and password properties are required", "28000");
        }

        LOG.log(Level.FINE, "connect: logging in as user={0}", user);
        Connection conn = JdbcObjects.connection(VaultClient.login(config, user, password));
        LOG.fine("connect: connection established");
        return conn;
    }

    /**
     * Returns {@code true} if this driver handles the given URL (i.e. the URL starts
     * with {@code jdbc:vault:}).
     */
    @Override
    public boolean acceptsURL(String url) {
        boolean accepted = url != null && url.startsWith(PREFIX);
        LOG.log(Level.FINE, "acceptsURL({0}) = {1}", new Object[]{url, accepted});
        return accepted;
    }

    @Override
    public int getMajorVersion() {
        return 1;
    }

    @Override
    public int getMinorVersion() {
        return 0;
    }

    /**
     * Returns {@code false}; Vault does not implement SQL-92 or the full JDBC contract.
     */
    @Override
    public boolean jdbcCompliant() {
        return false;
    }

    /**
     * Not supported — this driver does not use {@link java.util.logging}.
     *
     * @throws SQLFeatureNotSupportedException always
     */
    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException("Driver does not use logging", "0A000");
    }

    /**
     * Returns metadata describing all recognised connection properties and their
     * defaults.
     *
     * <p>The {@code password} property value is always returned as {@code null} to
     * prevent credentials from appearing in tooling that calls this method for
     * introspection.
     *
     * @param url  the JDBC URL (used only as context; not validated here)
     * @param info current connection properties used to populate default values
     * @return array of property descriptors
     */
    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
        Properties p = info == null ? new Properties() : info;

        String[][] entries = {
                { "user", null, "Vault username (or LDAP username at the configured auth mount)" },
                { "password", null, "Vault login password; never returned by property introspection" },
                { "authMount", "userpass", "Authentication mount relative to auth/" },
                { "mount", "secret", "KV secrets engine mount" },
                { "kvVersion", "2", "KV engine version: 1 or 2" },
                { "namespace", "", "Optional Vault namespace" },
                { "secretPath", null, "Default secret path relative to mount" },
                { "passwordField", "password", "Default literal field name in the secret" },
                { "secretVersion", null, "Optional positive KV v2 secret version" },
                { "connectTimeoutSeconds", "10", "TCP/TLS connection timeout" },
                { "requestTimeoutSeconds", "15", "Overall HTTP request timeout" },
                { "allowHttp", "false", "Allow HTTP only for localhost/127.0.0.1/[::1] development" },
                { "revokeOnClose", "true", "Revoke the driver-created token on close" }
        };

        DriverPropertyInfo[] result = new DriverPropertyInfo[entries.length];

        for (int i = 0; i < entries.length; i++) {
            String[] e = entries[i];
            result[i] = new DriverPropertyInfo(e[0], e[0].equals("password") ? null : p.getProperty(e[0], e[1]));
            result[i].required = i < 2;
            result[i].description = e[2];

            if (e[0].equals("kvVersion")) {
                result[i].choices = new String[] { "1", "2" };
            }
            if (e[0].equals("allowHttp") || e[0].equals("revokeOnClose")) {
                result[i].choices = new String[] { "true", "false" };
            }
        }
        return result;
    }
}
