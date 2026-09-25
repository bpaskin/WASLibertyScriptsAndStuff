package com.ibm.example.vault;

import com.ibm.json.java.JSON;
import com.ibm.json.java.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLDataException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTimeoutException;
import java.sql.SQLTransientConnectionException;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * HTTP(S) transport layer for Vault API calls.
 *
 * <p>Uses {@link java.net.HttpURLConnection} so that HTTPS connections use the
 * JVM's default TLS context without any {@code sun.*} implementation details.
 * All network I/O is guarded by a per-request deadline enforced by a single
 * daemon-thread scheduler ({@code vault-jdbc-timeout}).
 *
 * <p>Instances are created via {@link #login} and discarded when the JDBC
 * {@link java.sql.Connection} is closed.
 */
final class VaultClient {
    private static final Logger LOG = Logger.getLogger(VaultClient.class.getName());

    /** Maximum accepted response body size (1 MiB) to prevent unbounded memory use. */
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;

    /** Resolved configuration for this client; never {@code null}. */
    final VaultConfig config;

    /** Single-thread pool used to disconnect timed-out connections. */
    private final ScheduledThreadPoolExecutor deadlines;

    /** Vault client token obtained at login; {@code null} after {@link #close()}. */
    private String token;

    private VaultClient(VaultConfig config) {
        this.config = config;

        deadlines = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "vault-jdbc-timeout");
            thread.setDaemon(true);
            return thread;
        });

        deadlines.setRemoveOnCancelPolicy(true);
    }

    /**
     * Authenticates against Vault using the userpass (or compatible) auth method and
     * returns a ready-to-use client holding a scoped client token.
     *
     * @param config   resolved driver configuration
     * @param user     Vault username; must be a safe path segment
     * @param password login credential; serialised as JSON and sent over TLS
     * @return authenticated client
     * @throws SQLException if the credentials are rejected, MFA is required, or the
     *                      network call fails
     */
    static VaultClient login(VaultConfig config, String user, String password) throws SQLException {
        return login(config, user, password, 0);
    }

    /** A factory-local login timeout must not change DriverManager's global setting. */
    static VaultClient login(VaultConfig config, String user, String password, int loginTimeout) throws SQLException {
        // Step 1: Guard the username against path-traversal characters and control
        // codes before it is embedded in the Vault API URL.
        if (user.equals(".") || user.equals("..") || user.contains("/") || user.contains("\\")
                || user.codePoints().anyMatch(c -> c < 32 || c == 127)) {
            throw VaultConfig.invalid("Invalid user name");
        }

        LOG.log(Level.FINE, "login: authenticating user={0} authMount={1}", new Object[]{user, config.authMount()});

        // Step 2: Construct a client with its deadline scheduler. If login fails the
        // scheduler is shut down in the catch block so no daemon thread is leaked.
        VaultClient client = new VaultClient(config);

        try {
            // Step 3: Serialise the login payload as JSON. The password is only ever
            // transmitted over TLS and never logged or stored beyond this scope.
            String payload;
            try {
                JSONObject credentials = new JSONObject();
                credentials.put("password", password);
                payload = credentials.serialize();
            } catch (IOException e) {
                throw new SQLException("Cannot encode login request", "HY000");
            }

            // Step 4: Determine the effective timeout — the smaller of the configured
            // request timeout and the global JDBC login timeout (if set).
            int timeout = config.requestTimeoutSeconds();
            if (loginTimeout > 0) timeout = Math.min(timeout, loginTimeout);
            if (DriverManager.getLoginTimeout() > 0) {
                timeout = Math.min(timeout, DriverManager.getLoginTimeout());
            }

            // Step 5: POST credentials to the userpass (or custom) auth endpoint.
            Map<?, ?> response = client.request("POST", config.endpoint("auth/" + VaultConfig.path(config.authMount())
                    + "/login/" + VaultConfig.segment(user)), payload, timeout, "login");
            Object auth = response.get("auth");

            // Step 6: Reject interactive MFA flows — the driver has no way to complete
            // a challenge/response exchange.
            if (get(auth, "mfa_requirement") != null) {
                throw new SQLInvalidAuthorizationSpecException("Interactive MFA is not supported by this driver",
                        "28000");
            }

            // Step 7: Extract and validate the client token from the auth block.
            // Vault tokens are printable ASCII; control characters in a token would
            // corrupt the X-Vault-Token header.
            Object token = get(auth, "client_token");
            if (!(token instanceof String) || VaultConfig.isBlank((String) token)) {
                throw new SQLInvalidAuthorizationSpecException("Vault login did not return a client token", "28000");
            }
            if (((String) token).codePoints().anyMatch(c -> c < 33 || c > 126)) {
                throw new SQLException("Vault returned an invalid token header", "HY000");
            }

            // Step 8: Store the token and return the authenticated client.
            client.token = (String) token;
            LOG.log(Level.FINE, "login: authenticated successfully user={0}", user);
            return client;
        } catch (SQLException | RuntimeException e) {
            // Clean up the deadline scheduler if login did not succeed.
            client.deadlines.shutdownNow();
            throw e;
        }
    }

    /**
     * Reads a single string field from a Vault KV secret.
     *
     * <p>For KV v2 the response envelope is unwrapped automatically ({@code data.data}).
     * For KV v1 the {@code data} object is used directly.
     *
     * @param path         secret path relative to the configured mount
     * @param field        field name inside the secret; must be non-empty
     * @param queryTimeout per-query timeout in seconds; {@code 0} falls back to the
     *                     configured {@code requestTimeoutSeconds}
     * @return the string value of the requested field
     * @throws SQLException if the field is absent, null, or not a string, or if the
     *                      network call fails
     */
    String read(String path, String field, int queryTimeout) throws SQLException {
        // Step 1: Validate the field name early to avoid a network round-trip for an
        // obviously bad request.
        if (field == null || field.isEmpty()) {
            throw VaultConfig.invalid("A non-empty field name is required");
        }

        LOG.log(Level.FINE, "read: path={0} field={1} queryTimeout={2}s",
                new Object[]{path, field, queryTimeout});

        // Step 2: Build the KV endpoint path. KV v2 inserts a "/data/" segment between
        // the mount and the secret path; KV v1 uses the path directly.
        String endpoint = VaultConfig.path(config.mount()) + (config.kvVersion() == 2 ? "/data/" : "/")
                + VaultConfig.path(path);

        // Step 3: Append the version query parameter when a pinned version is configured.
        if (config.secretVersion() != null) {
            endpoint += "?version=" + config.secretVersion();
        }

        // Step 4: Perform the GET request. Falls back to the configured request timeout
        // when the caller did not set a query-level timeout.
        Map<?, ?> response = request("GET", config.endpoint(endpoint), null,
                queryTimeout == 0 ? config.requestTimeoutSeconds() : queryTimeout, "secret read");

        // Step 5: Unwrap the KV v2 envelope. The v2 API nests secret fields under
        // data.data; v1 exposes them directly under data.
        Object data = response.get("data");
        if (config.kvVersion() == 2) {
            data = get(data, "data");
        }

        // Step 6: Verify the data object is present and is a map of key-value pairs.
        if (!(data instanceof Map<?, ?>)) {
            throw new SQLException("Vault response does not contain KV secret data", "HY000");
        }

        // Step 7: Locate the requested field within the secret.
        Object value = get(data, field);
        if (value == null) {
            throw new SQLException("Requested secret field is missing or null", "02000");
        }

        // Step 8: Enforce the string type contract — JDBC callers expect getString() to
        // return a String, not a number or boolean.
        if (!(value instanceof String)) {
            throw new SQLDataException("Requested secret field must contain a string", "22005");
        }

        LOG.log(Level.FINE, "read: secret retrieved from path={0} field={1}", new Object[]{path, field});
        return (String) value;
    }

    /**
     * Null-safe map lookup; returns {@code null} if {@code object} is not a {@link Map}.
     */
    private static Object get(Object object, String key) {
        return object instanceof Map<?, ?> ? ((Map<?, ?>) object).get(key) : null;
    }

    /**
     * Probes Vault with a token self-lookup to verify the connection is still live.
     *
     * @param timeout probe timeout in seconds; {@code 0} uses the configured default
     * @return {@code true} if the token is present and the lookup succeeds
     */
    boolean isValid(int timeout) {
        if (token == null) {
            LOG.fine("isValid: token is null, returning false");
            return false;
        }

        LOG.log(Level.FINE, "isValid: probing token with timeout={0}s", timeout);
        try {
            request("GET", config.endpoint("auth/token/lookup-self"), null,
                    timeout == 0 ? config.requestTimeoutSeconds() : timeout, "token validation");
            LOG.fine("isValid: token is valid");
            return true;
        } catch (SQLException e) {
            LOG.log(Level.FINE, "isValid: token validation failed: {0}", e.getMessage());
            return false;
        }
    }

    /**
     * Revokes the client token (if {@link VaultConfig#revokeOnClose()} is set) and
     * shuts down the deadline scheduler.
     *
     * <p>Always nulls the token and stops the scheduler even if revocation fails.
     *
     * @throws SQLException if the revocation request returns an error status
     */
    void close() throws SQLException {
        try {
            if (token != null && config.revokeOnClose()) {
                LOG.fine("close: revoking Vault token");
                request("POST", config.endpoint("auth/token/revoke-self"), "{}", config.requestTimeoutSeconds(),
                        "token revocation");
                LOG.fine("close: token revoked");
            } else {
                LOG.log(Level.FINE, "close: skipping revocation (token={0} revokeOnClose={1})",
                        new Object[]{token != null ? "present" : "null", config.revokeOnClose()});
            }
        } finally {
            token = null;
            deadlines.shutdownNow();
        }
    }

    /**
     * Converts seconds to milliseconds, capping at {@link Integer#MAX_VALUE} to
     * satisfy {@link HttpURLConnection#setConnectTimeout}.
     */
    private static int millis(int seconds) {
        return (int) Math.min(Integer.MAX_VALUE, TimeUnit.SECONDS.toMillis(seconds));
    }

    /**
     * Executes a single Vault HTTP request and returns the parsed JSON response body
     * as a {@link Map}.
     *
     * <p>The method enforces an absolute wall-clock deadline via a scheduled
     * disconnect, buffering of the response body up to {@link #MAX_RESPONSE_BYTES},
     * and strict UTF-8 validation before JSON parsing. Error status codes are mapped
     * to the most-specific JDBC exception subtype available.
     *
     * @param method    HTTP method ({@code GET} or {@code POST})
     * @param uri       fully-resolved Vault API endpoint
     * @param body      JSON request body, or {@code null} for requests without a body
     * @param timeout   total allowed wall-clock seconds for this request
     * @param operation human-readable label used in exception messages
     * @return parsed response as a {@link Map}; empty map for HTTP 204
     * @throws SQLException on any error; subtype reflects the error category
     */
    private Map<?, ?> request(String method, URI uri, String body, int timeout, String operation) throws SQLException {
        // Step 1: Reject immediately if the calling thread has already been interrupted
        // so we do not start a network operation that will be abandoned anyway.
        if (Thread.currentThread().isInterrupted()) {
            throw new SQLException("Vault " + operation + " interrupted", "HY008");
        }

        LOG.log(Level.FINE, "request: {0} {1} timeout={2}s", new Object[]{method, uri, timeout});

        HttpURLConnection connection = null;
        ScheduledFuture<?> deadline = null;
        AtomicBoolean expired = new AtomicBoolean();
        // Record the absolute deadline as a nanosecond timestamp for checkDeadline().
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeout);

        try {
            // Step 2: Open the connection. The JDK automatically returns an
            // HttpsURLConnection for https:// URLs, using the JVM's default TrustManager
            // without requiring any sun.* classes.
            connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setAllowUserInteraction(false);
            // connectTimeout is capped to the per-request timeout to prevent
            // a slow TCP handshake from consuming the whole budget.
            connection.setConnectTimeout(millis(Math.min(timeout, config.connectTimeoutSeconds())));
            connection.setReadTimeout(millis(timeout));
            connection.setRequestMethod(method);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Connection", "close");

            // Step 3: Add optional Vault-specific headers.
            if (!config.namespace().isEmpty()) {
                // Enterprise-only header; omit entirely for non-namespaced installations.
                connection.setRequestProperty("X-Vault-Namespace", config.namespace());
            }
            if (token != null) {
                // Token is absent only on the initial login POST.
                connection.setRequestProperty("X-Vault-Token", token);
            }

            // Step 4: Arm the deadline — a scheduled task disconnects the socket if the
            // wall-clock timeout elapses while waiting for the response body.
            final HttpURLConnection active = connection;
            deadline = deadlines.schedule(() -> {
                expired.set(true);
                active.disconnect();
            }, timeout, TimeUnit.SECONDS);

            // Step 5: Write the request body for POST requests (e.g. login payload).
            if (body != null) {
                byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                // Use fixed-length streaming to avoid buffering the entire body in memory.
                connection.setFixedLengthStreamingMode(payload.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(payload);
                }
            }

            // Step 6: Read the HTTP status and map error codes to typed JDBC exceptions.
            // Check the deadline after getResponseCode() because it can block.
            int status = connection.getResponseCode();
            checkDeadline(expired, end);
            String message = "Vault " + operation + " failed (HTTP " + status + ")";
            if (status == 401 || status == 403 || (status == 400 && operation.equals("login"))) {
                // 401/403 = auth failure; 400 on login = bad credentials.
                throw new SQLInvalidAuthorizationSpecException(
                        message + "; check credentials, token expiry, and policy", "28000", status);
            }
            if (status == 404) {
                // Secret or mount not found — non-transient, won't fix itself.
                throw new SQLException(message + "; check mount, path, and version", "02000", status);
            }
            if (status == 429 || status >= 500) {
                // 429 = rate-limited; 5xx = server error — both may succeed on retry.
                throw new SQLTransientConnectionException(message, "08001", status);
            }
            if (status < 200 || status >= 300) {
                // Catch-all for unexpected 2xx variants and 3xx redirects (redirects are
                // never followed — setInstanceFollowRedirects(false) above).
                throw new SQLException(message, "HY000", status);
            }
            if (status == 204) {
                // No-content response (e.g. token revocation) — nothing to parse.
                LOG.log(Level.FINE, "request: {0} {1} -> HTTP 204 (no content)", new Object[]{method, uri});
                return Collections.emptyMap();
            }

            LOG.log(Level.FINE, "request: {0} {1} -> HTTP {2}", new Object[]{method, uri, status});

            // Step 7: Read the response body into a byte buffer, enforcing the 1 MiB
            // size limit. The deadline is checked on each iteration so a slow drip of
            // bytes does not bypass the timeout.
            byte[] bytes;
            try (InputStream raw = connection.getInputStream()) {
                ByteArrayOutputStream response = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                while (true) {
                    checkDeadline(expired, end);
                    int count = raw.read(buffer);
                    checkDeadline(expired, end);
                    if (count == -1) {
                        break;
                    }
                    if (response.size() > MAX_RESPONSE_BYTES - count) {
                        throw new SQLException("Vault response exceeds the 1 MiB limit", "HY000");
                    }
                    response.write(buffer, 0, count);
                }
                bytes = response.toByteArray();
            }

            // Step 8: Validate UTF-8 encoding strictly before handing bytes to the JSON
            // parser. Rejecting malformed bytes here prevents the parser from receiving
            // replacement characters that could alter JSON structure.
            Object root;
            try {
                String text = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes)).toString();
                // Step 9: Parse the validated text with JSON4J. Any parse error is
                // caught and re-thrown as a generic message — JSON4J exceptions may
                // echo source text that could contain secret values.
                root = JSON.parse(text);
            } catch (IOException parseEx) {
                throw new SQLException("Vault returned malformed JSON", "HY000");
            }

            // Step 10: The Vault API always returns a JSON object at the root; reject
            // arrays or primitives as invalid responses.
            if (!(root instanceof Map<?, ?>)) {
                throw new SQLException("Vault returned an invalid JSON object", "HY000");
            }

            return (Map<?, ?>) root;
        } catch (SocketTimeoutException e) {
            throw new SQLTimeoutException("Vault " + operation + " timed out", "HYT00");
        } catch (IOException e) {
            if (expired.get()) {
                throw new SQLTimeoutException("Vault " + operation + " timed out", "HYT00");
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new SQLException("Vault " + operation + " interrupted", "HY008");
            }
            throw new SQLNonTransientConnectionException("Vault " + operation + " failed; check network and TLS trust",
                    "08001");
        } catch (IllegalArgumentException e) {
            throw new SQLException("Invalid Vault request configuration", "HY024");
        } finally {
            // Always cancel the deadline task and close the connection, regardless of
            // whether the request succeeded or failed.
            if (deadline != null) {
                deadline.cancel(false);
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * Throws {@link SocketTimeoutException} if the deadline has been triggered by the
     * scheduler or the wall-clock end time has been reached.
     */
    private static void checkDeadline(AtomicBoolean expired, long end) throws SocketTimeoutException {
        if (expired.get() || System.nanoTime() - end >= 0) {
            throw new SocketTimeoutException();
        }
    }
}
