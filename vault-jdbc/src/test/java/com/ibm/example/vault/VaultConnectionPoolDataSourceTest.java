package com.ibm.example.vault;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ClientInfoStatus;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLClientInfoException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.sql.ConnectionEvent;
import javax.sql.ConnectionEventListener;
import javax.sql.ConnectionPoolDataSource;
import javax.sql.PooledConnection;
import javax.sql.StatementEvent;
import javax.sql.StatementEventListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VaultConnectionPoolDataSourceTest {
    HttpServer server;
    ExecutorService executor;
    VaultConnectionPoolDataSource factory;
    final CopyOnWriteArrayList<String> paths = new CopyOnWriteArrayList<>();
    volatile String loginBody;
    volatile String namespace;
    volatile int readStatus = 200;
    volatile int revokeStatus = 204;
    volatile int lookupStatus = 200;
    volatile long loginDelay;

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
        factory = new VaultConnectionPoolDataSource();
        factory.setURL("jdbc:vault:http://127.0.0.1:" + server.getAddress().getPort());
        factory.setAllowHttp("true");
        factory.setUser("configured-user");
        factory.setPassword("configured-password");
        factory.setSecretPath("apps/database");
        factory.setPasswordField("db_password");
    }

    @AfterEach void stop() {
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    void handle(HttpExchange exchange) {
        try {
            String path = exchange.getRequestURI().toString();
            paths.add(path);
            String request = new String(TestSupport.readAll(exchange.getRequestBody()), StandardCharsets.UTF_8);
            namespace = exchange.getRequestHeaders().getFirst("X-Vault-Namespace");
            int status = 200;
            String body = "{}";
            if (path.contains("/login/")) {
                loginBody = request;
                if (loginDelay > 0) Thread.sleep(loginDelay);
                body = "{\"auth\":{\"client_token\":\"test-token\"}}";
            } else if (path.endsWith("/revoke-self")) {
                status = revokeStatus;
            } else if (path.endsWith("/lookup-self")) {
                status = lookupStatus;
            } else {
                status = readStatus;
                body = "{\"data\":{\"data\":{\"db_password\":\"test-secret\"}}}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
            if (status != 204) exchange.getResponseBody().write(bytes);
        } catch (Exception ignored) { /* Tests deliberately time out requests. */ }
        finally { exchange.close(); }
    }

    long requests(String suffix) { return paths.stream().filter(p -> p.endsWith(suffix)).count(); }

    static final class Events implements ConnectionEventListener, StatementEventListener {
        int closed, errors, statementsClosed, statementsErrored;
        SQLException error;
        PooledConnection source;
        @Override public void connectionClosed(ConnectionEvent event) {
            closed++; source = (PooledConnection) event.getSource();
        }
        @Override public void connectionErrorOccurred(ConnectionEvent event) {
            errors++; error = event.getSQLException(); source = (PooledConnection) event.getSource();
        }
        @Override public void statementClosed(StatementEvent event) { statementsClosed++; }
        @Override public void statementErrorOccurred(StatementEvent event) { statementsErrored++; }
    }

    @Test void factoryHasTheInterfaceAndStringBeanPropertiesExpectedByWebSphere() throws Exception {
        assertInstanceOf(ConnectionPoolDataSource.class,
                Class.forName("com.ibm.example.vault.VaultConnectionPoolDataSource").getDeclaredConstructor().newInstance());
        Map<String, PropertyDescriptor> beans = new HashMap<>();
        for (PropertyDescriptor bean : Introspector.getBeanInfo(factory.getClass()).getPropertyDescriptors()) {
            beans.put(bean.getName(), bean);
        }
        for (String name : new String[]{"URL", "user", "password", "authMount", "mount", "kvVersion",
                "namespace", "secretPath", "passwordField", "secretVersion", "connectTimeoutSeconds",
                "requestTimeoutSeconds", "allowHttp", "revokeOnClose"}) {
            assertNotNull(beans.get(name), name);
            assertEquals(String.class, beans.get(name).getPropertyType(), name);
            assertNotNull(beans.get(name).getWriteMethod(), name);
        }
        assertNull(beans.get("password").getReadMethod());
    }

    @Test void containerCanReadAndResetConnectionPropertiesBeforeReadingSecret() throws Exception {
        PooledConnection pooled = factory.getPooledConnection();
        Events events = new Events(); pooled.addConnectionEventListener(events);
        try (Connection c = pooled.getConnection()) {
            // WebSphere captures these when it initializes a managed connection.
            assertEquals("HashiCorp Vault KV", c.getMetaData().getDatabaseProductName());
            Properties clientInfo = c.getClientInfo();
            Map<String, Class<?>> typeMap = c.getTypeMap();
            assertTrue(clientInfo.isEmpty());
            assertNull(c.getClientInfo("ApplicationName"));
            assertTrue(typeMap.isEmpty());
            c.setClientInfo(clientInfo);
            c.setTypeMap(typeMap);
            c.setAutoCommit(c.getAutoCommit());
            c.setReadOnly(c.isReadOnly());
            c.setTransactionIsolation(c.getTransactionIsolation());
            c.setHoldability(c.getHoldability());
            assertEquals(1, paths.size(), "Connection property access must not call Vault");
            try (Statement s = c.createStatement();
                 ResultSet r = s.executeQuery("SELECT password FROM vault_secret")) {
                assertTrue(r.next());
                assertEquals("test-secret", r.getString(1));
            }
            assertEquals(0, events.errors);
        } finally { pooled.close(); }
    }

    @Test void unsupportedConnectionPropertiesCannotChangeOrPoisonPooledSession() throws Exception {
        PooledConnection pooled = factory.getPooledConnection();
        Events events = new Events(); pooled.addConnectionEventListener(events);
        try {
            Connection first = pooled.getConnection();
            Map<String, Class<?>> customTypes = first.getTypeMap();
            customTypes.put("app.custom_type", String.class);
            assertTrue(first.getTypeMap().isEmpty());
            assertThrows(SQLFeatureNotSupportedException.class, () -> first.setTypeMap(customTypes));
            assertThrows(SQLException.class, () -> first.setTypeMap(null));
            Properties info = first.getClientInfo();
            info.setProperty("ApplicationName", "unrecognized-client");
            assertTrue(first.getClientInfo().isEmpty());
            SQLClientInfoException error = assertThrows(SQLClientInfoException.class,
                    () -> first.setClientInfo(info));
            assertEquals(ClientInfoStatus.REASON_UNKNOWN_PROPERTY,
                    error.getFailedProperties().get("ApplicationName"));
            assertNotNull(first.getWarnings());
            first.clearWarnings();
            assertNull(first.getWarnings());
            assertThrows(SQLClientInfoException.class,
                    () -> first.setClientInfo("ApplicationName", "unrecognized-client"));
            first.setClientInfo("ApplicationName", null);
            first.setClientInfo(new Properties());
            assertNull(first.getClientInfo("ApplicationName"));
            assertThrows(SQLClientInfoException.class, () -> first.setClientInfo(null, "value"));
            assertThrows(SQLClientInfoException.class, () -> first.setClientInfo((Properties) null));
            assertThrows(SQLFeatureNotSupportedException.class, () -> first.setAutoCommit(false));
            first.close();
            try (Connection second = pooled.getConnection()) {
                assertTrue(second.getTypeMap().isEmpty());
                assertTrue(second.getClientInfo().isEmpty());
                assertNull(second.getWarnings());
                assertEquals(0, events.errors);
                assertEquals(1, requests("/login/configured-user"));
            }
        } finally { pooled.close(); }
    }

    @Test void closedConnectionPropertyAccessUsesDeclaredJdbcExceptionTypes() throws Exception {
        PooledConnection pooled = factory.getPooledConnection();
        Events events = new Events(); pooled.addConnectionEventListener(events);
        try {
            Connection c = pooled.getConnection();
            c.close();
            assertEquals("08003", assertThrows(SQLException.class, c::getTypeMap).getSQLState());
            assertEquals("08003", assertThrows(SQLException.class, c::getClientInfo).getSQLState());
            assertThrows(SQLException.class, () -> c.getClientInfo("ApplicationName"));
            assertThrows(SQLException.class, () -> c.setTypeMap(new HashMap<String, Class<?>>()));
            // A generic SQLException here would be wrapped in UndeclaredThrowableException by the proxy.
            assertEquals("08003", assertThrows(SQLClientInfoException.class,
                    () -> c.setClientInfo(new Properties())).getSQLState());
            assertEquals("08003", assertThrows(SQLClientInfoException.class,
                    () -> c.setClientInfo("ApplicationName", null)).getSQLState());
            assertEquals(0, events.errors);
            try (Connection next = pooled.getConnection()) { assertTrue(next.getTypeMap().isEmpty()); }
        } finally { pooled.close(); }
    }

    @Test void standaloneConnectionsAlsoExposeEmptyTypeMapAndClientInfo() throws Exception {
        Properties settings = new Properties();
        settings.setProperty("user", "standalone-user");
        settings.setProperty("password", "standalone-password");
        settings.setProperty("allowHttp", "true");
        try (Connection c = new VaultDriver().connect(factory.getURL(), settings)) {
            assertTrue(c.getTypeMap().isEmpty());
            c.setTypeMap(c.getTypeMap());
            assertTrue(c.getClientInfo().isEmpty());
            c.setClientInfo(c.getClientInfo());
        }
        assertEquals(1, requests("/revoke-self"));
    }

    @Test void aliasCredentialsAndCustomPropertiesReachVaultWithoutChangingFactoryCredentials() throws Exception {
        factory.setAuthMount("corp-users");
        factory.setMount("team-secret");
        factory.setNamespace("team/");
        factory.setSecretVersion("3");
        PooledConnection pooled = factory.getPooledConnection("alias-user", "alias-password");
        try (Connection c = pooled.getConnection(); Statement s = c.createStatement();
             ResultSet r = s.executeQuery("SELECT password FROM vault_secret")) {
            assertTrue(r.next());
            assertEquals("test-secret", r.getString(1));
            assertEquals("team/", namespace);
            assertEquals("{\"password\":\"alias-password\"}", loginBody);
            assertTrue(paths.contains("/v1/auth/corp-users/login/alias-user"));
            assertTrue(paths.contains("/v1/team-secret/data/apps/database?version=3"));
        } finally { pooled.close(); }
        pooled = factory.getPooledConnection();
        try {
            assertEquals("configured-user", factory.getUser());
            assertEquals("{\"password\":\"configured-password\"}", loginBody);
        } finally { pooled.close(); }
    }

    @Test void logicalCloseReturnsToPoolAndPhysicalCloseRevokesExactlyOnce() throws Exception {
        PooledConnection pooled = factory.getPooledConnection();
        Events events = new Events();
        pooled.addConnectionEventListener(events);
        pooled.addStatementEventListener(events);
        try {
            Connection first = pooled.getConnection();
            PreparedStatement statement = first.prepareStatement("SELECT password FROM vault_secret");
            ResultSet result = statement.executeQuery();
            assertSame(first, statement.getConnection());
            assertSame(first, first.getMetaData().getConnection());
            assertSame(first, first.unwrap(Connection.class));
            assertSame(statement, result.getStatement());
            first.close(); first.close();
            assertTrue(first.isClosed()); assertTrue(statement.isClosed()); assertTrue(result.isClosed());
            assertEquals(1, events.closed); assertEquals(1, events.statementsClosed);
            assertSame(pooled, events.source);
            assertEquals(0, requests("/revoke-self"));
            Connection second = pooled.getConnection();
            assertNotSame(first, second);
            assertTrue(second.isValid(1));
            assertThrows(SQLException.class, first::createStatement);
            assertEquals(0, events.errors);
            assertEquals(1, requests("/login/configured-user"));
            pooled.close(); pooled.close();
            assertTrue(second.isClosed());
            assertEquals(1, requests("/revoke-self"));
            assertEquals(1, events.closed);
            assertThrows(SQLException.class, pooled::getConnection);
        } finally { pooled.close(); }
    }

    @Test void secondCheckoutInvalidatesPreviousHandleAndAllItsChildren() throws Exception {
        PooledConnection pooled = factory.getPooledConnection();
        Events events = new Events(); pooled.addConnectionEventListener(events);
        try {
            Connection first = pooled.getConnection();
            Statement statement = first.createStatement();
            ResultSet result = statement.executeQuery("SELECT password FROM vault_secret");
            Connection second = pooled.getConnection();
            assertTrue(first.isClosed()); assertTrue(statement.isClosed()); assertTrue(result.isClosed());
            assertEquals(0, events.closed);
            first.close();
            assertFalse(second.isClosed());
            assertThrows(SQLException.class, () -> result.getString(1));
            assertEquals(0, events.errors);
            second.close();
            assertEquals(1, events.closed);
        } finally { pooled.close(); }
    }

    @Test void queryAndUnsupportedTransactionErrorsDoNotEvictHealthySession() throws Exception {
        PooledConnection pooled = factory.getPooledConnection();
        Events events = new Events(); pooled.addConnectionEventListener(events);
        try (Connection c = pooled.getConnection(); Statement s = c.createStatement()) {
            assertThrows(SQLFeatureNotSupportedException.class, () -> c.setAutoCommit(false));
            assertThrows(SQLFeatureNotSupportedException.class, c::commit);
            assertEquals(Connection.TRANSACTION_NONE, c.getTransactionIsolation());
            readStatus = 404;
            SQLException missing = assertThrows(SQLException.class,
                    () -> s.executeQuery("SELECT password FROM vault_secret"));
            assertEquals("02000", missing.getSQLState());
            assertEquals(0, events.errors);
            readStatus = 200;
            try (ResultSet r = s.executeQuery("SELECT password FROM vault_secret")) { assertTrue(r.next()); }
        } finally { pooled.close(); }
    }

    @Test void fatalReadFailureNotifiesListenersOnceAndPreventsSessionReuse() throws Exception {
        PooledConnection pooled = factory.getPooledConnection();
        Events events = new Events();
        pooled.addConnectionEventListener(events); pooled.addStatementEventListener(events);
        try (Connection c = pooled.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT password FROM vault_secret")) {
            readStatus = 503;
            SQLException error = assertThrows(SQLException.class, s::executeQuery);
            assertEquals(1, events.errors); assertEquals(1, events.statementsErrored);
            assertSame(error, events.error); assertSame(pooled, events.source);
            assertFalse(c.isValid(1));
            assertThrows(SQLException.class, pooled::getConnection);
            assertThrows(SQLException.class, c::createStatement);
            assertEquals(1, events.errors);
        } finally { pooled.close(); }
        assertEquals(0, events.closed);
    }

    @Test void failedTokenValidationEvictsSessionAndSupportsReentrantPoolClose() throws Exception {
        PooledConnection pooled = factory.getPooledConnection();
        Events events = new Events();
        pooled.addConnectionEventListener(events);
        pooled.addConnectionEventListener(new ConnectionEventListener() {
            @Override public void connectionClosed(ConnectionEvent event) { }
            @Override public void connectionErrorOccurred(ConnectionEvent event) {
                try { pooled.close(); } catch (SQLException e) { throw new AssertionError(e); }
            }
        });
        try {
            Connection c = pooled.getConnection();
            lookupStatus = 403;
            assertFalse(c.isValid(1)); assertTrue(c.isClosed());
            assertEquals(1, events.errors); assertEquals(1, requests("/revoke-self"));
        } finally { pooled.close(); }
    }

    @Test void statementErrorCleanupCannotReturnFailedSessionToPool() throws Exception {
        PooledConnection pooled = factory.getPooledConnection();
        Events events = new Events(); pooled.addConnectionEventListener(events);
        Connection c = pooled.getConnection();
        pooled.addStatementEventListener(new StatementEventListener() {
            @Override public void statementClosed(StatementEvent event) { }
            @Override public void statementErrorOccurred(StatementEvent event) {
                try { c.close(); } catch (SQLException e) { throw new AssertionError(e); }
                assertThrows(SQLException.class, pooled::getConnection);
            }
        });
        try (PreparedStatement s = c.prepareStatement("SELECT password FROM vault_secret")) {
            readStatus = 403;
            assertThrows(SQLException.class, s::executeQuery);
            assertEquals(1, events.errors); assertEquals(0, events.closed);
            assertTrue(c.isClosed());
        } finally { pooled.close(); }
    }

    @Test void removedListenersDoNotReceiveEventsAndRevocationCanBeDisabled() throws Exception {
        factory.setRevokeOnClose("false");
        PooledConnection pooled = factory.getPooledConnection();
        Events events = new Events();
        pooled.addConnectionEventListener(events); pooled.removeConnectionEventListener(events);
        pooled.addStatementEventListener(events); pooled.removeStatementEventListener(events);
        try (Connection c = pooled.getConnection();
             PreparedStatement s = c.prepareStatement("SELECT password FROM vault_secret")) {
            s.close(); c.close();
            assertEquals(0, events.closed); assertEquals(0, events.statementsClosed);
        } finally { pooled.close(); }
        assertEquals(0, requests("/revoke-self"));
    }

    @Test void revocationFailureStillClosesPoolAndChildren() throws Exception {
        PooledConnection pooled = factory.getPooledConnection();
        Connection c = pooled.getConnection();
        Statement s = c.createStatement();
        revokeStatus = 500;
        assertThrows(SQLException.class, pooled::close);
        assertTrue(c.isClosed()); assertTrue(s.isClosed());
        assertThrows(SQLException.class, pooled::getConnection);
        pooled.close();
        assertEquals(1, requests("/revoke-self"));
    }

    @Test void validatesConfigurationAndExplicitCredentialsBeforeNetworkAccess() throws Exception {
        assertThrows(SQLException.class, () -> factory.getPooledConnection(null, null));
        factory.setKvVersion("3");
        assertThrows(SQLException.class, factory::getPooledConnection);
        factory.setKvVersion("2");
        factory.setURL("jdbc:vault:http://192.168.1.23:8200");
        assertThrows(SQLException.class, factory::getPooledConnection);
        factory.setURL(null);
        assertThrows(SQLException.class, factory::getPooledConnection);
        assertThrows(SQLException.class, () -> factory.setLoginTimeout(-1));
        assertTrue(paths.isEmpty());
    }

    @Test void factoryLoginTimeoutCapsLoginWithoutChangingGlobalTimeout() throws Exception {
        int global = DriverManager.getLoginTimeout();
        factory.setLoginTimeout(1);
        loginDelay = 2200;
        assertThrows(SQLTimeoutException.class, factory::getPooledConnection);
        assertEquals(global, DriverManager.getLoginTimeout());
        assertEquals("15", factory.getRequestTimeoutSeconds());
        assertEquals(1, factory.getLoginTimeout());
    }
}
