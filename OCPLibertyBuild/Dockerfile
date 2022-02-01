FROM icr.io/appcafe/open-liberty:full-java8-openj9-ubi 

USER 0
RUN apt-get update && apt-get -y upgrade && apt-get install vim hostname -y 

USER 1001
COPY --chown=1001:0  server.xml /config/
COPY --chown=1001:0  key.p12 /config/resources/security/
COPY --chown=1001:0  keystore.xml /config/configDropins/defaults/keystore.xml
COPY --chown=1001:0  DefaultApplication.ear /config/dropins
 
