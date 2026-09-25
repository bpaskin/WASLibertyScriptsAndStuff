package com.ibm.example.vault;

import java.sql.SQLException;
import java.sql.SQLSyntaxErrorException;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Parsed representation of the single SQL statement this driver accepts.
 *
 * <p>The only supported form (case-insensitive, optional trailing semicolon) is:
 * <pre>
 *   SELECT password FROM vault_secret
 *   [WHERE path = ? [AND field = ?]]
 * </pre>
 *
 * <p>Parameters are positional placeholders; their values are supplied separately
 * via {@link java.sql.PreparedStatement#setString} and are <em>never</em>
 * interpolated into the SQL string, eliminating any injection risk.
 */
final class SecretQuery {
    private static final Logger LOG = Logger.getLogger(SecretQuery.class.getName());

    /** Number of {@code ?} placeholders in the parsed query: 0, 1, or 2. */
    private final int parameterCount;

    private SecretQuery(int parameterCount) { this.parameterCount = parameterCount; }

    /** Returns the number of bound parameters this query expects (0, 1, or 2). */
    int parameterCount() { return parameterCount; }

    /**
     * Compiled pattern for the accepted query grammar.
     * Group 1 = path placeholder, group 2 = field placeholder (optional).
     */
    private static final Pattern QUERY = Pattern.compile(
            "\\s*SELECT\\s+password\\s+FROM\\s+vault_secret(?:\\s+WHERE\\s+path\\s*=\\s*(\\?)(?:\\s+AND\\s+field\\s*=\\s*(\\?))?)?\\s*;?\\s*",
            Pattern.CASE_INSENSITIVE);

    /**
     * Parses a SQL string and returns a {@code SecretQuery} describing its structure.
     *
     * @param sql the SQL string to parse; may be {@code null}
     * @return parsed query with its parameter count
     * @throws java.sql.SQLSyntaxErrorException if the SQL does not match the
     *         accepted grammar
     */
    static SecretQuery parse(String sql) throws SQLException {
        if (sql != null) {
            java.util.regex.Matcher match = QUERY.matcher(sql);
            if (match.matches()) {
                int params = match.group(2) != null ? 2 : match.group(1) != null ? 1 : 0;
                LOG.log(Level.FINE, "parse: accepted query with {0} parameter(s)", params);
                return new SecretQuery(params);
            }
        }
        LOG.log(Level.FINE, "parse: rejected unsupported SQL: {0}", sql);
        throw new SQLSyntaxErrorException("Supported query: SELECT password FROM vault_secret [WHERE path = ? [AND field = ?]]", "42000");
    }
}
