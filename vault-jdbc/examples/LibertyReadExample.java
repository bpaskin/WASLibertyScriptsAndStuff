import javax.naming.InitialContext;
import javax.sql.DataSource;
import java.sql.*;

class LibertyReadExample {
    void read() throws Exception {

        DataSource vault = (DataSource) new InitialContext().lookup("jdbc/vaultSecrets");
        try (Connection connection = vault.getConnection();
                Statement query = connection.createStatement();
                ResultSet result = query.executeQuery("SELECT password FROM vault_secret")) {
            if (result.next()) {
                String retrievedPassword = result.getString("password");
                // Pass the password to the component that needs it. Do not log it.
            }
        }
    }
}
