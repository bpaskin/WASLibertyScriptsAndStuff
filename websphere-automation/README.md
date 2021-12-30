# WebSphere Automation Installation and Setup #

Steps and scripts to set the WebSphere Automation and an OpenShift Cluster.  This includes installing the Operator and creating the necessary instances for WebSphere Automation (Automation base, Security, Health), hooking tradition WebSphere and Liberty to WebSphere Automation.  It will not include hooking in Instana for Health monitoring.  More information is available in the [Knowledge Center](https://www.ibm.com/docs/en/ws-automation).

Prerequistes:
Must have the oc command line utility installed and must be logged into the targeted cluster.

This works with the following versions of traditional WebSphere and Liberty:
- WebSphere Application Server (all editions) 8.5.5.15 and later
- WebSphere Application Server (all editions) 9.0.0.9 and later
- WebSphere Application Server Liberty (all editions) 18.0.0.3 and later

Storage class RWM must be default storage class.  More information in the [Knowledge Center](https://www.ibm.com/docs/en/ws-automation?topic=requirements-storage#in-r-sysreqs-storage).
To change the storage class the following can be done:
`oc patch storageclass ibmc-file-gold-gid  -p '{"metadata": {"annotations":{"storageclass.kubernetes.io/is-default-class":"true"}}}'`
`oc patch storageclass ibmc-block-gold -p '{"metadata": {"annotations":{"storageclass.kubernetes.io/is-default-class":"false"}}}'`

The WebSphere Automation will be installed in a new namespace called `websphere-automation`

Install the Operator:
`oc apply -f websphere-automation-operator.yaml`

Wait until all the operators are installed.

Next apply the [IBM Entitlement Key](https://myibm.ibm.com/products-services/containerlibrary) to the Secrets of the `websphere-automation` namespace.  Replace the `ENTITLEMENT-KEY` with the entitlment key from the entitlement key website.
`oc create secret docker-registry ibm-entitlement-key --namespace=websphere-automation --docker-server=cp.icr.io --docker-username=cp --docker-password=ENTITLEMENT-KEY`

Install the instances of WebSphere Automation
`oc apply -f websphere-automation-instance.yaml`

It may take awahile for everything to be installed.

