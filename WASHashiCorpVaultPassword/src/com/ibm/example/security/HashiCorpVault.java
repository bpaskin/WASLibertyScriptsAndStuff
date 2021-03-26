package com.ibm.example.security;

/**
 * Use HashiCorp Vault for traditional WebSphere App Server passwords
 * 
 * This code takes advantage of the plugable password security feature in tWAS
 * 
 *  Any password in the system that is enclosed in brackets [] will go to Vault
 *  to try to find the actual password for the item in brackets.  It searches under
 *  secrets/data/WAScreds/name.
 *  
 *  For example if a J2C auth alias had a password of [mydbpassword] then this code would
 *  attempt to logon to Vault and retrieve the password from secrets/data/WAScreds/mydbpassword
 *  
 *  Some requirements:
 *  Under vault the Secrets Engine must be called secrets and have a secret of WAScreds
 *  The user must have read permissions in the policy for secrets/data/WAScreds
 *  The public cert for the Vault must be stored in the truststore for tWAS 
 *  
 *  The following JVM custom properties must be placed under each App Server that will use this feature:
 *  com.ibm.wsspi.security.crypto.customPasswordEncryptionClass = com.ibm.example.security.HashiCorpVault
 *  com.ibm.wsspi.security.crypto.customPasswordEncryptionEnabled = true
 *  com.ibm.hashiURL = https://<hostname>:<port>/v1
 *  com.ibm.hashiRoleId = Vault Role ID
 *  com.ibm.hashiSecretId = Vault Secret ID for Role ID
 *  
 *  This code should be placed in a jar and placed under <was_home>/lib/ext
 *  
 *  To compile this code include the following tWAS libraries:
 *  com.ibm.ws.runtime.jar
 *  com.ibm.ws.prereq.jaxrs.jar
 *  
 *  Brian S Paskin
 *  v1.0.0.0 (26/03/2021)
 *  
 *  IBM makes no warranty for this code.  It is AS-IS.
 *  
 */

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.HttpsURLConnection;

import com.ibm.json.java.JSONObject;
import com.ibm.wsspi.security.crypto.CustomPasswordEncryption;
import com.ibm.wsspi.security.crypto.EncryptedInfo;
import com.ibm.wsspi.security.crypto.PasswordDecryptException;
import com.ibm.wsspi.security.crypto.PasswordEncryptException;

public class HashiCorpVault implements CustomPasswordEncryption {

	private static final String CLASSNAME = HashiCorpVault.class.getName();
	private static final Logger logger = Logger.getLogger(CLASSNAME);
	private static final String hashiURL = System.getProperty("com.ibm.hashiURL");
	private static final String hashiRoleId = System.getProperty("com.ibm.hashiRoleId");
	private static final String hashiSecretId = System.getProperty("com.ibm.hashiSecretId");

	@Override
	public byte[] decrypt(EncryptedInfo encryptedInfo) throws PasswordDecryptException {
		logger.entering(CLASSNAME, "decrypt()", encryptedInfo);
		
		String password = null;
		String encPassword = new String(encryptedInfo.getEncryptedBytes());
		logger.finer("Password : " + encPassword);
		
		// make sure that the custom properties have values or throw a password error
		if (null == hashiURL || null == hashiRoleId || null == hashiSecretId) {
			logger.log(Level.SEVERE, "JVM custom properties missing for decrypting passwords");
			throw new PasswordDecryptException("JVM custom properties missing for decrypting passwords");
		}
		
		// check if password needs to go to Vault.  
		// this is designated with the field in brackets []
		try {
			if ( encPassword.charAt(0) == '[' && encPassword.charAt(encPassword.length() - 1) == ']') {
				// get the key without the brackets
				String key = encPassword.substring(1, encPassword.length() - 1);
				logger.finer("Key : " + key);
				
				// login first to get the token to use for next step
				byte[] postData = ("{\"role_id\": \"" + hashiRoleId + "\", \"secret_id\": \"" + hashiSecretId + "\"}").getBytes("UTF-8");
				String postDataLength = Integer.toString(postData.length);
				logger.finest("sending data : "  +  new String(postData));
				logger.finest("sending length : "  + postDataLength);
				
				// setup the HTTP Connection
				URL url = new URL(null, hashiURL + "/auth/approle/login", new sun.net.www.protocol.https.Handler()); 
				HttpsURLConnection connection = (HttpsURLConnection) url.openConnection(); 
				connection.setDoOutput(true);
				connection.setDoInput(true);
				connection.setRequestMethod("POST");
				connection.setRequestProperty("Content-Length", postDataLength);
				connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
				connection.getOutputStream().write(postData);

				int status = connection.getResponseCode();
				logger.finest("HTTP AUTH returned status code : " + status);
				
				// get the input stream of the response
				Reader in;
				if (status == 200) {
					in = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
				} else {
					in = new BufferedReader(new InputStreamReader(connection.getErrorStream(), "UTF-8"));
				}
				
				// get character by character of the response and put it together
				JSONObject response = JSONObject.parse(in);
				
				// close the connection
				connection.disconnect();
				
				// check the status and get the token, if available
				String token = null;
				if (status != 200) {
					logger.severe("Response from Vault : " + response.toString());
				} else {
					logger.finer("Response from Vault : " + response.toString());	
					JSONObject auth = (JSONObject) response.get("auth");
					token = (String) auth.get("client_token");
					logger.finest("token : " + token);
				}
				
				// if there is no token throw an error
				if (null == token) {
					throw new PasswordDecryptException("Token was null");
				}
				
				// ready to send the next request with the token
				// to get the actual password
				url = new URL(null, hashiURL + "/secret/data/WAScreds", new sun.net.www.protocol.https.Handler()); 
				connection = (HttpsURLConnection) url.openConnection(); 
				connection.setDoOutput(true);
				connection.setRequestProperty("X-Vault-Token", token);
				connection.setRequestMethod("GET");
			
				status = connection.getResponseCode();
				logger.finest("HTTP KEY returned status code : " + status);
				
				// get the input stream of the response
				if (status == 200) {
					in = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"));
				} else {
					in = new BufferedReader(new InputStreamReader(connection.getErrorStream(), "UTF-8"));
				}
				
				// get character by character of the response and put it together
				response = JSONObject.parse(in);
				
				
				// close the connection
				connection.disconnect();
				
				if (status != 200) {
					logger.severe("Response from Vault : " + response.toString());
				} else {
					logger.finer("Response from Vault : " + response.toString());	
					response = (JSONObject) response.get("data");
					response = (JSONObject) response.get("data");
					password = (String) response.get(key);
					logger.finest("Password : " + password);
				}
				
				// make sire password is actually set
				if (null == password) {
					throw new PasswordDecryptException("Could not get password from Vault");
				}
				
				return password.getBytes();
			}
		} catch (MalformedURLException e) {
			logger.log(Level.SEVERE, e.getMessage(), e);
			throw new PasswordDecryptException(e);
		} catch (IOException e) {
			logger.log(Level.SEVERE, e.getMessage(), e);
			throw new PasswordDecryptException(e);
		}
		
		// regular password not in Vault
		logger.exiting(CLASSNAME, "decrypt()");
		return encryptedInfo.getEncryptedBytes();
	}

	@Override
	public EncryptedInfo encrypt(byte[] password) throws PasswordEncryptException {
		logger.entering(CLASSNAME, "encrypt()", new String(password));
		logger.exiting(CLASSNAME, "encrypt()");
		return new EncryptedInfo(password, "vault");
	}

	// Method is not used by tWAS at the current time.
	@Override
	public void initialize(HashMap map) {}

	
}
