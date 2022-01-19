# Installation of Transformation Advisor v3 on OCP #


### Please Note ###
If there is an existing v2.x version on the OCP Cluster, then it must be removed and the following [steps](https://www.ibm.com/docs/en/cta?topic=SS5Q6W/gettingStarted/movefrom25to30.html) must be completed before installing of v3.x.  Data migration can be done by following these [steps](https://www.ibm.com/docs/en/SS5Q6W/gettingStarted/dataMigration.html).

---

Please download and install the [OpenShift Command Line tool](https://docs.openshift.com/container-platform/4.9/cli_reference/openshift_cli/getting-started-cli.html) for the target environment. 

The yaml files will create a namespace/project called `trans-advisor`, create an `OperatorGroup`, create the necessary role and cluster role permissions, create the necessary links to the necessary catalogs, and install the operator.

---

Step I - create the namespace/project, roles, operator group, and catalogs </br>
`oc apply -f taoperator3_setup.yaml`

Step II - create the entitlement key </br>
Get the entitlement key from the [IBM Software Container Library](https://myibm.ibm.com/products-services/containerlibrary). </br>
`oc create secret docker-registry ibm-entitlement-key --docker-server=cp.icr.io --docker-username=cp --docker-password=<ENTITLEMENT KEY> -n trans-advisor`

Step III - Add the policy with the `trans-advisor` namespace for the operator </br>
`oc adm policy add-scc-to-user transadv-scc system:serviceaccount:trans-advisor:ta-operator`

Step IV - Install the operator </br>
`oc apply -f taoperator3_install.yaml`

Step V - Create an instance of the Transformation Advisor
