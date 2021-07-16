### MQ to ActiveMQ and ActiveMQ to MQ ###

This is a sample how to use Liberty to transfer messages between two different messaging platforms using JMS.  ActiveMQ only supports JMS 1.1, so some extra code needed to be added.

All information about Queues, Channels, Conenctions, etc are kept in the `definitions.xml` file that needs to be edited for the specific use.  The [WMQ](https://www.ibm.com/support/fixcentral/swg/selectFixes?parent=ibm%7EWebSphere&product=ibm/WebSphere/WebSphere+MQ&release=9.2.2&platform=All&function=all) and [ActiveMQ](https://search.maven.org/search?q=a:activemq-rar) resource adapters need to be downloaded.  They should be placed in the `${server_config_dir}/resouces` directory and the correct version must be specified in the Docker files and the `definitions.xml` file.

To build a Docker image for MQ to AMQ:
`Docker build -t mq2amq:latest -f Dockerfile-mq2amq .`

To run:
`docker run -d --name mq2amq mq2amq:latest`

To build a Docker image for AMQ to MQ:
`Docker build -t amq2mq:latest -f Dockerfile-amq2mq .`

To run:
`docker run -d --name amq2mq amq2mq:latest`
