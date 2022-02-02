# Build and Deploy an OpenLiberty to container to OCP with OCP Builds #

This set of files contains yaml files to be run using the `oc` command line tool and tools built into OCP to 
- create a new project/namespace
- install the OpenLiberty Operator in that project/namespace
- download a base image from the IBM Container Registry, 
- get the necessary files from `git`, 
- add files from `git` for OpenLiberty Operation
- builld a new image based on base image previously downloaded, 
- create a Kubernetes `Secret` that will contain variables for the HTTP and HTTPS ports for OpenLiberty,
- create a Kubernetes `ConfigMap` that will contain the file `jvm.options` that will be mounted,
- create an OpenLiberty Application with the OpenLiberty Operator.  This also creates thee `Service` and `Route` using HTTPS.
- 
