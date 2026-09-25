package com.ibm.example.vault.web;

import javax.annotation.Resource;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.sql.DataSource;
import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Example servlet that retrieves a database password from HashiCorp Vault via
 * the vault-jdbc driver and uses it to connect to a downstream data source.
 *
 * <p>The servlet never writes the secret to the HTTP response or to any log.
 * The only externally observable evidence of a successful retrieval is the
 * {@code 200 OK} status and the confirmation message.
 *
 * <h2>How it works</h2>
 * <ol>
 *   <li>Liberty injects the {@code jdbc/vaultSecrets} data source configured in
 *       {@code server.xml}. Each {@link DataSource#getConnection()} call
 *       authenticates to Vault with the container-managed credentials
 *       ({@code VAULT_USER} / {@code VAULT_PASSWORD}) and obtains a scoped
 *       client token.</li>
 *   <li>The servlet executes a parameterised
 *       {@code SELECT password FROM vault_secret WHERE path = ? AND field = ?}
 *       query. The driver translates this into a Vault KV GET and returns the
 *       field value as the single result column.</li>
 *   <li>The retrieved secret is passed directly to the downstream connection
 *       factory and immediately discarded — it is never stored in an instance
 *       variable, logged, or included in any response body.</li>
 *   <li>Closing the vault {@link Connection} revokes the Vault token
 *       (controlled by the {@code revokeOnClose} driver property).</li>
 * </ol>
 *
 * <h2>Deployment</h2>
 * <ul>
 *   <li>Deploy this WAR alongside the vault-jdbc JAR on a Liberty server
 *       whose {@code server.xml} includes the snippets from
 *       {@code examples/server.xml}.</li>
 *   <li>Set environment variables {@code VAULT_USER}, {@code VAULT_PASSWORD},
 *       and {@code VAULT_JDBC_URL} before starting Liberty.</li>
 *   <li>The servlet is mapped to {@code /secret} by the {@code @WebServlet}
 *       annotation; override in {@code web.xml} if needed.</li>
 * </ul>
 */
@WebServlet("/secret")
public class VaultSecretServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    /**
     * The {@code jdbc/vaultSecrets} data source declared in {@code server.xml}.
     * Liberty injects this via the {@code @Resource} annotation; the JNDI name
     * must match the {@code jndiName} attribute on the {@code <dataSource>} element.
     *
     * <p>This data source is backed by the vault-jdbc driver and performs a live
     * Vault API call on every {@link DataSource#getConnection()}.
     */
    @Resource(lookup = "jdbc/vaultSecrets")
    private DataSource vaultDataSource;

    /**
     * Handles GET requests. Retrieves a secret from Vault and demonstrates that
     * it can be used without ever surfacing it in the HTTP response.
     *
     * <p>Query parameters:
     * <ul>
     *   <li>{@code path} — Vault secret path relative to the configured mount
     *       (e.g. {@code apps/database}). Falls back to the driver's configured
     *       default ({@code secretPath} property) when omitted.</li>
     *   <li>{@code field} — Field name within the secret
     *       (e.g. {@code db_password}). Falls back to the driver's configured
     *       default ({@code passwordField} property) when omitted.</li>
     * </ul>
     *
     * <p>On success the response body contains a plain-text confirmation. On any
     * error an appropriate HTTP status and a sanitised message are returned;
     * internal details (including any partial secret value) are written only to
     * the server log.
     */
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        // Step 1: Read optional query parameters. When absent the driver falls
        //         back to the secretPath / passwordField configured in server.xml.
        String path  = request.getParameter("path");
        String field = request.getParameter("field");

        String secret;

        try {
            // Step 2: Obtain a connection. Each getConnection() call authenticates to
            //         Vault using the credentials Liberty supplies via containerAuthData.
            //         The try-with-resources block guarantees the connection is closed
            //         (and the Vault token is revoked) when the block exits.
            try (Connection vaultConn = vaultDataSource.getConnection()) {

                // Step 3: Build the query. Both parameters are optional; when a
                //         parameter is null the driver uses its configured default.
                //         Using PreparedStatement ensures the path and field are
                //         never interpolated into a SQL string.
                String sql = buildQuery(path, field);

                try (PreparedStatement stmt = vaultConn.prepareStatement(sql)) {

                    // Step 4: Bind parameters only for the placeholders we included.
                    int paramIndex = 1;
                    if (path != null && !path.isEmpty()) {
                        stmt.setString(paramIndex++, path);
                    }
                    if (field != null && !field.isEmpty()) {
                        stmt.setString(paramIndex, field);
                    }

                    // Step 5: Execute the query. The driver performs a single Vault KV
                    //         GET request and returns the field value as one row.
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (!rs.next()) {
                            // The driver always returns exactly one row on success, so
                            // no rows means the field was not found.
                            response.sendError(HttpServletResponse.SC_NOT_FOUND,
                                    "Secret not found at the requested path and field.");
                            return;
                        }

                        // Step 6: Retrieve the secret value. It is intentionally stored
                        //         in a local variable with the shortest possible scope and
                        //         never assigned to any field, logged, or echoed back.
                        secret = rs.getString(1);
                    }
                }
            }
        } catch (SQLException e) {
            // Step 7: Map JDBC exceptions to appropriate HTTP status codes.
            //         The SQL state and message are logged server-side; only a
            //         generic description is sent to the client to prevent
            //         information leakage.
            log("Vault secret retrieval failed: state=" + e.getSQLState()
                    + " message=" + e.getMessage());

            String sqlState = e.getSQLState() != null ? e.getSQLState() : "";
            if (sqlState.startsWith("28")) {
                // 28xxx = invalid authorisation — bad credentials or token expired.
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED,
                        "Vault authentication failed. Check VAULT_USER and VAULT_PASSWORD.");
            } else if (sqlState.equals("02000")) {
                // 02000 = no data — secret path or field does not exist.
                response.sendError(HttpServletResponse.SC_NOT_FOUND,
                        "Secret path or field not found in Vault.");
            } else if (sqlState.startsWith("08")) {
                // 08xxx = connection error — network or TLS problem.
                response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                        "Cannot reach Vault. Check VAULT_JDBC_URL and network connectivity.");
            } else {
                response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                        "Secret retrieval failed.");
            }
            return;
        }

        // Step 8: Use the secret. In a real application the retrieved password
        //         would be passed to a DataSource, a REST client, or an encryption
        //         key store. Here we demonstrate the hand-off point without logging
        //         or exposing the value — only its length is mentioned so the caller
        //         can confirm a non-empty value was received.
        useSecret(secret);

        // Step 9: Respond with a confirmation. The secret value itself is never
        //         included in the response body.
        response.setContentType("text/plain;charset=UTF-8");
        PrintWriter out = response.getWriter();
        out.println("Secret retrieved successfully.");
        out.println("Length: " + secret.length() + " characters.");
    }

    /**
     * Constructs the minimal {@code SELECT} query supported by the vault-jdbc
     * driver, including only the {@code WHERE} clauses needed for the supplied
     * parameters.
     *
     * <p>The driver accepts three forms:
     * <ul>
     *   <li>{@code SELECT password FROM vault_secret} — uses the default path
     *       and field from {@code server.xml}.</li>
     *   <li>{@code SELECT password FROM vault_secret WHERE path = ?} — uses a
     *       caller-supplied path with the default field.</li>
     *   <li>{@code SELECT password FROM vault_secret WHERE path = ? AND field = ?}
     *       — fully parameterised.</li>
     * </ul>
     *
     * @param path  caller-supplied path parameter, or {@code null}/empty for default
     * @param field caller-supplied field parameter, or {@code null}/empty for default
     * @return the SQL string to prepare
     */
    private static String buildQuery(String path, String field) {
        boolean hasPath  = path  != null && !path.isEmpty();
        boolean hasField = field != null && !field.isEmpty();

        if (hasPath && hasField) {
            // Both parameters supplied — fully qualified lookup.
            return "SELECT password FROM vault_secret WHERE path = ? AND field = ?";
        } else if (hasPath) {
            // Path only — use the driver's configured default field.
            return "SELECT password FROM vault_secret WHERE path = ?";
        } else {
            // Neither supplied — use both driver defaults.
            return "SELECT password FROM vault_secret";
        }
    }

    /**
     * Hand-off point for the retrieved secret.
     *
     * <p>Replace this method with real logic such as:
     * <ul>
     *   <li>Creating a {@link javax.sql.DataSource} connection with the password.</li>
     *   <li>Initialising an API client with a bearer token.</li>
     *   <li>Decrypting data using a retrieved encryption key.</li>
     * </ul>
     *
     * <p>The secret must <strong>not</strong> be logged, written to a response
     * body, stored in a static field, or passed to any method that may cache it.
     *
     * @param secret the plaintext secret value retrieved from Vault
     */
    private void useSecret(String secret) {
        // TODO: replace with real secret consumption logic.
        // Example: DriverManager.getConnection(dbUrl, dbUser, secret);
    }
}
