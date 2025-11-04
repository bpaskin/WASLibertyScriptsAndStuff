package com.ibm.example.security;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.net.ssl.HttpsURLConnection;

import com.ibm.json.java.JSONObject;

import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;

import com.ibm.wsspi.security.crypto.CustomPasswordEncryption;
import com.ibm.wsspi.security.crypto.EncryptedInfo;
import com.ibm.wsspi.security.crypto.PasswordDecryptException;
import com.ibm.wsspi.security.crypto.PasswordEncryptException;

@Component(service = {CustomPasswordEncryption.class}, 
            immediate = true, 
            name = "com.ibm.example.security.HashiCorpVault", 
            configurationPolicy = ConfigurationPolicy.OPTIONAL, 
            property = {"service.vendor=IBM"})
public class HashiCorpVault implements CustomPasswordEncryption {

    private static final Class<?> CLASSNAME = HashiCorpVault.class;
    private static final Logger logger = Logger.getLogger(CLASSNAME.getCanonicalName());
    private static final String hashiURL = System.getProperty("com.ibm.hashiURL");
	private static final String hashiRoleId = System.getProperty("com.ibm.hashiRoleId");
	private static final String hashiSecretId = System.getProperty("com.ibm.hashiSecretId");


    public void initialize(Map initialization_data) {}

    @Activate
    protected synchronized void activate(ComponentContext cc, Map<String, Object> props) {
    if (logger.isLoggable(Level.FINE))
        logger.fine("activate : cc :" + cc + " properties : " + props); 
    }

    @Modified
    protected synchronized void modify(Map<String, Object> props) {
    if (logger.isLoggable(Level.FINE))
        logger.fine("modify : properties : " + props); 
    }

    @Deactivate
    protected void deactivate(ComponentContext cc) {
    if (logger.isLoggable(Level.FINE))
        logger.fine("deactivate : cc :" + cc); 
    }

    @Override
    public byte[] decrypt(EncryptedInfo encryptedInfo) throws PasswordDecryptException {
		logger.fine("Entering decrypt()" + encryptedInfo);
		
		String password = null;
		String encPassword = new String(encryptedInfo.getEncryptedBytes());
		logger.finer("Password : " + encPassword);
		
		// make sure that the custom properties have values or throw a password error
		if (null == hashiURL || null == hashiRoleId || null == hashiSecretId) {
			logger.severe("JVM custom properties missing for decrypting passwords");
			throw new PasswordDecryptException("JVM custom properties missing for decrypting passwords");
		}
		
		// check if password needs to go to Vault.  
		// this is designated with the field in brackets []
		try {
				String key = encPassword;
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
				logger.info("Exiting decrypt()");
				return password.getBytes();
		} catch (MalformedURLException e) {
			logger.log(Level.SEVERE, e.getMessage(), e);
			throw new PasswordDecryptException(e);
		} catch (IOException e) {
			logger.log(Level.SEVERE, e.getMessage(), e);
			throw new PasswordDecryptException(e);
		}
	}

	@Override
	public EncryptedInfo encrypt(byte[] password) throws PasswordEncryptException {
		logger.info("Entering encrypt()" + new String(password));
		logger.info("Exiting encrypt()");
		return new EncryptedInfo(password, "vault");
	}
}
