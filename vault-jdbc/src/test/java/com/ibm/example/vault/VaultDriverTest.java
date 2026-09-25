package com.ibm.example.vault;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLDataException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.sql.SQLSyntaxErrorException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VaultDriverTest {
    HttpServer server;
    ExecutorService executor;
    String url;
    final CopyOnWriteArrayList<Request> requests = new CopyOnWriteArrayList<>();
    volatile int loginStatus = 200;
    volatile int readStatus = 200;
    volatile int revokeStatus = 204;
    volatile String loginBody = "{\"auth\":{\"client_token\":\"test-token\"}}";
    volatile String readBody = "{\"data\":{\"data\":{\"password\":\"p@ss\\\"word\\n雪\",\"custom\":\"second\"}}}";
    volatile long readDelay;
    volatile byte[] rawReadBody;
    volatile boolean dripBody;
    static final class Request {
        private final String method, uri, token, namespace, body;
        Request(String method, String uri, String token, String namespace, String body) {
            this.method = method; this.uri = uri; this.token = token; this.namespace = namespace; this.body = body;
        }
        String method() { return method; }
        String uri() { return uri; }
        String token() { return token; }
        String namespace() { return namespace; }
        String body() { return body; }
    }

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
        url = "jdbc:vault:http://127.0.0.1:" + server.getAddress().getPort();
    }
    @AfterEach void stop() {
        if (server != null) {
            server.stop(0);
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    void handle(HttpExchange exchange) {
        try {
            Request request = new Request(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                    exchange.getRequestHeaders().getFirst("X-Vault-Token"),
                    exchange.getRequestHeaders().getFirst("X-Vault-Namespace"),
                    new String(TestSupport.readAll(exchange.getRequestBody()), StandardCharsets.UTF_8));
            requests.add(request);
            int status;
            String body;
            if (request.uri().contains("/login/")) {
                status = loginStatus;
                body = loginBody;
            } else if (request.uri().endsWith("/revoke-self")) {
                status = revokeStatus;
                body = "{}";
            } else if (request.uri().endsWith("/lookup-self")) {
                status = 200;
                body = "{}";
            } else {
                if (readDelay > 0) {
                    Thread.sleep(readDelay);
                }
                status = readStatus;
                body = readBody;
            }
            if (status == 302) {
                exchange.getResponseHeaders().add("Location", "/redirect-target");
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            if (rawReadBody != null && request.uri().contains("/data/")) {
                bytes = rawReadBody;
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
            if (status != 204) {
                if (dripBody && request.method().equals("GET")) {
                    for (byte b : bytes) {
                        exchange.getResponseBody().write(b);
                        exchange.getResponseBody().flush();
                        Thread.sleep(100);
                    }
                } else exchange.getResponseBody().write(bytes);
            }
        } catch (Exception ignored) { /* Client may cancel deliberately in timeout tests. */ }
        finally { exchange.close(); }
    }

    Properties props() {
        Properties p = new Properties();
        p.setProperty("user", "alice"); p.setProperty("password", "login\"secret\n");
        p.setProperty("allowHttp", "true");
        return p;
    }

    @Test void serviceDiscoveryLoginKv2AndLifecycle() throws Exception {
        Properties p = props(); p.setProperty("namespace", "team/");
        p.setProperty("secretVersion", "3");
        Connection c = DriverManager.getConnection(url, p);
        assertInstanceOf(VaultDriver.class, DriverManager.getDriver(url));
        assertTrue(c.isReadOnly()); assertTrue(c.getAutoCommit());
        PreparedStatement s = c.prepareStatement("SELECT password FROM vault_secret WHERE path = ?");
        s.setString(1, "apps/my service");
        ResultSet r = s.executeQuery();
        assertThrows(SQLException.class, () -> r.getString(1));
        assertTrue(r.next()); assertEquals("p@ss\"word\n雪", r.getString("PASSWORD"));
        assertEquals("p@ss\"word\n雪", r.getObject(1, String.class));
        assertFalse(r.wasNull());
        assertEquals(Types.VARCHAR, r.getMetaData().getColumnType(1));
        assertTrue(r.getMetaData().isReadOnly(1)); assertFalse(r.getMetaData().isDefinitelyWritable(1));
        assertEquals("password", r.getMetaData().getColumnLabel(1));
        assertThrows(SQLException.class, () -> r.getString(2));
        assertFalse(r.next()); assertFalse(r.next()); assertTrue(r.isAfterLast());
        assertThrows(SQLException.class, () -> r.getString(1));
        c.close(); c.close();
        assertTrue(c.isClosed()); assertTrue(s.isClosed()); assertTrue(r.isClosed());
        assertThrows(SQLException.class, c::createStatement);
        assertEquals(3, requests.size());
        assertEquals("POST", requests.get(0).method());
        assertEquals("/v1/auth/userpass/login/alice", requests.get(0).uri());
        assertEquals("{\"password\":\"login\\\"secret\\n\"}", requests.get(0).body());
        assertNull(requests.get(0).token());
        assertEquals("/v1/secret/data/apps/my%20service?version=3", requests.get(1).uri());
        assertEquals("test-token", requests.get(1).token());
        assertEquals("team/", requests.get(1).namespace());
        assertEquals("/v1/auth/token/revoke-self", requests.get(2).uri());
    }

    @Test void customFieldFreshReadsAndOldResultClosure() throws Exception {
        try (Connection c = DriverManager.getConnection(url, props());
             PreparedStatement s = c.prepareStatement(" select password from vault_secret where path=? and field=?; ")) {
            s.setString(1, "app/db"); s.setObject(2, "custom");
            assertTrue(s.execute());
            ResultSet old = s.getResultSet(); assertTrue(old.next()); assertEquals("second", old.getString(1));
            readBody = "{\"data\":{\"data\":{\"custom\":\"rotated\"}}}";
            try (ResultSet next = s.executeQuery()) {
                assertTrue(old.isClosed()); assertTrue(next.next()); assertEquals("rotated", next.getString(1));
            }
            s.clearParameters();
            assertThrows(SQLException.class, s::executeQuery);
            assertEquals(-1, s.getUpdateCount());
        }
    }

    @Test void kv1CustomMountAndDefaultStatement() throws Exception {
        Properties p = props(); p.setProperty("kvVersion", "1"); p.setProperty("mount", "kv/legacy");
        p.setProperty("authMount", "corp-users"); p.setProperty("secretPath", "apps/db");
        p.setProperty("passwordField", "db.pass");
        readBody = "{\"data\":{\"db.pass\":\"v1-value\"}}";
        try (Connection c = DriverManager.getConnection(url, p); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT password FROM vault_secret")) {
            assertTrue(r.next()); assertEquals("v1-value", r.getString(1));
        }
        assertEquals("/v1/auth/corp-users/login/alice", requests.get(0).uri());
        assertEquals("/v1/kv/legacy/apps/db", requests.get(1).uri());
    }

    @Test void invalidConfigurationSendsNoRequests() throws Exception {
        Properties p = props(); p.remove("allowHttp");
        assertThrows(SQLException.class, () -> DriverManager.getConnection(url, p));
        String[] badUrls = {"jdbc:vault:https://alice:password@vault.example", "jdbc:vault:https://vault.example?password=x",
                "jdbc:vault:https://vault.example/v1", "jdbc:vault:ftp://vault.example", "jdbc:vault:http://vault.example",
                "jdbc:vault:https://vault.example#fragment", "jdbc:vault:https://vault.example:99999"};
        for (String bad : badUrls) assertThrows(SQLException.class, () -> DriverManager.getConnection(bad, props()));
        for (String[] pair : new String[][]{{"kvVersion", "3"}, {"requestTimeoutSeconds", "0"}, {"mount", "../secret"},
                {"authMount", "/userpass"}, {"namespace", "bad\nheader"}, {"revokeOnClose", "yes"}, {"secretVersion", "0"}}) {
            Properties invalid = props(); invalid.setProperty(pair[0], pair[1]);
            assertThrows(SQLException.class, () -> DriverManager.getConnection(url, invalid));
        }
        assertTrue(requests.isEmpty());
    }

    @Test void authenticationFailureAndIntrospectionDoNotExposeCredentials() throws Exception {
        loginBody = "{\"errors\":[\"login-secret and test-token\"]}";
        for (int status : new int[]{400, 401, 403}) {
            loginStatus = status;
            SQLException e = assertThrows(SQLInvalidAuthorizationSpecException.class, () -> DriverManager.getConnection(url, props()));
            assertEquals("28000", e.getSQLState()); assertEquals(status, e.getErrorCode());
            assertNull(e.getCause()); assertFalse(e.toString().contains("login-secret"));
        }
        for (DriverPropertyInfo property : new VaultDriver().getPropertyInfo(url, props())) {
            if (property.name.equals("password")) {
                assertNull(property.value);
            }
        }
    }

    @Test void unsupportedAndUnboundQueriesFailBeforeRead() throws Exception {
        try (Connection c = DriverManager.getConnection(url, props())) {
            assertThrows(SQLSyntaxErrorException.class, () -> c.prepareStatement("DELETE FROM vault_secret"));
            assertThrows(SQLFeatureNotSupportedException.class, () -> c.setAutoCommit(false));
            assertThrows(SQLFeatureNotSupportedException.class, c::commit);
            assertThrows(SQLFeatureNotSupportedException.class, () -> c.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_READ_ONLY));
            try (PreparedStatement s = c.prepareStatement("SELECT password FROM vault_secret WHERE path = ?")) {
                assertThrows(SQLException.class, s::executeQuery);
                assertThrows(SQLException.class, () -> s.setString(2, "bad"));
                assertThrows(SQLException.class, () -> s.setString(1, null));
                s.setString(1, "../admin"); assertThrows(SQLException.class, s::executeQuery);
                assertThrows(SQLException.class, () -> s.executeQuery("SELECT password FROM vault_secret"));
            }
            assertEquals(1, requests.size());
        }
    }

    @Test void missingNullAndNonStringFields() throws Exception {
        try (Connection c = DriverManager.getConnection(url, props());
             PreparedStatement s = c.prepareStatement("SELECT password FROM vault_secret WHERE path = ?")) {
            s.setString(1, "app/db");
            readBody = "{\"data\":{\"data\":{}}}";
            assertEquals("02000", assertThrows(SQLException.class, s::executeQuery).getSQLState());
            readBody = "{\"data\":{\"data\":{\"password\":null}}}";
            assertEquals("02000", assertThrows(SQLException.class, s::executeQuery).getSQLState());
            readBody = "{\"data\":{\"data\":{\"password\":123}}}";
            assertThrows(SQLDataException.class, s::executeQuery);
            readBody = "{\"data\":{\"data\":{\"password\":\"\"}}}";
            try (ResultSet r = s.executeQuery()) { assertTrue(r.next()); assertEquals("", r.getString(1)); }
        }
    }

    @Test void httpFailuresAreSanitizedAndRedirectsNeverFollowed() throws Exception {
        try (Connection c = DriverManager.getConnection(url, props());
             PreparedStatement s = c.prepareStatement("SELECT password FROM vault_secret WHERE path = ?")) {
            s.setString(1, "app/db"); readBody = "secret-must-not-leak";
            for (int status : new int[]{403, 404, 429, 500, 302}) {
                readStatus = status;
                SQLException e = assertThrows(SQLException.class, s::executeQuery);
                assertEquals(status, e.getErrorCode());
                assertFalse(e.toString().contains(readBody)); assertNull(e.getCause());
            }
            assertFalse(requests.stream().anyMatch(r -> r.uri().contains("redirect-target")));
            readStatus = 200;
            SQLException e = assertThrows(SQLException.class, s::executeQuery);
            assertEquals("Vault returned malformed JSON", e.getMessage()); assertNull(e.getCause());
        }
    }

    @Test void json4jDecodesEscapedUnicodeAndNestedData() throws Exception {
        readBody = "{\"data\":{\"data\":{\"password\":\"\\u96ea\\uD83D\\uDE00\\t\\\\\\\"\","
                + "\"unused\":[true,null,12,{\"nested\":\"value\"}]},\"metadata\":{\"version\":2}}}";
        try (Connection c = DriverManager.getConnection(url, props());
             PreparedStatement s = c.prepareStatement("SELECT password FROM vault_secret WHERE path = ?")) {
            s.setString(1, "app/db");
            try (ResultSet r = s.executeQuery()) {
                assertTrue(r.next()); assertEquals("雪😀\t\\\"", r.getString(1));
            }
        }
    }

    @Test void oversizedResponseRetainsSizeError() throws Exception {
        rawReadBody = new byte[1024 * 1024 + 1];
        java.util.Arrays.fill(rawReadBody, (byte) 'x');
        try (Connection c = DriverManager.getConnection(url, props());
             PreparedStatement s = c.prepareStatement("SELECT password FROM vault_secret WHERE path = ?")) {
            s.setString(1, "app/db");
            SQLException e = assertThrows(SQLException.class, s::executeQuery);
            assertEquals("HY000", e.getSQLState());
            assertEquals("Vault response exceeds the 1 MiB limit", e.getMessage());
            assertNull(e.getCause());
        }
    }

    @Test void malformedUtf8IsSanitized() throws Exception {
        rawReadBody = new byte[]{(byte) 0xc3, 0x28};
        try (Connection c = DriverManager.getConnection(url, props());
             PreparedStatement s = c.prepareStatement("SELECT password FROM vault_secret WHERE path = ?")) {
            s.setString(1, "app/db");
            SQLException e = assertThrows(SQLException.class, s::executeQuery);
            assertEquals("Vault returned malformed JSON", e.getMessage());
            assertNull(e.getCause());
        }
    }

    @Test void queryTimeoutIsEnforced() throws Exception {
        try (Connection c = DriverManager.getConnection(url, props());
             PreparedStatement s = c.prepareStatement("SELECT password FROM vault_secret WHERE path = ?")) {
            s.setString(1, "app/db"); s.setQueryTimeout(1); readDelay = 2000;
            assertThrows(SQLTimeoutException.class, s::executeQuery);
        }
    }

    @Test void closeOnCompletionAndValidation() throws Exception {
        try (Connection c = DriverManager.getConnection(url, props())) {
            assertTrue(c.isValid(1)); assertThrows(SQLException.class, () -> c.isValid(-1));
            PreparedStatement s = c.prepareStatement("SELECT password FROM vault_secret WHERE path = ?");
            s.setString(1, "app/db"); s.closeOnCompletion();
            ResultSet r = s.executeQuery(); r.close();
            assertTrue(s.isClosed()); assertTrue(r.isClosed());
        }
    }

    @Test void overallDeadlineStopsSlowBodyAndConnectionCanBeReused() throws Exception {
        try (Connection c = DriverManager.getConnection(url, props());
             PreparedStatement s = c.prepareStatement("SELECT password FROM vault_secret WHERE path = ?")) {
            s.setString(1, "app/db"); s.setQueryTimeout(1); dripBody = true;
            long start = System.nanoTime();
            assertThrows(SQLTimeoutException.class, s::executeQuery);
            assertTrue(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 3000,
                    "The overall deadline must stop a body that keeps sending individual bytes");
            dripBody = false;
            try (ResultSet r = s.executeQuery()) { assertTrue(r.next()); assertEquals("p@ss\"word\n雪", r.getString(1)); }
        }
    }

    @Test void closeFailureStillClosesResourcesAndDoesNotLeakToken() throws Exception {
        Connection c = DriverManager.getConnection(url, props());
        Statement s = c.createStatement(); revokeStatus = 403;
        assertThrows(SQLException.class, c::close);
        assertTrue(c.isClosed()); assertTrue(s.isClosed()); assertFalse(c.isValid(1));
        c.close();
    }

    @Test void revocationCanBeDisabledForBatchTokens() throws Exception {
        Properties p = props(); p.setProperty("revokeOnClose", "false");
        try (Connection ignored = DriverManager.getConnection(url, p)) { }
        assertEquals(1, requests.size());
    }

    @Test void missingTokenAndMfaAreReported() throws Exception {
        loginBody = "{\"auth\":{}}";
        assertThrows(SQLInvalidAuthorizationSpecException.class, () -> DriverManager.getConnection(url, props()));
        loginBody = "{\"auth\":{\"mfa_requirement\":{\"mfa_request_id\":\"id\"}}}";
        assertThrows(SQLInvalidAuthorizationSpecException.class, () -> DriverManager.getConnection(url, props()));
    }

    @Test void unrelatedUrlsAreDeclined() throws Exception {
        VaultDriver driver = new VaultDriver();
        assertFalse(driver.acceptsURL(null)); assertFalse(driver.acceptsURL("jdbc:postgresql://localhost/db"));
        assertNull(driver.connect("jdbc:postgresql://localhost/db", props())); assertFalse(driver.jdbcCompliant());
    }
}
