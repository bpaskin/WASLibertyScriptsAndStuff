package com.ibm.example.vault;

import com.ibm.json.java.JSONObject;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.HttpURLConnection;
import java.io.OutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLInvalidAuthorizationSpecException;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Starts an isolated local Vault dev process; never uses an existing Vault instance. */
@EnabledIfEnvironmentVariable(named = "VAULT_INTEGRATION", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VaultIntegrationTest {
    String base;
    String root;
    final String loginPassword = "disposable-login-" + UUID.randomUUID();
    Process process;

    @BeforeAll void start() throws Exception {
        int port;
        try (ServerSocket socket = new ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1"))) { port = socket.getLocalPort(); }
        base = "http://127.0.0.1:" + port;
        root = UUID.randomUUID().toString();
        ProcessBuilder builder = new ProcessBuilder(System.getenv().getOrDefault("VAULT_BINARY", "vault"),
                "server", "-dev", "-dev-no-store-token", "-dev-listen-address=127.0.0.1:" + port, "-dev-root-token-id=" + root);
        builder.redirectErrorStream(true).redirectOutput(Paths.get("target", "vault-integration.log").toFile());
        process = builder.start();
        try {
            boolean ready = false;
            for (int i = 0; i < 100 && process.isAlive(); i++) {
                try {
                    ready = requestStatus("/sys/health", null) == 200;
                    if (ready) {
                        break;
                    }
                } catch (IOException ignored) { }
                Thread.sleep(100);
            }
            assertTrue(ready, "Disposable Vault did not start; inspect target/vault-integration.log");
            admin("/sys/auth/userpass", TestSupport.map("type", "userpass"));
            admin("/sys/mounts/legacy", TestSupport.map("type", "kv", "options", TestSupport.map("version", "1")));
            admin("/sys/policies/acl/jdbc-reader", TestSupport.map("policy",
                    "path \"secret/data/apps/database\" { capabilities = [\"read\"] }\n"
                    + "path \"legacy/apps/database\" { capabilities = [\"read\"] }"));
            admin("/auth/userpass/users/jdbc-user", TestSupport.map("password", loginPassword,
                    "token_policies", "jdbc-reader", "token_ttl", "2m", "token_max_ttl", "2m"));
            admin("/secret/data/apps/database", TestSupport.map("data", TestSupport.map("password", "v2-old", "db_password", "custom-old")));
            admin("/secret/data/apps/database", TestSupport.map("data", TestSupport.map("password", "v2-current", "db_password", "custom-current")));
            admin("/legacy/apps/database", TestSupport.map("password", "v1-current"));
        } catch (Throwable failure) { stop(); throw failure; }
    }

    @AfterAll void stop() throws Exception {
        if (process != null) {
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    int requestStatus(String path, JSONObject body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create(base + "/v1" + path).toURL().openConnection();
        try {
            connection.setConnectTimeout(1000); connection.setReadTimeout(5000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("X-Vault-Token", root);
            if (body != null) {
                connection.setRequestMethod("POST"); connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                byte[] payload = body.serialize().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(payload.length);
                try (OutputStream output = connection.getOutputStream()) { output.write(payload); }
            }
            return connection.getResponseCode();
        } finally { connection.disconnect(); }
    }

    void admin(String path, JSONObject body) throws Exception {
        int status = requestStatus(path, body);
        assertTrue(status >= 200 && status < 300, "Fixture setup failed with HTTP " + status);
    }

    Properties props() {
        Properties p = new Properties(); p.setProperty("user", "jdbc-user"); p.setProperty("password", loginPassword);
        p.setProperty("allowHttp", "true"); return p;
    }

    String read(Properties p, String field) throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:vault:" + base, p);
             PreparedStatement s = c.prepareStatement("SELECT password FROM vault_secret WHERE path = ? AND field = ?")) {
            assertTrue(c.isValid(2)); s.setString(1, "apps/database"); s.setString(2, field);
            try (ResultSet r = s.executeQuery()) {
                assertTrue(r.next()); String result = r.getString(1); assertFalse(r.next()); return result;
            }
        }
    }

    @Test void realUserpassKv2CustomFieldAndVersion() throws Exception {
        assertEquals("v2-current", read(props(), "password"));
        assertEquals("custom-current", read(props(), "db_password"));
        Properties p = props(); p.setProperty("secretVersion", "1");
        assertEquals("v2-old", read(p, "password"));
    }

    @Test void realKv1() throws Exception {
        Properties p = props(); p.setProperty("mount", "legacy"); p.setProperty("kvVersion", "1");
        assertEquals("v1-current", read(p, "password"));
    }

    @Test void realWrongPassword() {
        Properties p = props(); p.setProperty("password", "incorrect");
        assertThrows(SQLInvalidAuthorizationSpecException.class, () -> DriverManager.getConnection("jdbc:vault:" + base, p));
    }

    @Test void realPolicyDenial() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:vault:" + base, props());
             PreparedStatement s = c.prepareStatement("SELECT password FROM vault_secret WHERE path = ?")) {
            s.setString(1, "not-authorized");
            assertThrows(SQLInvalidAuthorizationSpecException.class, s::executeQuery);
        }
    }

    @Test void packagedJarLoadsAndRunsExample() throws Exception {
        String jar = System.getProperty("driverJar");
        Assumptions.assumeTrue(jar != null, "Set -DdriverJar after packaging to test the driver with provided JSON4J");
        java.nio.file.Path classes = Paths.get("target", "example-classes");
        Files.createDirectories(classes);
        javax.tools.JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Run the packaged-JAR test with a JDK, not a JRE");
        assertEquals(0, compiler.run(null, null, null, "-encoding", "UTF-8", "-source", "8", "-target", "8",
                "-cp", jar, "-d", classes.toString(), "examples/RetrievePassword.java"));
        ProcessBuilder builder = new ProcessBuilder(Paths.get(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", TestSupport.driverClasspath(jar) + File.pathSeparator + classes, "RetrievePassword");
        Map<String, String> env = builder.environment();
        env.put("VAULT_JDBC_URL", "jdbc:vault:" + base); env.put("VAULT_USER", "jdbc-user");
        env.put("VAULT_PASSWORD", loginPassword); env.put("VAULT_SECRET_PATH", "apps/database");
        env.put("VAULT_ALLOW_HTTP", "true");
        builder.redirectErrorStream(true);
        Process example = builder.start();
        try {
            assertTrue(example.waitFor(20, TimeUnit.SECONDS), "Example timed out");
            String output = new String(TestSupport.readAll(example.getInputStream()), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(0, example.exitValue(), "Example failed");
            assertTrue(output.contains("Password retrieved successfully."));
            assertFalse(output.contains("v2-current"));
        } finally {
            if (example.isAlive()) {
                example.destroyForcibly();
            }
        }
    }
}
