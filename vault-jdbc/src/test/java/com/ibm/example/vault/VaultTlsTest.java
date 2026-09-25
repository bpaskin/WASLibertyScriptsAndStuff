package com.ibm.example.vault;

import com.sun.net.httpserver.HttpsServer;
import com.sun.net.httpserver.HttpsConfigurator;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class VaultTlsTest {
    final char[] password = "disposable-test-keystore".toCharArray();
    Path directory;
    Path truststore;
    HttpsServer server;
    ExecutorService executor;

    @BeforeAll void start() throws Exception {
        directory = Files.createTempDirectory(Paths.get("target"), "tls-test-");
        Path keys = directory.resolve("server.jks");
        truststore = directory.resolve("trust.jks");
        String keytool = Paths.get(System.getProperty("java.home"), "bin", "keytool").toString();
        Process generation = new ProcessBuilder(keytool, "-genkeypair", "-alias", "server", "-keyalg", "RSA",
                "-keysize", "2048", "-sigalg", "SHA256withRSA", "-validity", "2", "-dname", "CN=localhost",
                "-ext", "SAN=dns:localhost", "-storetype", "JKS", "-keystore", keys.toString(),
                "-storepass", new String(password), "-keypass", new String(password), "-noprompt")
                .redirectErrorStream(true).redirectOutput(directory.resolve("keytool.log").toFile()).start();
        try {
            assertTrue(generation.waitFor(20, TimeUnit.SECONDS), "Test certificate generation timed out");
            assertEquals(0, generation.exitValue(), "Test certificate generation failed");
        } finally { if (generation.isAlive()) generation.destroyForcibly(); }
        KeyStore store = KeyStore.getInstance("JKS");
        try (InputStream in = Files.newInputStream(keys)) { store.load(in, password); }
        KeyStore trust = KeyStore.getInstance("JKS"); trust.load(null, password);
        trust.setCertificateEntry("server", store.getCertificate("server"));
        try (OutputStream out = Files.newOutputStream(truststore)) { trust.store(out, password); }
        KeyManagerFactory factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        factory.init(store, password);
        SSLContext context = SSLContext.getInstance("TLS"); context.init(factory.getKeyManagers(), null, null);
        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(context));
        executor = Executors.newCachedThreadPool(); server.setExecutor(executor);
        server.createContext("/", exchange -> {
            try {
                TestSupport.readAll(exchange.getRequestBody());
                String path = exchange.getRequestURI().getPath();
                if (path.endsWith("/revoke-self")) { exchange.sendResponseHeaders(204, -1); return; }
                String body = path.contains("/login/") ? "{\"auth\":{\"client_token\":\"test-token\"}}"
                        : "{\"data\":{\"data\":{\"password\":\"test-secret\"}}}";
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally { exchange.close(); }
        });
        server.start();
    }

    @AfterAll void stop() {
        if (server != null) server.stop(0);
        if (executor != null) executor.shutdownNow();
    }

    void probe(String host, boolean trusted, boolean success) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
        if (trusted) {
            command.add("-Djavax.net.ssl.trustStore=" + truststore.toAbsolutePath());
            command.add("-Djavax.net.ssl.trustStorePassword=" + new String(password));
            command.add("-Djavax.net.ssl.trustStoreType=JKS");
        }
        String jar = System.getProperty("driverJar");
        String classpath = jar == null ? System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"))
                : TestSupport.driverClasspath(jar) + File.pathSeparator + Paths.get("target", "test-classes");
        command.add("-cp"); command.add(classpath);
        command.add(TlsProbe.class.getName());
        command.add("jdbc:vault:https://" + host + ":" + server.getAddress().getPort());
        command.add(success ? "success" : "reject");
        Process child = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(directory.resolve(host + "-" + trusted + ".log").toFile()).start();
        try {
            assertTrue(child.waitFor(20, TimeUnit.SECONDS), "TLS probe timed out");
            assertEquals(0, child.exitValue(), "TLS probe failed; inspect target/tls-test-* logs");
        } finally { if (child.isAlive()) child.destroyForcibly(); }
    }

    @Test void trustedCertificateRetrievesSecret() throws Exception { probe("localhost", true, true); }
    @Test void untrustedCertificateIsRejected() throws Exception { probe("localhost", false, false); }
    @Test void incorrectHostnameIsRejected() throws Exception { probe("127.0.0.1", true, false); }
}
