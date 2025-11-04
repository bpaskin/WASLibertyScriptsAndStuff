### Liberty custom password interaction with HashiCorp Vault

This is a feature that would replace the need to put passwords in the Liberty XML files and instead allow a lookup within HashiCorp Vault.

----

In Vault the Secrets Engine must be called secret and have secrets called WAScreds. All secrets should be kept in this path. ACL privledges must be given to read from secret/data/WAScreds

The public key certificate from Vault should be imported into the proper truststore in Liberty.

Each Application Server that is going to use this code must have these bootstrap.properties file:
```
com.ibm.hashiURL = https://<hostname>:<port>/v1
com.ibm.hashiRoleId = Vault Role ID
com.ibm.hashiSecretId = Vault Secret ID for Role ID
```

The user feature must be enabled in Liberty in the `server.xml` file:
```
<feature>usr:HashiCorpVault-1.0</feature>
```

This uses Liberty's [custom password encryption](https://www.ibm.com/docs/en/was-liberty/nd?topic=infrastructure-developing-custompasswordencryption-provider), so passwords should have the `{custom}passwordKey` in them.  The passwordKey will be used in the lookup to Vault.

Example in `server.xml`:
```
<authData id="myAuthAlias" user="root" password="{custom}test"/>
```

---

### Building and place the files in the correct location

1. set the home location of Liberty:
```
export wlp_home=/path/to/wlp
```
2. Build the classpath
```
export CLASSPATH=`find $wlp_home/lib -name "com.ibm.ws.crypto.passwordutil*.jar"`
export CLASSPATH=$CLASSPATH:`find $wlp_home/lib -name "com.ibm.json4j_*.jar"`
export CLASSPATH=$CLASSPATH:`find $wlp_home/dev/spi/spec -name "com.ibm.wsspi.org.osgi.service.component.annotations_*.jar"`
export CLASSPATH=$CLASSPATH:`find $wlp_home/dev/spi/spec -name "com.ibm.wsspi.org.osgi.service.component_*.jar"`
```
3. Compile the code
```
Java 9+
javac -cp $CLASSPATH --add-exports java.base/sun.net.www.protocol.https=ALL-UNNAMED com/ibm/example/security/HashiCorpVault.java

Java 8
javac -cp $CLASSPATH com/ibm/example/security/HashiCorpVault.java
```
4. Package the manfest file first
```
jar cfm  HashiCorpVault-1.0.jar META-INF/MANIFEST.MF
```
5. Add the classes and OSGi Info
```
jar uvf HashiCorpVault-1.0.jar com OSGI-INF
```
6. create the necessary extensions directories, this depends where your `usr` directory sits
```
mkdir -p $wlp_home/usr/extension/lib/features
```
7. Copy over the feature manifest:
```
cp features/HsahiCorpVault.mf $wlp_home/usr/extension/lib/features
```
8. Copy over the feature:
```
cp HashiCorpVault-1.0.jar $wlp_home/usr/extension/lib
```
