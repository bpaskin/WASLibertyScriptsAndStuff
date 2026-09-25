package com.ibm.example.vault;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.io.UnsupportedEncodingException;
import java.sql.SQLException;
import java.util.Properties;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Immutable, validated driver configuration parsed from the JDBC URL and
 * {@link java.util.Properties}.
 *
 * <p>Credentials (username, password, client token) are intentionally excluded;
 * this object is safe to log or share without risking secret exposure.
 *
 * <p>Instances are created via {@link #parse(String, Properties)} and are
 * effectively final value objects.
 */
final class VaultConfig {
    private static final Logger LOG = Logger.getLogger(VaultConfig.class.getName());

    /** Vault base URL ({@code https://host:port}), used to construct API endpoints. */
    private final URI base;

    /** Auth mount path relative to {@code auth/} (e.g. {@code userpass}). */
    private final String authMount;

    /** KV secrets engine mount path (e.g. {@code secret}). */
    private final String mount;

    /** KV engine version: {@code 1} or {@code 2}. */
    private final int kvVersion;

    /** Optional Vault namespace header value; empty string means no namespace. */
    private final String namespace;

    /** Default secret path used when the SQL query has no {@code path} parameter. */
    private final String secretPath;

    /** Field name to read from the secret (default {@code password}). */
    private final String passwordField;

    /** Optional pinned KV v2 secret version; {@code null} means latest. */
    private final Integer secretVersion;

    /** TCP/TLS connection timeout in seconds. */
    private final int connectTimeoutSeconds;

    /** Overall HTTP request timeout in seconds. */
    private final int requestTimeoutSeconds;

    /** Whether to revoke the driver-created token when the connection is closed. */
    private final boolean revokeOnClose;

    private VaultConfig(URI base, String authMount, String mount, int kvVersion, String namespace, String secretPath, String passwordField, Integer secretVersion, int connectTimeoutSeconds, int requestTimeoutSeconds, boolean revokeOnClose) {
        this.base = base;
        this.authMount = authMount;
        this.mount = mount;
        this.kvVersion = kvVersion;
        this.namespace = namespace;
        this.secretPath = secretPath;
        this.passwordField = passwordField;
        this.secretVersion = secretVersion;
        this.connectTimeoutSeconds = connectTimeoutSeconds;
        this.requestTimeoutSeconds = requestTimeoutSeconds;
        this.revokeOnClose = revokeOnClose;
    }

    URI base() { return base; }
    String authMount() { return authMount; }
    String mount() { return mount; }
    int kvVersion() { return kvVersion; }
    String namespace() { return namespace; }
    String secretPath() { return secretPath; }
    String passwordField() { return passwordField; }
    Integer secretVersion() { return secretVersion; }
    int connectTimeoutSeconds() { return connectTimeoutSeconds; }
    int requestTimeoutSeconds() { return requestTimeoutSeconds; }
    boolean revokeOnClose() { return revokeOnClose; }

    /**
     * Parses and validates a JDBC URL and connection properties into an immutable
     * {@code VaultConfig}.
     *
     * <p>The URL must be of the form {@code jdbc:vault:https://host:port} with no
     * path, credentials, query string, or fragment. HTTP is only permitted for
     * loopback addresses when {@code allowHttp=true}.
     *
     * @param url the full JDBC URL
     * @param p   connection properties supplied by the caller
     * @return validated configuration
     * @throws SQLException with state {@code HY024} for any invalid property value
     */
    static VaultConfig parse(String url, Properties p) throws SQLException {
        // Step 1: Strip the "jdbc:vault:" prefix and parse the remainder as a URI.
        URI base;
        try { base = new URI(url.substring(VaultDriver.PREFIX.length())); }
        catch (URISyntaxException | IllegalArgumentException e) { throw invalid("Invalid Vault URL"); }

        // Step 2: Structural validation — reject embedded credentials, query strings,
        // fragments, non-root paths, and out-of-range ports. A missing port (-1) is
        // accepted; port 0 is reserved and rejected.
        if (base.getHost() == null || base.getRawUserInfo() != null || base.getRawQuery() != null
                || base.getRawFragment() != null || (base.getRawPath() != null
                && !base.getRawPath().isEmpty() && !base.getRawPath().equals("/"))
                || base.getPort() == 0 || base.getPort() > 65535) {
            throw invalid("Use jdbc:vault:https://host:port with no path, credentials, query, or fragment");
        }

        // Step 3: Scheme check. HTTPS is always required; plain HTTP is permitted only
        // for loopback addresses when the caller has explicitly set allowHttp=true
        // (intended for local development/testing only).
        boolean allowHttp = bool(p, "allowHttp", false);
        if (!"https".equals(base.getScheme())) {
            if (!"http".equals(base.getScheme()) || !allowHttp
                    || !Arrays.asList("localhost", "127.0.0.1", "[::1]").contains(base.getHost())) {
                throw invalid("HTTPS is required; allowHttp=true permits only loopback development URLs");
            }
        }

        // Step 4: KV engine version — must be 1 or 2.
        int kv = positive(p, "kvVersion", 2);
        if (kv != 1 && kv != 2) {
            throw invalid("kvVersion must be 1 or 2");
        }

        // Step 5: Optional pinned secret version is only meaningful for KV v2.
        Integer version = p.getProperty("secretVersion") == null ? null : positive(p, "secretVersion", 1);
        if (kv == 1 && version != null) {
            throw invalid("secretVersion requires kvVersion=2");
        }

        // Step 6: Validate and URL-encode the auth mount and secrets mount paths.
        String auth = p.getProperty("authMount", "userpass");
        String mount = p.getProperty("mount", "secret");
        path(auth); path(mount);

        // Step 7: Optional default secret path — validated if provided.
        String secretPath = p.getProperty("secretPath");
        if (secretPath != null) {
            path(secretPath);
        }

        // Step 8: Field name must be non-empty (default "password").
        String field = p.getProperty("passwordField", "password");
        if (field.isEmpty()) {
            throw invalid("passwordField cannot be empty");
        }

        // Step 9: Namespace must contain only printable ASCII if provided (it is sent
        // as an HTTP header value).
        String namespace = p.getProperty("namespace", "");
        if (!namespace.isEmpty()) {
            if (namespace.codePoints().anyMatch(c -> c < 32 || c > 126)) {
                throw invalid("Invalid namespace header");
            }
        }

        // Step 10: Connection timeout — capped by the global JDBC login timeout if set.
        int loginTimeout = java.sql.DriverManager.getLoginTimeout();
        int connectTimeout = positive(p, "connectTimeoutSeconds", 10);
        if (loginTimeout > 0) {
            connectTimeout = Math.min(loginTimeout, connectTimeout);
        }

        // Step 11: Assemble and return the validated, immutable configuration.
        VaultConfig cfg = new VaultConfig(base, auth, mount, kv, namespace, secretPath, field, version,
                connectTimeout, positive(p, "requestTimeoutSeconds", 15), bool(p, "revokeOnClose", true));
        if (LOG.isLoggable(Level.FINE)) {
            LOG.log(Level.FINE,
                    "VaultConfig parsed: base={0} authMount={1} mount={2} kvVersion={3}"
                    + " secretPath={4} passwordField={5} secretVersion={6}"
                    + " connectTimeout={7}s requestTimeout={8}s revokeOnClose={9}",
                    new Object[]{cfg.base, cfg.authMount, cfg.mount, cfg.kvVersion,
                            cfg.secretPath, cfg.passwordField, cfg.secretVersion,
                            cfg.connectTimeoutSeconds, cfg.requestTimeoutSeconds, cfg.revokeOnClose});
        }
        return cfg;
    }

    /**
     * Validates and percent-encodes each segment of a Vault relative path.
     *
     * <p>Rejects blank values, empty segments, dot-traversal ({@code .} / {@code ..}),
     * backslashes, and control characters.
     *
     * @param value raw path string (may contain {@code /} separators)
     * @return URL-encoded path safe for use in a Vault API endpoint
     * @throws SQLException with state {@code HY024} if the path is invalid
     */
    static String path(String value) throws SQLException {
        if (value == null || isBlank(value)) {
            throw invalid("A non-empty relative Vault path is required");
        }
        String[] parts = value.split("/", -1);
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")
                    || part.indexOf('\\') >= 0 || part.codePoints().anyMatch(c -> c < 32 || c == 127)) {
                throw invalid("Vault paths must have non-empty segments without dot traversal or control characters");
            }
        }
        return Arrays.stream(parts).map(VaultConfig::segment).collect(Collectors.joining("/"));
    }

    /**
     * Percent-encodes a single path segment using UTF-8, replacing {@code +} with
     * {@code %20} so that spaces are encoded in the standard URL path form.
     *
     * @param value raw segment value
     * @return URL-encoded segment
     */
    static String segment(String value) {
        try { return URLEncoder.encode(value, "UTF-8").replace("+", "%20"); }
        catch (UnsupportedEncodingException e) { throw new AssertionError("UTF-8 must be available", e); }
    }

    /**
     * Constructs a fully-qualified Vault API URI by resolving the given path
     * under {@code /v1/} against the configured base URL.
     *
     * @param path Vault API path, e.g. {@code secret/data/apps/db}
     * @return resolved {@link URI}
     */
    URI endpoint(String path) {
        return base.resolve("/v1/" + path);
    }

    /**
     * Reads an integer property that must be strictly positive.
     *
     * @param p        connection properties
     * @param key      property name
     * @param fallback default value used when the property is absent
     * @return the parsed positive integer
     * @throws SQLException with state {@code HY024} if the value is missing,
     *                      non-numeric, or not positive
     */
    private static int positive(Properties p, String key, int fallback) throws SQLException {
        try {
            int value = Integer.parseInt(p.getProperty(key, Integer.toString(fallback)));
            if (value <= 0) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException e) { throw invalid(key + " must be a positive integer"); }
    }

    /**
     * Reads a boolean property that must be exactly {@code "true"} or {@code "false"}
     * (case-insensitive).
     *
     * @param p        connection properties
     * @param key      property name
     * @param fallback default value used when the property is absent
     * @return the parsed boolean
     * @throws SQLException with state {@code HY024} if the value is not a valid boolean
     */
    private static boolean bool(Properties p, String key, boolean fallback) throws SQLException {
        String value = p.getProperty(key, Boolean.toString(fallback));
        if (!value.equalsIgnoreCase("true") && !value.equalsIgnoreCase("false")) {
            throw invalid(key + " must be true or false");
        }
        return Boolean.parseBoolean(value);
    }

    /**
     * Returns {@code true} if the string consists entirely of whitespace code points.
     *
     * @param value string to test; must not be {@code null}
     * @return {@code true} if blank
     */
    static boolean isBlank(String value) {
        return value.codePoints().allMatch(Character::isWhitespace);
    }

    /**
     * Creates a {@link SQLException} with SQL state {@code HY024} (invalid attribute value)
     * for reporting configuration errors.
     *
     * @param message human-readable error description
     * @return the exception (not thrown — callers use {@code throw invalid(...)})
     */
    static SQLException invalid(String message) {
        return new SQLException(message, "HY024");
    }
}
