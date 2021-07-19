### MQ to ActiveMQ and ActiveMQ to MQ ###

This is a sample how to use Liberty/OpenLiberty to transfer messages between two different messaging platforms using JMS.  ActiveMQ only supports JMS 1.1, so some extra code needed to be added.

All information about Queues, Channels, Conenctions, etc are kept in the `definitions.xml` file that needs to be edited for the specific use.  The [WMQ](https://www.ibm.com/support/fixcentral/swg/selectFixes?parent=ibm%7EWebSphere&product=ibm/WebSphere/WebSphere+MQ&release=9.2.2&platform=All&function=all) and [ActiveMQ](https://search.maven.org/search?q=a:activemq-rar) resource adapters need to be downloaded.  They should be placed in the `${server_config_dir}/resouces` directory and the correct version must be specified in the Docker files and the `definitions.xml` file.

To build a Docker image for MQ to AMQ:
`Docker build -t mq2amq:latest -f Dockerfile-mq2amq .`

To run:
`docker run -d --name mq2amq mq2amq:latest`

To build a Docker image for AMQ to MQ:
`Docker build -t amq2mq:latest -f Dockerfile-amq2mq .`

To run:
`docker run -d --name amq2mq amq2mq:latest`

___________________________
### Working with OpenShift ###

To deploy to an OpenShift environment. 
1. Install the OpenLiberty Operator.  This can be done through the [Operator Hub](https://operatorhub.io/operator/open-liberty) or by following the [manual install steps](https://github.com/OpenLiberty/open-liberty-operator/tree/master/deploy/releases/0.7.1).  
2. Upload the images to a repository, like [Quay.io](https://quay.io).  
3. Use the [amq2mq.yaml](https://github.com/bpaskin/WASLibertyScriptsAndStuff/blob/master/MQ2AMQ-AMQ2MQ/amq2mq.yaml) or [mq2amq.yaml](https://github.com/bpaskin/WASLibertyScriptsAndStuff/blob/master/MQ2AMQ-AMQ2MQ/mq2amq.yaml) as a template and update the `namespace` and `applicationImage`, which is the location of the image to pull.  Run the command `oc apply -f mq2amq.yaml` or `oc apply -f amq2mq.yaml`

The logs of the pod that is created should show that the OpenLiberty server is started and connected to the both systems.
