# Using non Java based MQ Security Exits with OpenLiberty/Liberty

[IBM MQ](https://www.ibm.com/products/mq) supports Channel security exits, plug points, that expand the security capabilities.  The exits can be written using a [supported language](https://www.ibm.com/docs/en/ibm-mq/9.2?topic=reference-channel-exit-calls-data-structures) on a supported platform.  OpenLiberty/Liberty runs on Java and can execute security exits that are written in Java without any further installation of software.  However, if another language is needed, then the MQ Client Runtime is required.

I will be using Linux as an example and I have successfully used this technique in a container. The MQ Clients (MQC) can be downloaded from [IBM Fix Central](https://www.ibm.com/support/fixcentral/).  For the Product select `IBM MQ`, version `9.2.3` (at the time of the writing), and desired platform.  On the next page just browse for fixes.  The MQC is named similiar to `9.2.3.0-IBM-MQC-<platform>.tar.gz`.  Download that file.

After downloading, gunzip and untarball the file.  For Linux it will be either in rpm or dep format.  Someone who has `root` privledges will need to install the packages.  First accept the license file by running the `mqlicense.sh` file.  The order to install (`dpkg -i` or `rpm -ivh`) is:
* ibmmq-runtime
* ibmmq-gskit
* ibmmq-client
* ibmmq-java

This will install the MQ Client under `/opt/mqm` and `/var/mqm` on Linux.  Place the security exit under `/var/mqm/exits` or `/var/mqm/exits64` depending on how exit was compiled.

To setup OpenLiberty/Liberty, a shared object is necessary.  To do this update the `jvm.options` file and add </br>
`-Djava.library.path=/opt/mqm/java/lib64`

To configure the secuity exit in the ConnectionFactory or ActivationSpec stanza in the `server.xml`, it will be similar to </br>
```<properties.mq channel="${MQ.CHANNEL}" destinationRef="IN.Q" destinationType="javax.jms.Queue" hostName="${MQ.HOSTNAME}" maxPoolDepth="25" port="${MQ.PORT}" queueManager="${MQ.QMGR}" transportType="CLIENT" sslCipherSuite="${MQ.CIPHER}" userName="${MQ.CLNTUSER}" securityExit="/var/mqm/exits/name(entryPoint)" />```
