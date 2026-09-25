package com.ibm.example.vault;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLNonTransientConnectionException;
import java.util.Properties;

/** Fresh JVM makes truststore behavior independent of JSSE's cached default context. */
public class TlsProbe {
    public static void main(String[] args) throws Exception {
        Properties p = new Properties(); p.setProperty("user", "test-user"); p.setProperty("password", "test-login");
        boolean expectSuccess = args[1].equals("success");
        try (Connection c = DriverManager.getConnection(args[0], p);
             PreparedStatement s = c.prepareStatement("SELECT password FROM vault_secret WHERE path = ?")) {
            if (!expectSuccess) throw new AssertionError("TLS connection should have been rejected");
            s.setString(1, "app/db");
            try (ResultSet r = s.executeQuery()) {
                if (!r.next() || !"test-secret".equals(r.getString(1))) throw new AssertionError("Secret read failed");
            }
        } catch (SQLNonTransientConnectionException e) {
            if (expectSuccess || !"08001".equals(e.getSQLState())) throw e;
        }
    }
}
