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
---
### Create Project and Install Operator ###

The `openLibertyOperatorInstall.yaml` file will create a new project/namespace called `open-liberty`.  The Operator will be installed in this project/namespace.

1. logon to the target OCP environment using the `oc login` command.
2. execute the script using `oc apply -f openLibertyOperatorInstall.yaml`.
3. change to the new project-namespace using the command `oc project open-liberty`.
---
### Create ImageStreams, BuildConfig, and Build a new image ###

The `BuildAndImage.yaml` will save a base image of the latest version of version of Open Liberty to the `ImageStreams` and call it `open-liberty:full-java8-ibmjava-ubi`.  Then a new blank `ImageStream` will be created for the output of the build.  That will be called `open-liberty-sample:latest`.  Finally a `BuildConfig` will be created that will download the files from this github repository and build a container image using the supplied `Dockerfile` and files.  The new image will be stored in the blank image we created `open-liberty-sample:latest`.

A `Build` should be started after the `BuildConfig`.  The logs in the `Build` can be viewed on status and step.  The `BuildConfig` is set to only creat a `Builld` when the base `ImageStream` is changed.  In a real environment a webhook trigger would be setup, as well.

1. execute the script using the command `oc apply -f BuildAndImage.yaml`.
---
### Create a Secret to host Variables, create a ConfigMap for the jvm.options file, and create new OpenLiberty application pulling in the Secret and ConfigMap ###

Create a `Seecret` to place the HTTP and HTTPs ports.  This will be mounted by the OpenLiberty App under the `/config/variables` directory and read in as variables for use with the `server.xml` file.  Likewise, a `ConfigMap` with the `jvm.options` will be mounted under the `/config` directory and used when the OpenLiberty server starts.  The OpenLiberty server will take the new image from the `ImageStream` that was built and deploy it using the HTTPS port 9443 as passsthrough for the `Route` and `Service`.  

1. execute the command `oc apply -f openLibertyApp.yaml`.
