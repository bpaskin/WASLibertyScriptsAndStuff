# Vault Secret JDBC Driver

A Java 8+ driver that authenticates to HashiCorp Vault using a username and
password and returns a selected KV secret field as a one-row JDBC `ResultSet`.
The default authentication mount is `userpass` and the default secrets engine
is KV v2 at `secret`.

This is a deliberately small, read-only JDBC adapter for application code. It
implements the query forms below and reports `jdbcCompliant() == false`. It does
not provide SQL-92, table discovery for database browsers, transactions, writes,
stored procedures, batches, or general ORM compatibility. Unsupported JDBC
operations throw `SQLFeatureNotSupportedException`.

---

## Table of contents

1. [Build and install](#build-and-install)
2. [Vault setup](#vault-setup)
   - [Start or connect to Vault](#start-or-connect-to-vault)
   - [Enable the KV secrets engine](#enable-the-kv-secrets-engine)
   - [Write a secret](#write-a-secret)
   - [Enable the userpass auth method](#enable-the-userpass-auth-method)
   - [Create a least-privilege policy](#create-a-least-privilege-policy)
   - [Create the Vault user](#create-the-vault-user)
   - [Verify access manually](#verify-access-manually)
3. [Liberty server configuration](#liberty-server-configuration)
4. [WebSphere Application Server configuration](#websphere-application-server-configuration)
5. [Servlet example](#servlet-example)
6. [Retrieve a password](#retrieve-a-password)
7. [Connection properties](#connection-properties)
8. [Vault access and token lifecycle](#vault-access-and-token-lifecycle)
9. [TLS and operational behavior](#tls-and-operational-behavior)
10. [Runnable example](#runnable-example)
11. [Tests](#tests)
12. [Driver architecture](#driver-architecture)
13. [Relationship to the supplied Vault example](#relationship-to-the-supplied-vault-example)
14. [API references](#api-references)

---

## Build and install

The driver requires Java 8 or newer and uses JSON4J supplied by tWAS or
Liberty. It compiles against `io.openliberty:com.ibm.json4j:1.0.112.RELEASE`
with Maven `provided` scope. HTTP/TLS continues to use the built-in
`HttpURLConnection` / `HttpsURLConnection` APIs; Apache HttpClient is not used.

Every build includes `com.ibm.example.vault.was.VaultDataStoreHelper` in the
regular `target/vault-jdbc-1.0.2.jar`. The helper is part of `src/main/java`;
no Maven profile or separate helper JAR is required.

The helper requires WebSphere's public API at compile time. On a new checkout,
copy `was_public.jar` from your traditional WebSphere installation once:

```sh
mkdir -p work/websphere-api
cp /opt/IBM/WebSphere/AppServer905/dev/was_public.jar work/websphere-api/was_public.jar
```

`work/` is ignored by version control and is preserved by `mvn clean`. The API
JAR is not packaged in the driver or servlet WAR. If you keep the API elsewhere,
set `-Dwas.public.jar=/absolute/path/to/was_public.jar` when building. The API is
needed to compile every build, including one intended for Liberty; Liberty does
not need this WebSphere API JAR at runtime for normal JDBC driver use.

Build with JDK 8+ and Maven 3.9.x:

```sh
mvn clean package
```

This also runs the project's existing Liberty server setup. To build just the
JAR and WAR without that setup, use:

```sh
mvn clean prepare-package war:war
```

Both commands always include the helper and produce these artifacts:

- `target/vault-jdbc-1.0.2.jar` — the driver JAR, which must be placed in the
  Liberty shared resources directory or the tWAS JDBC Provider classpath. It
  contains all driver classes and the `META-INF/services/java.sql.Driver` SPI
  descriptor.
- `target/vault-jdbc-1.0.2.war` — the example web application. The WAR contains
  only `VaultSecretServlet.class`; driver classes are intentionally excluded from
  `WEB-INF/classes` so that the JDBC classloader, not the web classloader, owns
  the driver and its SPI registration.

Deploy the driver JAR with the server's JSON4J classes visible to its
classloader. JSON4J is neither bundled nor copied into the build output. JUnit
is a test-only dependency.

The POM declares IBM's
[Open Liberty artifact repository](https://public.dhe.ibm.com/ibmdl/export/pub/software/olrepo/io/openliberty/com.ibm.json4j/1.0.112.RELEASE/),
which hosts the dependency used for compilation and tests. A clean build must be
able to reach that repository unless the artifact is already cached.

Driver class: `com.ibm.example.vault.VaultDriver`. JDBC service discovery loads
it automatically from the classpath. Applications with isolated classloaders can
explicitly call `Class.forName("com.ibm.example.vault.VaultDriver")`.

---

## Vault setup

This section walks through preparing a Vault instance so that the driver can
authenticate and read secrets. The commands use the
[Vault CLI](https://developer.hashicorp.com/vault/docs/commands); the same
operations are available through the Vault UI and REST API.

### Start or connect to Vault

**Development server (local testing only — data is in-memory and lost on restart):**

```sh
vault server -dev -dev-root-token-id="root"
```

In a second terminal, export the environment variables so the CLI can reach it:

```sh
export VAULT_ADDR='http://127.0.0.1:8200'
export VAULT_TOKEN='root'
```

> **Production:** Connect to your existing Vault cluster. Authenticate with a
> token or login method that has admin-level privileges for the setup steps
> below. Use `https://` and verify the TLS certificate. After setup is complete
> the privileged token is no longer needed.

Verify connectivity:

```sh
vault status
```

---

### Enable the KV secrets engine

Skip this step if KV v2 is already mounted at `secret` (the default dev server
pre-enables it).

**KV v2 (recommended):**

```sh
vault secrets enable -path=secret kv-v2
```

**KV v1 (legacy):**

```sh
vault secrets enable -path=secret kv
```

To use a custom mount path (e.g. `app-secrets`), replace `secret` above and set
the `mount=app-secrets` connection property when connecting.

---

### Write a secret

**KV v2:**

```sh
vault kv put secret/apps/database \
    db_password="correct-horse-battery-staple" \
    db_user="appuser"
```

**KV v1:**

```sh
vault kv put secret/apps/database \
    db_password="correct-horse-battery-staple"
```

Verify the secret was stored:

```sh
# KV v2
vault kv get secret/apps/database

# KV v1
vault read secret/apps/database
```

The JDBC driver reads the path `apps/database` at mount `secret`. The field
`db_password` is set as the `passwordField` connection property. The mount and
`data/` path segment are added automatically; do not include them in the `path`
parameter.

---

### Enable the userpass auth method

Skip this step if userpass is already enabled at `auth/userpass`.

```sh
vault auth enable userpass
```

To use a custom mount path (e.g. `corp-users`), replace `userpass` above and
set `authMount=corp-users` in the connection properties.

---

### Create a least-privilege policy

Save the following policy to a file called `vault-jdbc-policy.hcl`:

```hcl
# Allow the driver to authenticate and obtain a client token.
# (Covered by Vault's built-in "default" policy; included here explicitly
#  for deployments that restrict or remove the default policy.)
path "auth/token/lookup-self" {
  capabilities = ["read"]
}
path "auth/token/revoke-self" {
  capabilities = ["update"]
}

# KV v2: allow reading the specific secret path.
# The data/ segment is required for KV v2 read operations.
path "secret/data/apps/database" {
  capabilities = ["read"]
}

# KV v1: allow reading the specific secret path.
# Remove or comment out when using KV v2 only.
# path "secret/apps/database" {
#   capabilities = ["read"]
# }
```

Apply the policy:

```sh
vault policy write vault-jdbc-policy vault-jdbc-policy.hcl
```

> **Principle of least privilege:** The policy grants `read` only on the exact
> secret path. Avoid using wildcard paths (`secret/data/*`) or broad `read`
> capabilities in production.

---

### Create the Vault user

```sh
vault write auth/userpass/users/jdbc-user \
    password="$(openssl rand -hex 32)" \
    policies="vault-jdbc-policy" \
    token_ttl="1h" \
    token_max_ttl="4h"
```

Store the generated password securely — it is the value you supply as
`VAULT_PASSWORD` at runtime. The driver uses it only to authenticate; it is
discarded from memory after the connection is established.

To read back the stored password (only possible with appropriate admin access):

```sh
vault read auth/userpass/users/jdbc-user
# Note: Vault does not return the password in plain text after creation.
# Store it safely at creation time.
```

To update the password later:

```sh
vault write auth/userpass/users/jdbc-user \
    password="new-secure-password"
```

---

### Verify access manually

Confirm the setup end-to-end before configuring the driver:

```sh
# Step 1: Log in as the new user and capture the token.
vault login -method=userpass username=jdbc-user
# Enter the password when prompted.

# Step 2: Read the secret with the resulting token.
vault kv get -field=db_password secret/apps/database
# Expected output: correct-horse-battery-staple

# Step 3: Revoke the token (simulates revokeOnClose=true).
vault token revoke -self
```

All three steps must succeed without permission errors before the driver will
work. If step 2 fails with `permission denied`, re-check the policy path and
verify the policy is attached to the user.

---

## Java 8 compatibility

The driver and JSON4J target Java 8 bytecode (class-file version 52).
`JSONObject.serialize()` encodes the login payload and `JSON.parse()` reads
Vault responses. The driver has no custom JSON classes.

---

## Liberty server configuration

[`src/main/liberty/config/server.xml`](src/main/liberty/config/server.xml)
configures the JNDI data source `jdbc/vaultSecrets` for Java 8 with `jdbc-4.2`,
`jndi-1.0`, `json-1.0`, and `servlet-4.0`. The file in
[`examples/server.xml`](examples/server.xml) is a standalone snippet for
merging into an existing server; both configure the same data source.

1. Build with `mvn clean package`. The build automatically copies
   `target/vault-jdbc-1.0.2.jar` into
   `target/liberty/wlp/usr/shared/resources/vault-jdbc/` before Liberty starts.
   For a standalone Liberty installation, copy the JAR to
   `wlp/usr/shared/resources/vault-jdbc/` manually.
2. Merge the example into your application's Liberty `server.xml`. Keep your
   application's deployment and other required features. Liberty's
   [json-1.0 feature](https://openliberty.io/docs/latest/reference/feature/json-1.0.html)
   supplies JSON4J; no JSON4J JAR needs to accompany the driver.
3. Set `VAULT_JDBC_URL`, `VAULT_USER`, and `VAULT_PASSWORD` in Liberty's startup
   environment. Use an HTTPS origin such as
   `jdbc:vault:https://vault.example.com:8200`. These are the Vault user's login
   credentials. Set `allowHttp="true"` in `<properties>` and use a loopback URL
   only for local development (see [Connection properties](#connection-properties)).
4. Set `mount`, `secretPath`, and `passwordField` in the data source's
   `properties` element. The example reads `db_password` from `apps/database` at
   the `secret` KV v2 mount. Configure the Liberty JVM's TLS trust for your
   Vault endpoint as described in [TLS and operational behavior](#tls-and-operational-behavior).

To start the packaged example server directly:

```sh
VAULT_JDBC_URL=jdbc:vault:https://vault.example.com:8200 \
VAULT_USER=jdbc-user \
VAULT_PASSWORD=<password> \
mvn liberty:run
```

The servlet is then available at `GET http://localhost:9080/vault-example/secret`.

Liberty discovers this `java.sql.Driver` through its service descriptor and
exposes it as a `javax.sql.DataSource`, as described in the
[Liberty JDBC configuration documentation](https://openliberty.io/docs/latest/relational-database-connections-JDBC.html).
Application code running inside that Liberty server can use a direct JNDI lookup:

```java
import javax.naming.InitialContext;
import javax.sql.DataSource;
import java.sql.*;

DataSource vault = (DataSource) new InitialContext().lookup("jdbc/vaultSecrets");
try (Connection connection = vault.getConnection();
     Statement query = connection.createStatement();
     ResultSet result = query.executeQuery("SELECT password FROM vault_secret")) {
    if (result.next()) {
        String retrievedPassword = result.getString("password");
        // Pass the password to the component that needs it. Do not log it.
    }
}
```

This query uses the path and field configured in `server.xml`. The parameterized
queries below can override them. The example enables container authentication for
direct lookups, so `getConnection()` uses `vaultLogin`.

The [data source settings](https://openliberty.io/docs/latest/reference/config/dataSource.html)
disable transactions and statement caching and select `TRANSACTION_NONE`. The
[connection manager](https://openliberty.io/docs/latest/reference/config/connectionManager.html)
disables sharing for direct lookups and uses `agedTimeout="0"` to disable
connection reuse. Closing the connection allows the driver to revoke its Vault
token.

> **Classloader isolation:** The driver JAR must be in the Liberty shared
> resources `<library>`, not inside the WAR. If driver classes appear in both
> `WEB-INF/classes` and the library, Liberty's web classloader registers a second
> copy of `VaultDriver` that the JDBC classloader cannot see, causing
> `CWNEN0030E: Provider com.ibm.example.vault.VaultDriver not found`. The
> Maven build's `<packagingExcludes>` in `pom.xml` handles this automatically
> for the example WAR; replicate that exclusion for any WAR that packages driver
> sources directly.

### Debug logging

All driver classes emit `FINE`-level (`java.util.logging`) trace covering
configuration parsing, connection lifecycle, HTTP requests, and secret reads.
Credentials and secret values are never logged. To enable in Liberty, add to
`server.xml`:

```xml
<logging traceSpecification="com.ibm.example.vault.*=fine"/>
```

Or add to `bootstrap.properties`:

```properties
com.ibm.ws.logging.trace.specification=com.ibm.example.vault.*=fine
```

Trace output appears in `logs/trace.log`.

---

## WebSphere Application Server configuration

Use `com.ibm.example.vault.VaultConnectionPoolDataSource` as the traditional
WebSphere Application Server (tWAS) JDBC provider implementation class. It is a
non-XA `javax.sql.ConnectionPoolDataSource` factory included in the driver JAR.
`com.ibm.example.vault.VaultDriver` implements `java.sql.Driver` and is intended
for DriverManager/Liberty use. Configuring it directly as a tWAS provider causes
`DSRA8101E` because it cannot be cast to `ConnectionPoolDataSource`. See IBM's
[data access portability requirements](https://www.ibm.com/docs/en/was/8.5.5?topic=jca-data-access-portability-features).

The adapter supplies pooled connections and String JavaBean setters for the
Vault properties below. The driver still does not support transactions or XA;
configure a non-transactional data source. Its pooling lifecycle is covered by
local tests, but full integration must be validated on your tWAS installation.
Ensure the server's JSON4J classes are visible to the provider classloader.

The connection exposes an empty JDBC type map and empty client-info properties,
and accepts resetting these to empty values. Vault has no SQL user-defined types
or client-info storage: non-empty type maps and attempts to set client-info
values are rejected. These defaults let the container inspect the connection
before its first secret query. See the JDBC
[Connection API](https://docs.oracle.com/javase/8/docs/api/java/sql/Connection.html).

Run the Jython commands below in **wsadmin**, not standalone Python:

```sh
wsadmin.sh -lang jython
```

Connect to the Deployment Manager for cluster-scoped configuration. The examples
share variables across steps and use `WASResort` as the cluster name; replace it
with your cluster. For multiline blocks, save them in execution order to a `.py`
file and run `wsadmin.sh -lang jython -f <script.py>`. When entering blocks
interactively, use a blank line to finish each `if` or `for` block.

### Update an existing WASResort data source

Use this procedure if `jdbc/vaultSecrets` already uses
`com.ibm.example.vault.VaultConnectionPoolDataSource` and fails with
`Unsupported JDBC operation: setTransactionIsolation`.

1. Build the combined JDBC JAR as described in step 1 below, or use the supplied
   combined build. Replace the driver file on the Deployment Manager host and
   every cluster node:

   ```text
   /opt/IBM/WebSphere/jdbc/vault-jdbc-1.0.2.jar
   ```

   This JAR contains `VaultConnectionPoolDataSource`, `VaultDataStoreHelper`,
   and the `getTypeMap` / `getClientInfo` fixes.

2. On the Deployment Manager host, open wsadmin with Jython and apply the
   following changes to the existing data source. Adjust the cluster, provider,
   and data source names if yours differ:

   - Set the provider classpath to the single combined JAR (remove any old
     separate-helper entry).
   - Set the implementation class to
     `com.ibm.example.vault.was.VaultDataStoreHelper`.
   - Enable `nonTransactionalDataSource`, disable the statement cache, and
     remove `webSphereDefaultIsolationLevel`.
   - Preserve your Vault URL, secret properties, credentials, and
     authentication mapping. For the configuration in this example, retain
     `DmgrNode/vault` with `DefaultPrincipalMapping`.

3. Synchronize the nodes and restart every affected cluster member to load the
   helper. Node synchronization does not copy the external JDBC JAR. The servlet WAR
   does not need replacement for this helper update.

4. Retry the servlet to verify a secret read. The helper selects
   `TRANSACTION_NONE` for ordinary lookups; explicit transactional isolation or
   access-intent requirements remain unsupported. Compilation and local driver
   tests do not replace verification on your WebSphere installation.

For a new configuration, follow the numbered setup steps below.

### 1. Build and copy the JDBC JAR

After the one-time API setup in [Build and install](#build-and-install), build
from the project root:

```sh
mvn clean prepare-package war:war
```

The wrapper can copy the public API from a WebSphere installation and then run
that same build:

```sh
sh websphere/build-helper.sh /opt/IBM/WebSphere/AppServer905
```

After setup, `sh websphere/build-helper.sh` reuses the copied API. Use Maven with
JDK 8 or newer. The helper source is
`src/main/java/com/ibm/example/vault/was/VaultDataStoreHelper.java`; it is always
compiled with the JDBC sources into `target/vault-jdbc-1.0.2.jar`. The helper and
all other driver classes are excluded from the servlet WAR. WebSphere and JCA
API dependencies are not bundled in either artifact.

Copy only `target/vault-jdbc-1.0.2.jar` to
`/opt/IBM/WebSphere/jdbc/vault-jdbc-1.0.2.jar` on every cluster node and the
Deployment Manager. The file must be readable by the server process. Verify the
archive contains `com/ibm/example/vault/was/VaultDataStoreHelper.class`; older JARs
with the same `1.0.2` filename may not include it.

For an existing pooled provider, follow the steps in
[Update an existing WASResort data source](#update-an-existing-wasresort-data-source)
on the Deployment Manager after replacing the JAR to select the helper without
changing Vault credentials or authentication mappings. Node synchronization
does not copy this external JAR to the nodes. A separate helper JAR is no longer
needed in the JDBC provider classpath.

### 2. Create the JDBC Provider

In the admin console navigate to **Resources → JDBC → JDBC Providers** and
click **New**.

| Field | Value |
|---|---|
| Database type | **User-defined** |
| Provider type | **User-defined JDBC Provider** |
| Implementation type | **User-defined** |
| Name | `Vault Secret JDBC Provider` |
| Class path | `/opt/IBM/WebSphere/jdbc/vault-jdbc-1.0.2.jar` |
| Implementation class name | `com.ibm.example.vault.VaultConnectionPoolDataSource` |

Click **OK** and save to the master configuration.

Using wsadmin (Jython):

```python
clusterName = 'WASResort'
providerPath = '/ServerCluster:%s/JDBCProvider:Vault Secret JDBC Provider/' % clusterName
implementationClass = 'com.ibm.example.vault.VaultConnectionPoolDataSource'
driverJar = '/opt/IBM/WebSphere/jdbc/vault-jdbc-1.0.2.jar'
helperClass = 'com.ibm.example.vault.was.VaultDataStoreHelper'

provider = AdminConfig.getid(providerPath)
if not provider:
    provider = AdminTask.createJDBCProvider([
        '-scope', 'Cluster=' + clusterName,
        '-databaseType', 'User-defined',
        '-providerType', 'User-defined JDBC Provider',
        '-implementationType', 'User-defined',
        '-name', 'Vault Secret JDBC Provider',
        '-classpath', driverJar,
        '-implementationClassName', implementationClass
    ])

# Also repair a provider previously configured with VaultDriver.
AdminConfig.modify(provider, [
    ['implementationClassName', implementationClass],
    ['classpath', driverJar],
    ['xa', 'false']
])
AdminConfig.save()
```

Use the exact provider type `User-defined JDBC Provider`; `User-defined` alone
causes `DSRA3602E`. The implementation type is `User-defined`, not a description
such as `Java(tm) driver type 4 data source`. The scoped lookup reuses an existing
provider and updates its implementation class, classpath, and non-XA setting.
Deploy the combined JDBC JAR to every cluster node, save, synchronize nodes, and restart
the affected application servers so they load the provider and helper classes.

### 3. Create a J2C authentication alias

Navigate to **Security → Global security → Java Authentication and
Authorization Service → J2C authentication data** and click **New**.

| Field | Value |
|---|---|
| Alias | `vault` |
| User ID | Vault username (e.g. `jdbc-user`) |
| Password | Vault login password |

Use the exact alias displayed by WebSphere, including any node prefix. The
example uses `DmgrNode/vault`; replace it if your environment uses another name.
Create the alias before creating the data source. Reuse an existing alias without
changing its credentials:

```python
authAlias = 'DmgrNode/vault'
sec = AdminConfig.getid('/Security:/')
aliasExists = False
for authData in AdminConfig.list('JAASAuthData', sec).splitlines():
    if AdminConfig.showAttribute(authData, 'alias') == authAlias:
        aliasExists = True

if not aliasExists:
    AdminConfig.create('JAASAuthData', sec, [
        ['alias', authAlias],
        ['userId', 'jdbc-user'],
        ['password', '<vault-login-password>']
    ])

AdminConfig.save()
```

Supply the login password securely; do not commit a script containing it.
The component-managed alias applies when the application's resource reference
uses `res-auth=Application`. For `res-auth=Container`, configure the
container-managed authentication alias/resource-reference mapping instead. The
adapter must forward the supplied credentials as the driver's `user` and
`password` connection properties.

### 4. Create the Data Source

Navigate to **Resources → JDBC → Data Sources** and click **New**. Select the
`Vault Secret JDBC Provider` created above.

| Field | Value |
|---|---|
| Name | `Vault Secrets Data Source` |
| JNDI name | `jdbc/vaultSecrets` |
| Data store helper class name | `com.ibm.example.vault.was.VaultDataStoreHelper` (user-defined) |
| Component-managed authentication alias | *(see step 3)* |
| Container-managed authentication alias | *(see step 3)* |

Under **Connection pool properties**, set:

| Property | Value | Reason |
|---|---|---|
| Minimum connections | `0` | Allow the pool to drain completely |
| Maximum connections | `10` | Limit concurrent Vault logins |
| Connection timeout | `30` | Seconds to wait for a free connection |
| Aged timeout | Set for the Vault token lifetime | `0` disables age-based retirement; it does not discard connections after each use |
| Reap time | `30` | How often the pool reaper runs |

Pool settings require testing with your adapter. Closing a pooled application
connection can return it to the pool without closing the physical connection or
revoking its token. Account for the reaper interval and in-use connections when
choosing an aged timeout below the Vault token lifetime. See IBM's
[connection pool settings](https://www.ibm.com/docs/en/was/8.5.5?topic=applications-connection-pool-settings).

Under **Custom properties** of the data source, add:

| Property name | Example value | Description |
|---|---|---|
| `URL` | `jdbc:vault:https://vault.example.com:8200` | Vault origin |
| `authMount` | `userpass` | Auth mount path relative to `auth/` |
| `mount` | `secret` | KV secrets engine mount |
| `kvVersion` | `2` | KV engine version (`1` or `2`) |
| `secretPath` | `apps/database` | Default secret path for zero-parameter queries |
| `passwordField` | `db_password` | Default field name |
| `connectTimeoutSeconds` | `10` | TCP/TLS connection timeout |
| `requestTimeoutSeconds` | `15` | HTTP request deadline |
| `revokeOnClose` | `true` | Revoke the Vault token when the connection closes |

All Vault-specific [connection properties](#connection-properties) use
`java.lang.String`, matching the adapter's JavaBean setters, including numeric and
boolean options. The URL property is uppercase `URL`. Do not add `user` or
`password` here — supply them through a J2C authentication alias (step 3).

Set these additional WebSphere properties for the read-only adapter:

| Property | Type | Value |
|---|---|---|
| `nonTransactionalDataSource` | `java.lang.Boolean` | `true` |

This is a WebSphere setting, not a Vault JavaBean property. Disable the data
source's statement cache (`statementCacheSize=0`). The data store helper must be
`com.ibm.example.vault.was.VaultDataStoreHelper`. It returns `TRANSACTION_NONE`
for ordinary lookups and rejects explicit access-intent policies. Explicit
transactional resource-reference isolation settings remain unsupported.

`nonTransactionalDataSource=true` prevents transaction enlistment; it does not
by itself select an isolation level. Earlier instructions used
`webSphereDefaultIsolationLevel=0`, but IBM's isolation table marks that value as
IBM i-specific. Remove that custom property and use the Vault helper for Linux.
Vault reads cannot be committed or rolled back. See IBM's
[non-transactional data source configuration](https://www.ibm.com/docs/en/was-nd/9.0.5?topic=console-websphere-application-server-data-source-properties),
[isolation level settings](https://www.ibm.com/docs/SSEQTP_8.5.5/com.ibm.websphere.base.doc/ae/cdat_isolevel.html),
and [custom helper extension instructions](https://www.ibm.com/docs/en/was/9.0.5?topic=applications-developing-custom-datastorehelper-class).

Using wsadmin (Jython), create the data source **without
`-configureResourceProperties`**, then create or update its custom properties.
The command step matches properties exposed by the provider template; arbitrary
properties on a user-defined provider can cause `WASX8018E: Cannot find a match
for option value`. Use IBM's documented
[custom-property configuration](https://www.ibm.com/docs/en/was/9.0.5?topic=cdaws-configuring-new-data-source-custom-properties-using-wsadmin)
instead:

```python
provider = AdminConfig.getid(providerPath)
if not provider:
    raise ValueError('Create the JDBC provider in the selected cluster first')

ds = AdminConfig.getid(providerPath + 'DataSource:Vault Secrets Data Source/')
if not ds:
    ds = AdminTask.createDatasource(provider, [
        '-name', 'Vault Secrets Data Source',
        '-jndiName', 'jdbc/vaultSecrets',
        '-dataStoreHelperClassName',
            helperClass,
        '-componentManagedAuthenticationAlias', authAlias,
        '-containerManagedPersistence', 'false'
    ])

AdminConfig.modify(ds, [
    ['dataStoreHelperClassName', helperClass],
    ['statementCacheSize', '0']
])

# The servlet's @Resource uses container-managed authentication.
# A component-managed authentication alias alone does not cover this lookup.
mappingAttrs = [
    ['mappingConfigAlias', 'DefaultPrincipalMapping'],
    ['authDataAlias', authAlias]
]
mapping = AdminConfig.showAttribute(ds, 'mapping')
if mapping:
    AdminConfig.modify(mapping, mappingAttrs)
else:
    mapping = AdminConfig.create('MappingModule', ds, mappingAttrs)

propSet = AdminConfig.showAttribute(ds, 'propertySet')
if not propSet:
    propSet = AdminConfig.create('J2EEResourcePropertySet', ds, [])

properties = [
    ['URL', 'java.lang.String', 'jdbc:vault:https://vault.example.com:8200'],
    ['authMount', 'java.lang.String', 'userpass'],
    ['mount', 'java.lang.String', 'secret'],
    ['kvVersion', 'java.lang.String', '2'],
    ['secretPath', 'java.lang.String', 'apps/database'],
    ['passwordField', 'java.lang.String', 'db_password'],
    ['connectTimeoutSeconds', 'java.lang.String', '10'],
    ['requestTimeoutSeconds', 'java.lang.String', '15'],
    ['revokeOnClose', 'java.lang.String', 'true'],
    ['nonTransactionalDataSource', 'java.lang.Boolean', 'true']
]

existing = {}
for prop in AdminConfig.list('J2EEResourceProperty', propSet).splitlines():
    name = AdminConfig.showAttribute(prop, 'name')
    if name == 'webSphereDefaultIsolationLevel':
        AdminConfig.remove(prop)
    else:
        existing[name] = prop

for name, propType, value in properties:
    attrs = [['type', propType], ['value', value], ['ignore', 'false']]
    if name in existing:
        AdminConfig.modify(existing[name], attrs)
    else:
        AdminConfig.create('J2EEResourceProperty', propSet,
                           [['name', name]] + attrs)

AdminConfig.save()
```

This reuses an existing data source in the selected cluster and updates matching
properties without duplicating them, disables the statement cache, and configures
the data source's container-managed authentication mapping to `authAlias`
(`DmgrNode/vault` in this example) with `DefaultPrincipalMapping`. It does not
change the credentials stored in that existing alias. If the application has
an explicit resource-reference authentication binding, check that binding as
well. The creation command disables EJB
container-managed persistence because this driver does not support it; this
setting is separate from container-managed authentication.

Copy values as plain text: use `jdbc:vault:https://vault.example.com:8200`, not
Markdown `[label](URL)` syntax, and `db_password`, not `db\_password`. HTTPS is
required for remote Vault hosts. `allowHttp=true` permits only loopback development
URLs; it does not permit `http://192.168.1.23:8200`.

After saving, synchronize the nodes. If you changed the JDBC JAR, the provider
classpath, the provider implementation class, or the data store helper class,
restart the affected application servers before testing.

### 5. Test the connection

After deploying the rebuilt driver JAR, open the data source in the admin console
and click **Test connection** to check connectivity. A successful connection test
does not prove that the configured secret path or field can be read. Run the
query in step 6 to verify secret access. Creating configuration objects alone
does not validate the adapter, property setters, or credentials.

If the servlet fails with `Unsupported JDBC operation: getTypeMap` or logs
`DSRA1300E` for `Connection.getClientInfo`, deploy a JAR rebuilt from the current
sources on every cluster node and restart the affected servers. Earlier builds
with the same `1.0.2` filename lacked these connection initialization methods.
Changing the authentication alias will not add missing JDBC methods. An FFDC
`NullPointerException` in `getTransactionStateAsString` / `introspectSelf` can be
a secondary diagnostic failure; inspect the preceding servlet/JDBC error first.

If allocation fails in `synchronizePropertiesWithCRI` with
`Unsupported JDBC operation: setTransactionIsolation`, WebSphere requested a
transactional isolation level. Check that the configured helper is
`com.ibm.example.vault.was.VaultDataStoreHelper`, the combined JDBC JAR contains
that class on each node, and the servers have restarted. Also remove explicit
transactional isolation/access-intent requirements from the application's
resource reference for this source. The driver's transaction checks remain
strict; it does not claim to support repeatable reads or serializable execution.

### 6. Use the data source in your application

Application code looks up the data source by JNDI name. The call is identical
to the Liberty example:

```java
import javax.naming.InitialContext;
import javax.sql.DataSource;
import java.sql.*;

DataSource vault = (DataSource) new InitialContext().lookup("jdbc/vaultSecrets");
try (Connection connection = vault.getConnection();
     Statement query = connection.createStatement();
     ResultSet result = query.executeQuery("SELECT password FROM vault_secret")) {
    if (result.next()) {
        String retrievedPassword = result.getString("password");
        // Pass the password to the component that needs it. Do not log it.
    }
}
```

### TLS trust for tWAS

For a private Vault CA, import the certificate into the tWAS trust store. In the
admin console navigate to **Security → SSL certificate and key management →
Key stores and certificates**, open your server's trust store (typically
`NodeDefaultTrustStore`), and click **Signer certificates → Retrieve from
port** or **Add** to import the CA PEM file directly.

---

## Servlet example

[`examples/VaultSecretServlet.java`](examples/VaultSecretServlet.java) is a
complete `HttpServlet` that retrieves a secret from Vault on each `GET` request
via the JNDI-injected `jdbc/vaultSecrets` data source.

**Endpoint:** `GET /vault-example/secret`

| Query parameter | Effect |
|---|---|
| *(none)* | Uses `secretPath` and `passwordField` from `server.xml` |
| `?path=apps/database` | Overrides the secret path for this request |
| `?field=db_password` | Overrides the field name for this request |
| `?path=apps/database&field=db_password` | Overrides both |

The servlet responds with `200 OK` and a confirmation message on success. The
secret value is **never** written to the response body or any log. HTTP error
codes are mapped from JDBC SQL states:

| HTTP status | Cause |
|---|---|
| `401 Unauthorized` | Vault rejected the credentials (`28xxx`) |
| `404 Not Found` | Secret path or field does not exist (`02000`) |
| `503 Service Unavailable` | Cannot reach Vault (`08xxx`) |
| `500 Internal Server Error` | Any other failure |

**To deploy:**

1. Package the servlet class into a WAR and place it at
   `${server.config.dir}/apps/vault-example.war`.
2. Ensure `server.xml` includes the `servlet-4.0` feature and the
   `<webApplication>` element from `examples/server.xml`.
3. The `<classloader commonLibraryRef="vaultJdbcLibrary"/>` element makes the
   vault-jdbc driver and JSON4J visible to the WAR's classloader.

---

## Retrieve a password

For a KV v2 secret at mount `secret`, path `apps/database`, with a string field
named `db_password`:

```java
import java.sql.*;
import java.util.Properties;

Properties properties = new Properties();
properties.setProperty("user", System.getenv("VAULT_USER"));
properties.setProperty("password", System.getenv("VAULT_PASSWORD"));
properties.setProperty("mount", "secret");

try (Connection connection = DriverManager.getConnection(
        "jdbc:vault:https://vault.example.com:8200", properties);
     PreparedStatement query = connection.prepareStatement(
        "SELECT password FROM vault_secret WHERE path = ? AND field = ?")) {
    properties.remove("password");
    query.setString(1, "apps/database");
    query.setString(2, "db_password");

    try (ResultSet result = query.executeQuery()) {
        if (result.next()) {
            String retrievedPassword = result.getString("password");
            // Pass retrievedPassword to the component that needs it. Do not log it.
        }
    }
}
```

The login password authenticates the Vault user; the returned value is the
separate secret stored at the requested path. The result column is always named
`password`, regardless of the Vault field name.

The URL contains only the Vault origin: `jdbc:vault:https://host[:port]`. Pass
credentials and options through `Properties`. URL userinfo, query strings,
fragments, and base paths are rejected. With all defaults,
`DriverManager.getConnection(url, userId, loginPassword)` also works.

### Supported queries

```sql
SELECT password FROM vault_secret WHERE path = ? AND field = ?
SELECT password FROM vault_secret WHERE path = ?
SELECT password FROM vault_secret
```

- The first query binds the relative secret path and literal field name with
  `setString` or `setObject(index, String)`.
- The second uses the `passwordField` property, defaulting to `password`.
- The third requires a `secretPath` property and uses `passwordField`. It also
  works through `Statement.executeQuery`, which helps applications that cannot
  bind parameters.
- SQL keywords and the result column label are case-insensitive. Vault paths and
  field names retain their original case. Fields containing periods are literal
  keys, not JSON paths.
- One optional trailing semicolon is accepted. SQL literals, aliases, comments,
  joins, multiple statements, and other predicates are unsupported.
- Each execution reads from Vault again, so the next execution sees rotation
  unless `secretVersion` pins an older version. Results contain one non-null
  string. A missing secret, deleted version, missing field, or null field raises
  `SQLException`; non-string fields raise `SQLDataException`. An empty string is
  returned unchanged.

Do not include the KV mount or the KV v2 `data/` API segment in `path`. For
mount `secret` and logical path `apps/database`, the driver constructs the
correct API endpoint. Use raw path text, not pre-encoded URL text. Leading/
trailing slashes, empty segments, and dot traversal are rejected.

---

## Connection properties

| Property | Default | Meaning |
|---|---|---|
| `user` | Required | Vault user ID |
| `password` | Required | Vault login password |
| `authMount` | `userpass` | Authentication mount relative to `auth/`; supports custom userpass mounts |
| `mount` | `secret` | KV secrets engine mount |
| `kvVersion` | `2` | `1` or `2` |
| `namespace` | Empty | Optional `X-Vault-Namespace` header |
| `secretPath` | Unset | Default relative path for the zero-parameter query |
| `passwordField` | `password` | Default field name |
| `secretVersion` | Unset | Positive KV v2 version; omitted means latest; rejected with KV v1 |
| `connectTimeoutSeconds` | `10` | TCP/TLS connection timeout |
| `requestTimeoutSeconds` | `15` | HTTP read timeout and request deadline |
| `allowHttp` | `false` | Permit cleartext HTTP only for `localhost`, `127.0.0.1`, or `[::1]` during development |
| `revokeOnClose` | `true` | Revoke the token obtained by this connection when it closes |

`Statement.setQueryTimeout(seconds)` overrides the request timeout for that
statement's secret reads. Zero uses the configured request timeout. A positive
`DriverManager.setLoginTimeout` caps the login request and connection timeouts.
The driver disconnects expired requests and checks the deadline while reading
response data. Java 8 URLConnection cannot guarantee a strict wall-clock bound
for every JVM/platform DNS or socket operation.

The configurable authentication mount also accommodates the same
username/password login shape used by Vault LDAP, but the real-server
integration suite exercises userpass. Interactive MFA challenges are unsupported.

---

## Vault access and token lifecycle

A Vault administrator must enable/configure userpass, create the user, and grant
its token access to the chosen secret. See the [Vault setup](#vault-setup)
section for step-by-step instructions. An example minimal policy for KV v2:

```hcl
path "secret/data/apps/database" {
  capabilities = ["read"]
}
```

For KV v1 the policy path is `secret/apps/database`. Reading the v2 data
endpoint does not require permission to enumerate secret metadata.

The connection authenticates immediately and keeps the resulting token in memory.
It does not keep a reference to the supplied login password after `connect`
returns. Close each result, statement, and connection using try-with-resources.
Closing a standalone connection closes its statements/results and, by default,
revokes its token. With `VaultConnectionPoolDataSource`, closing a logical
connection closes its statements/results and notifies the server's pool; the
physical Vault session remains available for reuse. Its token is revoked when the
pool closes the `PooledConnection`, unless `revokeOnClose=false`. Credentials passed
to `getPooledConnection(user, password)` are used for login and are not retained by
the factory. Closing a result drops the driver's reference to its secret string; Java
strings and caller-held values cannot be reliably zeroed.

`Connection.isValid(timeout)` calls `auth/token/lookup-self`. Token validation
and revocation require `read` on `auth/token/lookup-self` and `update` on
`auth/token/revoke-self`, respectively; these are normally available through
Vault's default policy. The example policy includes both explicitly for
deployments that omit the default policy. Each API call consumes a use when
Vault issues a use-limited token.

If revocation fails, `close()` still closes all local resources, drops the token
reference, and reports the failure as a sanitized `SQLException`. The server
token may remain valid until expiry. For batch tokens, or when token revocation
is intentionally unavailable, set `revokeOnClose=false` and configure a suitably
short token TTL in Vault.

There is no background renewal or automatic reauthentication. Open a new
connection after the token expires. Use each connection and its statements
sequentially. The adapter reports connection-error events for network, timeout,
and authorization failures so the pool can retire the affected physical session.
Full application-server integration has not been certified.

---

## TLS and operational behavior

HTTPS uses the JVM truststore and standard certificate/hostname checks. For a
private Vault CA, configure a JVM truststore containing that CA using standard
Java options:

```sh
# Import the Vault CA certificate into a truststore.
keytool -importcert \
    -alias vault-ca \
    -file /path/to/vault-ca.crt \
    -keystore /path/to/truststore.p12 \
    -storetype PKCS12 \
    -storepass changeit

# Point the JVM at the truststore when starting your application.
java -Djavax.net.ssl.trustStore=/path/to/truststore.p12 \
     -Djavax.net.ssl.trustStorePassword=changeit \
     -jar your-application.jar
```

For Liberty, configure the truststore in `server.xml` using the
[`<ssl>`](https://openliberty.io/docs/latest/reference/config/ssl.html) and
[`<keyStore>`](https://openliberty.io/docs/latest/reference/config/keyStore.html)
elements instead of JVM system properties. For tWAS, see
[TLS trust for tWAS](#tls-trust-for-twas) in the WebSphere Application Server
configuration section.

There is no option to disable TLS verification.

HTTP redirects are rejected. Point the driver at a stable Vault address or a
load balancer that supports Vault request forwarding. Reverse-proxy URL prefixes
are not supported. Credentials, tokens, and secret response bodies are never
logged by this driver and are excluded from exception messages, `toString`, and
password property introspection. Avoid HTTP wire logging and application-level
logging of the properties or result value.

| SQL state | Meaning |
|---|---|
| `28000` | Login rejected (including HTTP 400), token expired, or policy denied access (HTTP 401/403) |
| `02000` | Secret/version not found, or requested field missing/null |
| `22005` | Secret field or parameter is not a string |
| `HYT00` | HTTP request timed out |
| `08001` | Network/TLS problem or transient server failure |
| `HY024` | Invalid connection option or path |
| `42000` | Unsupported query syntax |
| `07001` / `07009` | Unbound parameters / invalid parameter or column index |
| `0A000` | Unsupported JDBC operation |

Responses are limited to 1 MiB before parsing. Invalid UTF-8 and JSON4J parse
failures produce sanitized SQL exceptions; JSON syntax handling otherwise follows
JSON4J.

Server response bodies and low-level exception causes are deliberately omitted
because they may contain secrets. HTTP failures retain their status code in
`SQLException.getErrorCode()`. The driver does not implement a retry loop;
callers decide whether to reconnect or retry after examining the error. The
underlying JDK URLConnection implementation may retry a failed idempotent HTTP
exchange.

---

## Runnable example

For standalone execution outside the application server, supply a JSON4J JAR
explicitly. Set the non-secret configuration and run the supplied example:

```sh
export VAULT_JDBC_URL='jdbc:vault:https://vault.example.com:8200'
export VAULT_USER='my-vault-user'
export VAULT_SECRET_PATH='apps/database'
export VAULT_PASSWORD_FIELD='db_password'
export JSON4J_JAR='/path/to/com.ibm.json4j-1.0.112.RELEASE.jar'
mkdir -p target/example-classes
javac -encoding UTF-8 -cp target/vault-jdbc-1.0.2.jar \
    -d target/example-classes examples/RetrievePassword.java
java -cp "target/vault-jdbc-1.0.2.jar:$JSON4J_JAR:target/example-classes" \
    RetrievePassword
```

On Windows, use `;` in place of `:` between classpath entries and the
appropriate environment variable syntax for your shell. In tWAS or Liberty, use
the server-provided JSON4J dependency.

The example prompts for the login password in an interactive terminal, or reads
`VAULT_PASSWORD` if already supplied by your execution environment. It reports
successful retrieval without printing the returned secret. Optional environment
variables are `VAULT_AUTH_MOUNT`, `VAULT_MOUNT`, `VAULT_KV_VERSION`,
`VAULT_NAMESPACE`, and `VAULT_ALLOW_HTTP`. These environment variables configure
the example; the driver itself reads JDBC properties.

---

## Tests

```sh
# Mock HTTP server tests, including errors, timeouts, redaction, and JDBC lifecycle
mvn test

# Also launch and stop a disposable loopback Vault dev server with generated credentials
VAULT_INTEGRATION=true mvn package

# After packaging, verify JDBC discovery, the example, and TLS with provided JSON4J
VAULT_INTEGRATION=true mvn test -Dtest=VaultIntegrationTest,VaultTlsTest \
  -DdriverJar=target/vault-jdbc-1.0.2.jar
```

Maven includes the provided JSON4J dependency on the test classpath. Packaged-
driver tests use that dependency for their standalone child processes. The test
suite also verifies TLS with a temporary test certificate using the JDK's
`keytool`; trusted certificates succeed, while untrusted certificates and
incorrect hostnames are rejected.

The integration suite requires the `vault` executable on `PATH` (or set
`VAULT_BINARY`). It uses random local ports, an in-memory dev instance, and
generated fixture credentials. It never contacts an existing Vault server. Tests
require permission to open loopback sockets. The disposable server log is written
to `target/vault-integration.log`; it contains development-only credentials and
should not be published. Tests cover userpass login, KV v1/v2, custom fields,
historical versions, policy denial, and loading the packaged driver with JSON4J.
Live enterprise namespaces, LDAP, and your deployment's certificate chain are not
covered by the local integration suite.

---

## Driver architecture

The driver is entirely self-contained inside `com.ibm.example.vault`. There are
no Python scripts included; all configuration and migration is done through
Jython (`wsadmin`) or Java APIs as documented above.

| Class | Package | Role |
|---|---|---|
| `VaultDriver` | `com.ibm.example.vault` | Implements `java.sql.Driver`. Registered automatically via the `META-INF/services/java.sql.Driver` SPI so that `DriverManager` discovers it from the classpath. Accepts URLs starting with `jdbc:vault:`, parses configuration, and delegates to `VaultClient.login` to produce a `Connection`. Reports `jdbcCompliant() == false`. |
| `VaultConfig` | `com.ibm.example.vault` | Immutable, validated value object parsed from the JDBC URL and `Properties`. Holds the Vault base URL, auth mount, KV mount, engine version, namespace, default secret path, field name, optional pinned version, and timeout settings. Credentials are excluded so the object is safe to log. Created only via `VaultConfig.parse`. |
| `VaultClient` | `com.ibm.example.vault` | HTTP(S) transport layer. Uses `HttpURLConnection` (no `sun.*` classes) with a per-request wall-clock deadline enforced by a single daemon-thread scheduler (`vault-jdbc-timeout`). Provides `login` (userpass POST), `read` (KV GET with v1/v2 envelope unwrapping), `isValid` (token self-lookup), and `close` (optional token revocation + scheduler shutdown). Response bodies are capped at 1 MiB and validated as strict UTF-8 before JSON parsing. |
| `VaultConnectionPoolDataSource` | `com.ibm.example.vault` | Implements `javax.sql.ConnectionPoolDataSource` for application-server-managed pools (traditional WebSphere). Exposes all driver options as String JavaBean setters so the server can configure them through the admin console. Creates `VaultPooledConnection` instances on demand; the server owns the pool. Configured as a non-transactional data source. |
| `VaultPooledConnection` | `com.ibm.example.vault` | Implements `javax.sql.PooledConnection`. Wraps a single authenticated `VaultClient` session. Manages logical connection handles (`JdbcObjects.Conn`), fires `ConnectionEvent` and `StatementEvent` notifications, and marks the session as failed on fatal errors so the pool can retire it. |
| `JdbcObjects` | `com.ibm.example.vault` | Factory for all JDBC proxy objects (`Connection`, `Statement`, `PreparedStatement`, `ResultSet`, `DatabaseMetaData`, `ResultSetMetaData`). Each object is a JDK dynamic proxy; unimplemented optional JDBC methods throw `SQLFeatureNotSupportedException` rather than silently returning fake values. All proxy method dispatch is synchronised on the handler instance. |
| `SecretQuery` | `com.ibm.example.vault` | Parses and validates the single SQL grammar the driver accepts (`SELECT password FROM vault_secret [WHERE path = ? [AND field = ?]]`). Parameters are positional placeholders supplied via `PreparedStatement.setString`; they are never interpolated into the SQL string. Any other SQL raises `SQLSyntaxErrorException`. |
| `VaultDataStoreHelper` | `com.ibm.example.vault.was` | Extends WebSphere's `GenericDataStoreHelper`. Overrides `getIsolationLevel` to return `TRANSACTION_NONE` for ordinary lookups and to reject explicit access-intent transaction policies, preventing the `DSRA8101E: setTransactionIsolation` error that occurs with the default helper. Compiled into the driver JAR from `src/main/java`; no separate helper JAR is needed. |
| `VaultSecretServlet` | `com.ibm.example.vault.web` | Example `HttpServlet` packaged into `vault-jdbc-1.0.2.war`. Receives a JNDI-injected `jdbc/vaultSecrets` data source, executes a parameterised `SELECT password FROM vault_secret` query, passes the result to a downstream connection, and discards it without writing it to any log or response body. Deployed as a separate WAR so the web classloader never owns the driver classes. |

---

## Relationship to the supplied Vault example

The HTTP flow follows
[HashiCorpVault.java in WASHashiCorpVaultPassword](https://github.com/bpaskin/WASLibertyScriptsAndStuff/blob/master/WASHashiCorpVaultPassword/src/com/ibm/example/security/HashiCorpVault.java):
log in through `HttpsURLConnection`, obtain `auth.client_token`, send it in
`X-Vault-Token`, and extract the requested KV v2 field from `data.data`. The
supplied example uses AppRole; this driver keeps the username/password `userpass`
authentication selected for this task. It uses the public URL connection API
without implementation-specific `sun.*` classes.

To read the same storage layout as that example, set `mount=secret`, bind `path`
to `WAScreds`, and bind `field` to the password's key (for example
`mydbpassword`).

---

## API references

- [Vault userpass login API](https://developer.hashicorp.com/vault/api-docs/auth/userpass#login)
- [Vault KV v2 read API](https://developer.hashicorp.com/vault/api-docs/secret/kv/kv-v2#read-secret-version)
- [Vault KV v1 read API](https://developer.hashicorp.com/vault/api-docs/secret/kv/kv-v1#read-secret)
- [Vault policy documentation](https://developer.hashicorp.com/vault/docs/concepts/policies)
- [Vault userpass auth method](https://developer.hashicorp.com/vault/docs/auth/userpass)
- [Java HttpsURLConnection API](https://docs.oracle.com/javase/8/docs/api/javax/net/ssl/HttpsURLConnection.html)
- [Java JDBC Driver interface](https://docs.oracle.com/javase/8/docs/api/java/sql/Driver.html)
- [Liberty JDBC configuration](https://openliberty.io/docs/latest/relational-database-connections-JDBC.html)
- [Liberty json-1.0 feature](https://openliberty.io/docs/latest/reference/feature/json-1.0.html)
- [Liberty servlet-4.0 feature](https://openliberty.io/docs/latest/reference/feature/servlet-4.0.html)
- [tWAS JDBC provider configuration](https://www.ibm.com/docs/en/was/9.0.5?topic=connections-configuring-jdbc-providers)
- [tWAS data source configuration](https://www.ibm.com/docs/en/was/9.0.5?topic=connections-configuring-data-sources)
- [tWAS J2C authentication aliases](https://www.ibm.com/docs/en/was/9.0.5?topic=security-java-authentication-authorization-service-j2c-authentication-data)
