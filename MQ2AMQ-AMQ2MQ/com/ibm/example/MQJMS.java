package com.ibm.example;

import java.util.logging.Level;
import java.util.logging.Logger;

import javax.ejb.MessageDriven;
import javax.jms.Connection;
import javax.jms.ConnectionFactory;
import javax.jms.Destination;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageListener;
import javax.jms.MessageProducer;
import javax.jms.Session;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;

@MessageDriven(name="GetMessages")
public class MQJMS implements MessageListener {
	private static final String CLASSNAME = MQJMS.class.getName();
	private static final Logger logger = Logger.getLogger(CLASSNAME);
	
    public void onMessage(Message message) {
    	logger.entering(CLASSNAME, "onMessage");

    	try { 
    		logger.finer("Creating Context");
	    	Context context = new InitialContext();
    		
    		logger.finer("Lookup Connection Factory");
			ConnectionFactory cf = (ConnectionFactory) context.lookup("jms/OUT.CF");
			
			logger.finer("Create connection");
			Connection conn = cf.createConnection(); 
			
			logger.finer("Connect to JMS Provider");
			Session session = conn.createSession(false, Session.AUTO_ACKNOWLEDGE); 
			
			logger.finer("Creating Message Proder based on Context lookup");
			MessageProducer producer = session.createProducer((Destination)context.lookup("jms/OUT.Q")); 
			
			logger.finer("Sending message");
			producer.send(message);

    	} catch (NamingException e) {
    		logger.log(Level.SEVERE, e.getMessage(), e);
    		throw new RuntimeException(e.getMessage());
    	} catch (JMSException e) {
    		logger.log(Level.SEVERE, e.getMessage(), e);
    		throw new RuntimeException(e.getMessage());
    	}
    	
    	logger.exiting(CLASSNAME, "onMessage");
    }
}
