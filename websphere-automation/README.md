# WebSphere Automation Installation and Setup #

Steps and scripts to set the WebSphere Automation and an OpenShift Cluster.  This includes installing the Operator and creating the necessary instances for WebSphere Automation (Automation base, Security, Health), hooking tradition WebSphere and Liberty to WebSphere Automation.  It will not include hooking in Instana for Health monitoring.  More information is available in the [Knowledge Center](https://www.ibm.com/docs/en/ws-automation).

---

Prerequisites:</br>
Must have the oc command line utility installed and must be logged into the targeted cluster.

This works with the following versions of traditional WebSphere and Liberty:
- WebSphere Application Server (all editions) 8.5.5.15 and later
- WebSphere Application Server (all editions) 9.0.0.9 and later
- WebSphere Application Server Liberty (all editions) 18.0.0.3 and later

Storage class RWM must be default storage class.  More information in the [Knowledge Center](https://www.ibm.com/docs/en/ws-automation?topic=requirements-storage#in-r-sysreqs-storage).
To change the storage class the following can be done:</br>
Make default: `oc patch storageclass ibmc-file-gold-gid  -p '{"metadata": {"annotations":{"storageclass.kubernetes.io/is-default-class":"true"}}}'`</br>
Remove default: `oc patch storageclass ibmc-block-gold -p '{"metadata": {"annotations":{"storageclass.kubernetes.io/is-default-class":"false"}}}'`</br>

---

The WebSphere Automation will be installed in a new namespace called `websphere-automation`

Install the Operator:</br>
`oc apply -f websphere-automation-operator.yaml`</br>

Wait until all the operators are installed.

---

Next apply the [IBM Entitlement Key](https://myibm.ibm.com/products-services/containerlibrary) to the Secrets of the `websphere-automation` namespace.  Replace the `ENTITLEMENT-KEY` with the entitlment key from the entitlement key website. </br>
`oc create secret docker-registry ibm-entitlement-key --namespace=websphere-automation --docker-server=cp.icr.io --docker-username=cp --docker-password=ENTITLEMENT-KEY`

The Fix Central userid and password must be saved in a Secret if fixed are to be prepared from WSA
```
oc create secret generic wsa-secure-fixcentral-creds --from-literal=user=<fixcentral_id> --from-literal=password=<fixcentral_pw>
```

Install the instances of WebSphere Automation
`oc apply -f websphere-automation-instance.yaml`

It may take awahile for everything to be installed.

---

Configure traditional WebSphere Application Server

If using a Deployment Manager then the following should be done on the DMGR node.  Otherwise, it goes on the App Server node.  In addition, this assumes that the server is using the default configuration for the SSL Configuration.  This can be changed by pointing the last line to the proper SSL Reference.

```
echo "url=https://`oc get route cpd -n websphere-automation -o jsonpath='{.spec.host}'/websphereauto/meteringapi`" > was-usage-metering.properties
echo "apiKey=`oc -n websphere-automation get secret wsa-secure-metering-apis-encrypted-tokens -o jsonpath='{.data.wsa-secure-metering-apis-sa}' | base64 -d`" >> was-usage-metering.properties
echo "sslRef=CellDefaultSSLSettings" >> was-usage-metering.properties
```

This file should be copied to the `profile_home/config/cells/cell_name` directory

Download the certificate:
```
echo "`oc get secret wsa-default-external-tls-secret -n websphere-automation -o jsonpath='{.data.cert\.crt}' | base64 -d`" > cert.pem
````

Place it in the truststore:
```
keytool -import -trustcacerts -file cert.pem -keystore PROFILE_NAME/config/cells/CELL_NAME/trust.p12 -storetype PKCS12 -storepass WebAS -noprompt
```

Syncrhonize the nodes and check the `SystemOut.log` of the servers to make sure it is connecting to the metering endpoint.

---

Configure Liberty Server

The Liberty `bootstrap.properties` file needs to be updated with the necessary information.

```
echo "URL=https://`oc get route cpd -n websphere-automation -o jsonpath='{.spec.host}'/websphereauto/meteringapi`" >> bootstrap.properties
echo "APIKEY=`oc -n websphere-automation get secret wsa-secure-metering-apis-encrypted-tokens -o jsonpath='{.data.wsa-secure-metering-apis-sa}' | base64 -d`" >> bootstrap.properties
echo "SSLREF=defaultSSLConfig" >> bootstrap.properties
```

update the `server.xml`:

```
<featureManager>
   <feature>usageMetering-1.0</feature>
</featureManager>

   <usageMetering url="${URL}" apiKey="${APIKEY}" sslRef="${SSLREF}"/>
```

Download the certificate to be trusted:
```
echo "`oc get secret wsa-default-external-tls-secret -n websphere-automation -o jsonpath='{.data.cert\.crt}' | base64 -d`" > cert.pem
````

Add the certificate to the truststore of the server, example:
```
keytool -import -trustcacerts -file cert.pem -keystore SERVER_NAME/resources/security/trust.p12 -storetype PKCS12 -storepass WebAS -noprompt
```

Check the logs to make sure it is communicating with the metering endpoint.
