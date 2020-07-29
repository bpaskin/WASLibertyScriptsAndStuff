The collective can have extra information due to incorrect additions, deletions or updates to servers that cannot be removed by the regular `collective` commands and may cause issues with other servers, applications or clusters.  This script will delete a single server from the collective using the Liberty MBeans using [Jython](https://www.jython.org).

The Jython standalone must be [downloaded](https://www.jython.org/download.html).

1. Copy `jython-standalone-x.x.x.jar` (Jython standalone), `wlp/clients/restConnector.jar` and `wlp/clients/jython/restConnector.py` in the same directory
2. Update the variables section of the script
3. Execute the script `java -cp jython-standalone-x.x.x.jar:restConnector.jar org.python.util.jython path/to/LibertyDeleteServerCollective.py`

The script will output the following information if successful:
```
Connecting to the server...
Successfully connected to the server "collectiveHostName:portNumber"
Found server - deleting ...
```
