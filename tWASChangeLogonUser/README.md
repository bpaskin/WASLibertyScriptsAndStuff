This is some sample code that uses a logon module to change the passed logged on user to another user.  This was used in conjunction with SAML IdP initated logon.

The code checks to see if there is a user logged on, takes the name and changes it to another user.  In this case it is changed to `mario`.

This allows for custom mappings of users, like if the IdP returns an email address and that needs to be mapped to a real username on the system.

Compile the code and place it in a `.jar` file and place the file in the `websphere/lib/ext` directory.  Next update the WEB_INBOUND under JAAS System applications and add the LogonModule.  It should be the first in the list or it may not work correctly.
