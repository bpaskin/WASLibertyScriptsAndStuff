package com.ibm.example.logon;

import java.util.Hashtable;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.security.auth.Subject;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.login.LoginException;
import javax.security.auth.spi.LoginModule;

import com.ibm.websphere.security.auth.WSLoginFailedException;
import com.ibm.websphere.security.auth.WSPrincipal;
import com.ibm.ws.security.common.auth.WSPrincipalImpl;
import com.ibm.wsspi.security.token.AttributeNameConstants;

/**
/* Sample Logon Module for WebSphere Application Server
/* This will take a given userid from SAML or some other method
/* do a lookup and then change the user to "mario"
/*
/* This module should be placed in a .jar and placed in the
/* websphere/lib/ext directory
/* 
/* update the the JAAS System Logons WEB_INBOUND
/* and make this the first in the order.
/*
/* 18/03/2021 - Brian S Paskin  
**/
public class UpdateUserid implements LoginModule {
	
	private static final String CLASSNAME = UpdateUserid.class.getName();
	private static final Logger logger = Logger.getLogger(CLASSNAME);
	
	private Subject subject;
    private CallbackHandler callbackHandler;
    private Map<String, Object> sharedState;

	@Override
	public boolean abort() throws LoginException {
		logger.entering(CLASSNAME, "abort()");
		logger.exiting(CLASSNAME, "abort()");
		return true;
	}

	@Override
	public boolean commit() throws LoginException {
		logger.entering(CLASSNAME, "commit()");
		logger.exiting(CLASSNAME, "commit()");
		return true;
	}

	@SuppressWarnings("unchecked")
	@Override
	public void initialize(Subject subject, CallbackHandler callbackHandler, Map<String, ?> sharedState, Map<String, ?> options) {
		logger.entering(CLASSNAME, "initialize()");
		this.callbackHandler = callbackHandler;
		this.sharedState = (Map<String, Object>) sharedState;
		this.subject = subject;
		logger.exiting(CLASSNAME, "initialize()");
	}

	@Override
	public boolean login() throws LoginException {
		logger.entering(CLASSNAME, "login()");
		
		NameCallback nameCallback = null;
		Callback[] callbacks = new Callback[1];
		callbacks[0] = nameCallback = new NameCallback("Username: ");
		
		try {
			callbackHandler.handle(callbacks);
		} catch (Exception e) {
			logger.log(Level.SEVERE, e.getMessage(), e);
			throw new WSLoginFailedException(e);
		}
		
		String username = nameCallback.getName();
		logger.fine("callback name : " + username);	
		
		// Do some code to do whatever is necessary
		// to translate to new user name
		String newUserId = "mario";  // Hard coded value for now
		
		WSPrincipal wsPrincipalImpl = new WSPrincipalImpl(newUserId);
		subject.getPrincipals().add(wsPrincipalImpl);
    	
		Hashtable<String, String> hashtable = new Hashtable<String, String>();
    	hashtable.put(AttributeNameConstants.WSCREDENTIAL_USERID,  newUserId);
    	hashtable.put(AttributeNameConstants.WSCREDENTIAL_UNIQUEID,  newUserId);
        hashtable.put(AttributeNameConstants.WSCREDENTIAL_SECURITYNAME,  newUserId);
        hashtable.put(AttributeNameConstants.WSCREDENTIAL_CACHE_KEY, "Cache:" + newUserId);
        sharedState.put(AttributeNameConstants.WSCREDENTIAL_PROPERTIES_KEY, hashtable);    
        
		logger.exiting(CLASSNAME, "login()");
		return true;
	}

	@Override
	public boolean logout() throws LoginException {
		logger.entering(CLASSNAME, "logout()");
		logger.exiting(CLASSNAME, "logout()");
		return true;
	}
}
