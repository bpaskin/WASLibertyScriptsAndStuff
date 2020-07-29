# Script to delete server from a Liberty Collective using 
# Liberty MBeans
# 
# Requires Jython Standalone jar, restConnector.jar, and
# restConnector.py in the same directory
# fine the restConnector files under wlp/clients
#
# Variables MUST be updated accordingly
#
# usage:
# java -cp restConnector.jar:jython-standalone-2.7.2.jar org.python.util.jython path/to/file.py

from restConnector import JMXRESTConnector
from javax.management import ObjectName

# Variables that need to be updated for each server
COLLECTIVE_HOST = 'collectiveHostName'
COLLECTIVE_PORT = 9443
COLLECTIVE_USERID = 'adminUserid'
COLLECTIVE_PW = 'adminPassword'
TRUSTSTORE = '/path/to/truststore.p12'
TRUSTSTORE_PW = 'truststorePassword'
HOSTNAME = 'hostnameOfServer'
SERVER_NAME = 'serverName'

# USR_DIR is the directory with / replaced with %2F
# example USR_DIR = '%2Fusr%2FWebSphere%2Fwlp%2Fusr'
USR_DIR = '%2Fpath%2Fto%2Fusr'

# Setup truststore with collective controller public key
JMXRESTConnector.trustStore = TRUSTSTORE
JMXRESTConnector.trustStorePassword = TRUSTSTORE_PW

# Connect to the Collective
connector = JMXRESTConnector()
connection = connector.connect(COLLECTIVE_HOST, COLLECTIVE_PORT, COLLECTIVE_USERID, COLLECTIVE_PW)
mconnection = connector.getMBeanServerConnection()

# Get the CollectiveRepository MBean
mbeans = mconnection.queryNames(ObjectName("WebSphere:type=CollectiveRepository,*"), None).toArray()
mbean_repo = mbeans[0]

# Try to find the App Server, if found then delete it
DATA = '/sys.was.collectives/local/hosts/' + HOSTNAME + '/userdirs/' + USR_DIR + '/servers/' + SERVER_NAME

try:
	mconnection.invoke(mbean_repo, 'getDescendantData', [DATA],['java.lang.String'])
	print "Found server - deleting ..."
	mconnection.invoke(mbean_repo, 'delete', [DATA],['java.lang.String'])
except:
	print "Server not found"

