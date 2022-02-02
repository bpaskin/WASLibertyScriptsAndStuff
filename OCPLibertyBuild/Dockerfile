FROM icr.io/appcafe/open-liberty:full-java8-openj9-ubi 

USER 1001
COPY --chown=1001:0  server.xml /config/configDropins/defaults/server.xml
COPY --chown=1001:0  key.p12 /config/resources/security/
COPY --chown=1001:0  keystore.xml /config/configDropins/defaults/keystore.xml
COPY --chown=1001:0  DefaultApplication.ear /config/dropins
 
