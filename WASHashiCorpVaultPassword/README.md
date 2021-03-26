This code allows traditional WebSphere Application Server to use [HashiCorp Vault](https://www.vaultproject.io/) to store passwords in a secure manner.

In Vault the Secrets Engine must be called `secret` and have secrets called `WAScreds`.  All secrets should be kept in this path.  ACL privledges must be given to read from `secret/data/WAScreds`.

In tWAS, the code should be placed in a jar and placed in the `<was_home>/lib/ext directory`.  The name of the jar is not important.

The public key certificate from Vault should be imported into the proper truststore in tWAS, normally the celldefaulttruststore.

Each Application that is going to use this code must have these `JVM custom properties`:

```
com.ibm.wsspi.security.crypto.customPasswordEncryptionClass = com.ibm.example.security.HashiCorpVault
com.ibm.wsspi.security.crypto.customPasswordEncryptionEnabled = true
com.ibm.hashiURL = https://<hostname>:<port>/v1
com.ibm.hashiRoleId = Vault Role ID
com.ibm.hashiSecretId = Vault Secret ID for Role ID
```

Any password in the system that is surrounded by brackets [] will go to Vault to lookup the name user `/secret/WAScreds/<name>` and return the password, if found. This can be used in J2C Authentication Alias. 
