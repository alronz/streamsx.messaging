/*******************************************************************************
 * Copyright (C) 2014, International Business Machines Corporation
 * All Rights Reserved
 *******************************************************************************/
/* Generated by Streams Studio: 28 February, 2014 3:11:52 PM EST */
package com.ibm.streamsx.messaging.mqtt;


import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;

import org.apache.log4j.Logger;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;

import com.ibm.streams.operator.Attribute;
import com.ibm.streams.operator.OperatorContext;
import com.ibm.streams.operator.OperatorContext.ContextCheck;
import com.ibm.streams.operator.OutputTuple;
import com.ibm.streams.operator.StreamSchema;
import com.ibm.streams.operator.StreamingInput;
import com.ibm.streams.operator.StreamingOutput;
import com.ibm.streams.operator.Tuple;
import com.ibm.streams.operator.Type;
import com.ibm.streams.operator.Type.MetaType;
import com.ibm.streams.operator.compile.OperatorContextChecker;
import com.ibm.streams.operator.log4j.TraceLevel;
import com.ibm.streams.operator.model.Icons;
import com.ibm.streams.operator.model.InputPortSet;
import com.ibm.streams.operator.model.InputPortSet.WindowMode;
import com.ibm.streams.operator.model.InputPortSet.WindowPunctuationInputMode;
import com.ibm.streams.operator.model.InputPorts;
import com.ibm.streams.operator.model.Libraries;
import com.ibm.streams.operator.model.OutputPortSet;
import com.ibm.streams.operator.model.OutputPortSet.WindowPunctuationOutputMode;
import com.ibm.streams.operator.model.OutputPorts;
import com.ibm.streams.operator.model.Parameter;
import com.ibm.streams.operator.model.PrimitiveOperator;
import com.ibm.streams.operator.state.ConsistentRegionContext;
import com.ibm.streams.operator.types.RString;
import com.ibm.streams.operator.types.ValueFactory;
import com.ibm.streamsx.messaging.common.DataGovernanceUtil;
import com.ibm.streamsx.messaging.common.IGovernanceConstants;
import com.ibm.streamsx.messaging.common.PropertyProvider;
import com.ibm.streamsx.messaging.mqtt.Messages;
import com.ibm.streamsx.messaging.mqtt.MqttClientRequest.MqttClientRequestType;

/**
 * A source operator that does not receive any input streams and produces new tuples. 
 * The method <code>produceTuples</code> is called to begin submitting tuples.
 * <P>
 * For a source operator, the following event methods from the Operator interface can be called:
 * </p>
 * <ul>
 * <li><code>initialize()</code> to perform operator initialization</li>
 * <li>allPortsReady() notification indicates the operator's ports are ready to process and submit tuples</li> 
 * <li>shutdown() to shutdown the operator. A shutdown request may occur at any time, 
 * such as a request to stop a PE or cancel a job. 
 * Thus the shutdown() may occur while the operator is processing tuples, punctuation marks, 
 * or even during port ready notification.</li>
 * </ul>
 * <p>With the exception of operator initialization, all the other events may occur concurrently with each other, 
 * which lead to these methods being called concurrently by different threads.</p> 
 */
@PrimitiveOperator(name = "MQTTSource", namespace = "com.ibm.streamsx.messaging.mqtt", description = SPLDocConstants.MQTTSRC_OP_DESCRIPTION)
@InputPorts({ @InputPortSet(description = SPLDocConstants.MQTTSRC_INPUT_PORT0, optional = true, windowingMode = WindowMode.NonWindowed, windowPunctuationInputMode = WindowPunctuationInputMode.Oblivious) })
@OutputPorts({
		@OutputPortSet(description = SPLDocConstants.MQTTSRC_OUPUT_PORT_0, cardinality = 1, optional = false, windowPunctuationOutputMode = WindowPunctuationOutputMode.Free),
		@OutputPortSet(description = SPLDocConstants.MQTTSRC_OUTPUT_PORT_1, optional = true, cardinality = 1, windowPunctuationOutputMode = WindowPunctuationOutputMode.Free) })
@Libraries(value = { "opt/downloaded/*" })
@Icons(location16 = "icons/MQTTSource_16.gif", location32 = "icons/MQTTSource_32.gif")
public class MqttSourceOperator extends AbstractMqttOperator { 
	
	private static Logger TRACE = Logger.getLogger(MqttSourceOperator.class);
	
	// Parameters 
	private List<String> paramTopics; 
	private List<Integer> paramQos;
	private List<String> paramQosStr;
	private String topicOutAttrName;
	private int reconnectionBound = IMqttConstants.DEFAULT_RECONNECTION_BOUND;		// default 5, 0 = no retry, -1 = infinite retry
	private long period = IMqttConstants.DEFAULT_RECONNECTION_PERIOD;
	private MqttClientWrapper mqttWrapper;
	private boolean shutdown = false;
	
	private int messageQueueSize = IMqttConstants.MQTTSRC_DEFAULT_QUEUE_SIZE;
	
	private ArrayBlockingQueue<MqttMessageRecord> messageQueue;
	private ArrayBlockingQueue<MqttClientRequest> clientRequestQueue;
	
	/**
	 * Thread for calling <code>produceTuples()</code> to produce tuples 
	 */
    private Thread processThread;
    
    private Thread clientRequestThread;
    
    private class MqttMessageRecord {
    	String topic;
    	MqttMessage message;
    	
    	public MqttMessageRecord(String topic, MqttMessage message) {
    		this.topic = topic;
    		this.message = message;
		}
    }
    
    
    private MqttCallback callback = new MqttCallback() {

		@Override
		public void connectionLost(Throwable cause) {
			scheduleConnectAndSubscribe(getServerUri());
		}

		@Override
		public void messageArrived(String topic, MqttMessage message)
				throws Exception {
			
			MqttMessageRecord record = new MqttMessageRecord(topic, message);
			messageQueue.put(record);					
		}

		@Override
		public void deliveryComplete(IMqttDeliveryToken token) {		
			
		}    	
    	
    };

    @ContextCheck(compile=true)
    public static void checkQosQosStrExclusive(OperatorContextChecker checker) {
    	checker.checkExcludedParameters("qos", "qosStr"); //$NON-NLS-1$ //$NON-NLS-2$
    	checker.checkExcludedParameters("qosStr", "qos"); //$NON-NLS-1$ //$NON-NLS-2$
    }
    
    @ContextCheck(compile=true)
	public static void checkConsistentRegion(OperatorContextChecker checker) {
		
		// check if this operator is within a consistent region
		OperatorContext oContext = checker.getOperatorContext();
		ConsistentRegionContext cContext = oContext.getOptionalContext(ConsistentRegionContext.class);
		
		if(cContext != null) {
			checker.setInvalidContext(Messages.getString("OP_CANNOT_PARTICIPATE_IN_CONSISTENT_REGION"), new String[] {"MQTTSource"}); //$NON-NLS-1$
		}
	}
    
    @ContextCheck(compile=false)
    public  static void runtimeChecks(OperatorContextChecker checker) {
    	
    	validateNumber(checker, "period", 0, Long.MAX_VALUE); //$NON-NLS-1$
    	validateNumber(checker, "qos", 0, 2); //$NON-NLS-1$
    	validateNumber(checker, "reconnectionBound", -1, Long.MAX_VALUE); //$NON-NLS-1$
    	validateNumber(checker, "messageQueueSize", 1, Integer.MAX_VALUE); //$NON-NLS-1$
    	validateCommaSeparatedNumber(checker, "qosStr", 0, 2); //$NON-NLS-1$
    	
    	int topicsCount = getCommaSeparatedParamNumber(checker, "topics"); //$NON-NLS-1$
    	int qosTotalCount = getCommaSeparatedParamNumber(checker, "qos") + getCommaSeparatedParamNumber(checker, "qosStr"); //$NON-NLS-1$ //$NON-NLS-2$
    	
    	//List<String> topicValues = checker.getOperatorContext().getParameterValues("topics"); //$NON-NLS-1$
    	//List<String> qosValues = checker.getOperatorContext().getParameterValues("qos"); //$NON-NLS-1$
    	
    	if (qosTotalCount > 0 && topicsCount != qosTotalCount)
    	{
    		checker.setInvalidContext(Messages.getString("NUMBER_OF_TOPICS_MUST_EQUAL_QOS_VALUES"), new Object[] {}); //$NON-NLS-1$
    	}
    	
    	if (checker.getOperatorContext().getParameterNames().contains("topicOutAttrName")) { //$NON-NLS-1$
    		
    		List<String> parameterValues = checker.getOperatorContext().getParameterValues("topicOutAttrName"); //$NON-NLS-1$
    		String outAttributeName = parameterValues.get(0);
	    	List<StreamingOutput<OutputTuple>> outputPorts = checker.getOperatorContext().getStreamingOutputs();
	    	if (outputPorts.size() > 0)
	    	{
	    		StreamingOutput<OutputTuple> outputPort = outputPorts.get(0);
	    		StreamSchema streamSchema = outputPort.getStreamSchema();
	    		boolean check = checker.checkRequiredAttributes(streamSchema, outAttributeName);
	    		if (check)
	    			checker.checkAttributeType(streamSchema.getAttribute(outAttributeName), MetaType.RSTRING, MetaType.USTRING);
	    	}
    	}
    	
    	if(checker.getOperatorContext().getParameterNames().contains("dataAttributeName")) { //$NON-NLS-1$
    		
    		List<String> parameterValues = checker.getOperatorContext().getParameterValues("dataAttributeName"); //$NON-NLS-1$
    		String dataAttributeName = parameterValues.get(0);
    		List<StreamingOutput<OutputTuple>> outputPorts = checker.getOperatorContext().getStreamingOutputs();
    		if (outputPorts.size() > 0) 
    		{
    			StreamingOutput<OutputTuple> outputPort = outputPorts.get(0);
	    		StreamSchema streamSchema = outputPort.getStreamSchema();
	    		boolean check = checker.checkRequiredAttributes(streamSchema, dataAttributeName);
	    		if (check)
	    			checker.checkAttributeType(streamSchema.getAttribute(dataAttributeName), MetaType.RSTRING, MetaType.BLOB );
    		}
    	}
    	
    }
    
    // There are parameters such as qosStr allows comma separated value to be specified.
    // i.e "0, 1", this method will parse the comma separated string value and parse it to 
    // a number to verify if the parsed number is within the min/max range.
    private static void validateCommaSeparatedNumber(OperatorContextChecker checker, String paramName, long min, long max) {
    	try {
			List<String> paramValues = checker.getOperatorContext().getParameterValues(paramName);
			for (String paramValue : paramValues) {
				
				String[] paramStrVal = paramValue.split(IMqttConstants.COMMA);
				
				for(String strVal : paramStrVal) {
					Long longVal = Long.valueOf(strVal.trim());

					if (longVal.longValue() > max || longVal.longValue() < min) {
						checker.setInvalidContext(
								Messages.getString("NOT_IN_RANGE"), //$NON-NLS-1$
								new Object[] { paramName, min, max });
					}
				}
				
			}
		} catch (NumberFormatException e) {
			checker.setInvalidContext(
					Messages.getString("NOT_A_NUMBER"), //$NON-NLS-1$
					new Object[] { paramName });
		}
    }
    
    private static int getCommaSeparatedParamNumber(OperatorContextChecker checker, String paramName) {
    	int count = 0;
    	List<String> paramValues = checker.getOperatorContext().getParameterValues(paramName);
    	
    	for(String paramStrValue : paramValues) {
    		String[] parsedParamStrValue = paramStrValue.split(IMqttConstants.COMMA);
    		
    		count += parsedParamStrValue.length;
    	}
    	
    	return count;
    	
    }
    
    @ContextCheck(compile=true, runtime=false)
	public static void checkOutputPort(OperatorContextChecker checker) {
		List<StreamingOutput<OutputTuple>> outputPorts = checker.getOperatorContext().getStreamingOutputs();
		
		if (outputPorts.size() > 0)
		{
			
			// if user is not specifying dataAttributeName attribute, then we check if stream schema contains default data attribute
			if(!checker.getOperatorContext().getParameterNames().contains("dataAttributeName")) { //$NON-NLS-1$
				
				StreamingOutput<OutputTuple> dataPort = outputPorts.get(0);
				StreamSchema streamSchema = dataPort.getStreamSchema();
				
				Attribute dataAttribute = null;
				
				if(streamSchema.getAttributeCount() == 1) {
					dataAttribute = streamSchema.getAttribute(0);
				}
				else {
					dataAttribute = streamSchema.getAttribute("data"); //$NON-NLS-1$
				}
				
				// the default data attribute must be present and must be either BLOB or RSTRING
				if(dataAttribute != null) {
					checker.checkAttributeType(dataAttribute, MetaType.RSTRING, MetaType.BLOB );
				}
				else {
					checker.setInvalidContext(Messages.getString("DATA_ATTRIB_NOT_FOUND_FROM_OUTPUT_PORT"), new Object[]{}); //$NON-NLS-1$
				}
			}
		}
		
		validateSchemaForErrorOutputPort(checker, getErrorPortFromContext(checker.getOperatorContext()));
		// TODO:  check control input port
    }

	/**
     * Initialize this operator. Called once before any tuples are processed.
     * @param context OperatorContext for this operator.
     * @throws Exception Operator failure, will cause the enclosing PE to terminate.
     */
    @Override
    public synchronized void initialize(OperatorContext context)
            throws Exception {
    	// Must call super.initialize(context) to correctly setup an operator.
        super.initialize(context);
        Logger.getLogger(this.getClass()).trace("Operator " + context.getName() + " initializing in PE: " + context.getPE().getPEId() + " in Job: " + context.getPE().getJobId() ); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        
        messageQueue = new ArrayBlockingQueue<MqttSourceOperator.MqttMessageRecord>(getMessageQueueSize());
        clientRequestQueue = new ArrayBlockingQueue<MqttClientRequest>(20);
        
        mqttWrapper = new MqttClientWrapper();
        
        initFromConnectionDocument();
        setupSslProperties(mqttWrapper);
        
        mqttWrapper.setBrokerUri(getServerUri());
        mqttWrapper.setUserID(getUserID());
        mqttWrapper.setPassword(getPassword());
        mqttWrapper.setClientID(getClientID());
        mqttWrapper.setCommandTimeout(getCommandTimeout());
        mqttWrapper.setKeepAliveInterval(getKeepAliveInterval());
        mqttWrapper.setConnectionLostMetric(nConnectionLost);
        mqttWrapper.setIsConnectedMetric(isConnected);
        
        if(getAppConfigName() != null) {
     	   mqttWrapper.setPropProvider(new PropertyProvider(context.getPE(), getAppConfigName()));
     	   mqttWrapper.setUserPropName(getUserPropName());
     	   mqttWrapper.setPasswordPropName(getPasswordPropName());
        }
        
        // register for data governance
        registerForDataGovernance();
        
        /*
         * Create the thread for producing tuples. 
         * The thread is created at initialize time but started.
         * The thread will be started by allPortsReady().
         */
        processThread = getOperatorContext().getThreadFactory().newThread(
                new Runnable() {

                    @Override
                    public void run() {
                        try {
                            produceTuples();
                        } catch (Exception e) {
                            Logger.getLogger(this.getClass()).error("Operator error", e); //$NON-NLS-1$
                        }                    
                    }
                    
                });
        
        /*
         * Set the thread not to be a daemon to ensure that the SPL runtime
         * will wait for the thread to complete before determining the
         * operator is complete.
         */
        processThread.setDaemon(false);
        
        /*
         * Create the thread for producing tuples. 
         * The thread is created at initialize time but started.
         * The thread will be started by allPortsReady().
         */
        clientRequestThread = getOperatorContext().getThreadFactory().newThread(
                new Runnable() {

                    @Override
                    public void run() {                       
                        handleClientRequests();                                  
                    }
                    
                });
        
        /*
         * Set the thread not to be a daemon to ensure that the SPL runtime
         * will wait for the thread to complete before determining the
         * operator is complete.
         */
        clientRequestThread.setDaemon(true);       
    }

    private void registerForDataGovernance() {
		String uri = getServerUri();
		List<String> topics = getTopics();
		TRACE.log(TraceLevel.INFO, "MQTTSource - Registering for data governance with server uri: " + uri + " and topics: " + topics.toArray().toString()); //$NON-NLS-1$ //$NON-NLS-2$
		
		if(topics != null && uri != null && !uri.isEmpty()) {		
			for (String topic : topics) {			
				TRACE.log(TraceLevel.INFO, "MQTTSource - Registering for data governance with server uri: " + uri + " and topic: " + topic); //$NON-NLS-1$ //$NON-NLS-2$
				DataGovernanceUtil.registerForDataGovernance(this, topic, IGovernanceConstants.ASSET_MQTT_TOPIC_TYPE, uri, IGovernanceConstants.ASSET_MQTT_SERVER_TYPE, true, "MQTTSource"); //$NON-NLS-1$
			}
		} else {
			TRACE.log(TraceLevel.INFO, "MQTTSource - Registering for data governance -- aborted. topic and/or uri is null"); //$NON-NLS-1$
		}
	}
    
	protected void handleClientRequests() {
		while (!shutdown)
        {
        	try {
				MqttClientRequest request = clientRequestQueue.take();
				
				if (request.getReqType() == MqttClientRequestType.CONNECT)
				{				
					// only handle the last request
					if (mqttWrapper.getPendingBrokerUri().isEmpty()
							|| !mqttWrapper.getPendingBrokerUri().isEmpty()
							&& mqttWrapper.getPendingBrokerUri().equals(
									request.getServerUri()))
					{
						TRACE.log(TraceLevel.DEBUG, "[Request Queue:] " + IMqttConstants.CONN_SERVERURI + ":" + getServerUri()); //$NON-NLS-1$ //$NON-NLS-2$

						setServerUri(request.getServerUri());
						mqttWrapper.setBrokerUri(request.getServerUri());
						
						// disconnect and try to connect again.
						// Disconnect is synchronous so wait for that to finish and attempt to connect.
						try {
							mqttWrapper.disconnect();
						} catch (Exception e) {
							// disconnect may fail as the server may have been disconnected
							TRACE.log(TraceLevel.DEBUG, "[Request Queue:] Disconnect exception."); //$NON-NLS-1$ //$NON-NLS-2$
						}					
						connectAndSubscribe();
					}
				}
				
				// When we update the topic subscriptions, we need to manually
				// manage the topics / qos being used.  The paramTopics and paramQos
				// lists are used to set up initial subscriptions when a connection is being made
				// The MQTT client does not provide a way for us to query for the information 
				else if (request.getReqType() == MqttClientRequestType.ADD_TOPICS)
				{
					TRACE.log(TraceLevel.DEBUG, "[Request Queue: Add Topics] " + request.getTopics() + ":" + request.getQos());  //$NON-NLS-1$ //$NON-NLS-2$
					// add to topic list
					addTopics(request.getTopics(), request.getQos());
					int[] qos = createQosList(request.getTopics(), request.getQos());
					
					mqttWrapper.subscribe(request.getTopics(), qos);
				}
				else if (request.getReqType() == MqttClientRequestType.REMOVE_TOPICS)
				{
					TRACE.log(TraceLevel.DEBUG, "[Request Queue: remove Topics] " + request.getTopics()); //$NON-NLS-1$
					// remove from topic list
					removeTopics(request.getTopics());
					// unsubscribe the specified topic
					mqttWrapper.unsubscribe(request.getTopics());
				}
				else if (request.getReqType() == MqttClientRequestType.UPDATE_TOPICS) {
					TRACE.log(TraceLevel.DEBUG, "[Request Queue: Update Topics] " + request.getTopics() + ":" + request.getQos()); //$NON-NLS-1$ //$NON-NLS-2$
					// update qos for topic
					updateTopics(request.getTopics(), request.getQos());
					// unsubscribe specified topic
					mqttWrapper.unsubscribe(request.getTopics());

					// subscribe topic with new qos
					int[] qos = createQosList(request.getTopics(), request.getQos());
					mqttWrapper.subscribe(request.getTopics(), qos);
				}
				else if (request.getReqType() == MqttClientRequestType.REPLACE_TOPICS)
				{
					TRACE.log(TraceLevel.DEBUG, "[Request Queue: Replace Topics] " + request.getTopics() + ":" + request.getQos()); //$NON-NLS-1$ //$NON-NLS-2$
					
					// unsubscribe all topics	
					mqttWrapper.unsubscribe((String[]) paramTopics.toArray(new String[0]));
					
					// update topic list and qos list
					replaceTopics(request.getTopics(), request.getQos());

					// subscribe to new set of topics with correct qos
					int[] qos = createQosList(request.getTopics(), request.getQos());
					mqttWrapper.subscribe(request.getTopics(), qos);
				}				
			} catch (InterruptedException e) {			
				TRACE.log(TraceLevel.DEBUG, "[Request Queue:] Thread interrupted as expected: " + e.getLocalizedMessage()); //$NON-NLS-1$ //$NON-NLS-2$
			} catch (URISyntaxException e1) {
				String errorMsg = Messages.getString("URI_SYNTAX_EXCEPTION", e1.getLocalizedMessage()); //$NON-NLS-1$
				TRACE.log(TraceLevel.ERROR, errorMsg,e1);
				submitToErrorPort(errorMsg, null);
			} catch (MqttException e) {				
				String errorMsg = Messages.getString("MGTT_CLIENT_ERROR_WHILE_HANDLING_MQTT_CLIENT_REQ", e.getLocalizedMessage()); //$NON-NLS-1$
				TRACE.log(TraceLevel.ERROR, errorMsg,e);
				submitToErrorPort(errorMsg, null);
			} catch (RuntimeException e) {
				String errorMsg = Messages.getString("RUNTIME_EXCEPTION_WHILE_HANDLING_MQTT_CLIENT_REQ", e.getLocalizedMessage()); //$NON-NLS-1$
				TRACE.log(TraceLevel.ERROR, errorMsg,e);
				submitToErrorPort(errorMsg, null);
				
				// rethrow connect exception to cause the operator to exit
				if (e instanceof MqttClientConnectException)
					throw e;
			}
        }
		
	}

	private int[] createQosList(String[]  topics, int topicsQos) {
		int[] qos = new int[topics.length];
		for (int i = 0; i < qos.length; i++) {
			qos[i] = topicsQos;
		}
		return qos;
	}

	private void addTopics(String[] topicsToAdd, int qos) {
		int[] qosToAdd = createQosList(topicsToAdd, qos);
		
		List<String> topicList = Arrays.asList(topicsToAdd);
		paramTopics.addAll(topicList);
		
		for (int i = 0; i < qosToAdd.length; i++) {
			paramQos.add(qosToAdd[i]);
		}		
	}
	
	private void removeTopics(String[] topicsToRemove){		
		for (int i = 0; i < topicsToRemove.length; i++) {
			int topicIdx = paramTopics.indexOf(topicsToRemove[i]);
			if (topicIdx >= 0)
			{
				paramTopics.remove(topicIdx);
				paramQos.remove(topicIdx);
			}
		}		
	}
	
	private void replaceTopics(String[] topicsToReplace, int qos)	{
		paramTopics.clear();
		paramQos.clear();
		addTopics(topicsToReplace, qos);
	}
	
	private void updateTopics(String[] topicsToUpdate, int qos){		
		for (int i = 0; i < topicsToUpdate.length; i++) {
			int topicIdx = paramTopics.indexOf(topicsToUpdate[i]);
			if (topicIdx >= 0)
			{
				paramQos.set(topicIdx, qos);
			}
		}		
	}

	private void connectAndSubscribe() throws MqttException, InterruptedException {
		mqttWrapper.connect(getReconnectionBound(), getPeriod());
        mqttWrapper.addCallBack(callback);               
        
        // qos is an optional parameter, set up defaults if it is not specified
        if (paramQos == null)
        {
        	paramQos = new ArrayList<Integer>();
        	for (int i = 0; i < paramTopics.size(); i++) {
				paramQos.add(0);
			}
        }
        
        mqttWrapper.subscribe((String[])paramTopics.toArray(new String[0]), getQos());
	}

    /**
     * Notification that initialization is complete and all input and output ports 
     * are connected and ready to receive and submit tuples.
     * @throws Exception Operator failure, will cause the enclosing PE to terminate.
     */
    @Override
    public synchronized void allPortsReady() throws Exception {
        OperatorContext context = getOperatorContext();
        Logger.getLogger(this.getClass()).trace("Operator " + context.getName() + " all ports are ready in PE: " + context.getPE().getPEId() + " in Job: " + context.getPE().getJobId() ); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
    	// Start a thread for producing tuples because operator 
    	// implementations must not block and must return control to the caller.
        processThread.start();        
        clientRequestThread.start(); 
        
        // submit and subscribe on background thread, allow operator to start
        scheduleConnectAndSubscribe(getServerUri());        
    }
    
    private void scheduleConnectAndSubscribe(String serverUri) {
    	try {
    		// connect request will automatically take current topics and subscribe
			MqttClientRequest request = new MqttClientRequest().setReqType(MqttClientRequestType.CONNECT).setServerUri(serverUri);
			clientRequestQueue.put(request);
			
		} catch (InterruptedException e) {
		
		}
    }
    
    /**
     * Submit new tuples to the output stream
     * @throws Exception if an error occurs while submitting a tuple
     */
    private void produceTuples() throws Exception  {
    	StreamSchema streamSchema = getOutput(0).getStreamSchema();
		String dataAttributeName = this.getDataAttributeName() == null ? IMqttConstants.MQTT_DEFAULT_DATA_ATTRIBUTE_NAME : this.getDataAttributeName();
		
		int dataAttrIndex = streamSchema.getAttributeIndex(dataAttributeName);
		
		if(dataAttrIndex == -1) {
			dataAttrIndex = 0;
		}
		
		Type.MetaType dataAttributeType = streamSchema.getAttribute(dataAttrIndex).getType().getMetaType();
		
		boolean isBlob = false;
		if(dataAttributeType.equals(MetaType.BLOB))
			isBlob = true;
		else if (dataAttributeType.equals(MetaType.RSTRING))
			isBlob = false;
		
        while (!shutdown)
        {
        	MqttMessageRecord record = messageQueue.take();
			byte[] blob = record.message.getPayload();
			
			StreamingOutput<OutputTuple> outputPort = getOutput(0);
			OutputTuple tuple = outputPort.newTuple();
			
			if(isBlob) {
				tuple.setBlob(dataAttrIndex, ValueFactory.newBlob(blob));
			}
			else { // it should be RSTRING type
				tuple.setObject(dataAttrIndex, new RString(blob));
			}

			if (topicOutAttrName != null)
			{
				tuple.setString(topicOutAttrName, record.topic);
			}
			
			outputPort.submit(tuple);
        }
    }
    
    @Override
    public void process(StreamingInput<Tuple> stream, Tuple tuple)
    		throws Exception {
    	handleControlSignal(tuple);
    }
    
	/**
	 * In the handling of control signal of MQTTSource, the following should occur
	 * 1)  read in the entire tuple to see what topic actions need to be taken
	 * 2)  update topic subscription list before handling any connection signal
	 * 3)  handle connection signal
	 * @param tuple
	 * @throws Exception 
	 */
	private void handleControlSignal(Tuple tuple) throws Exception {
		// 
		try {
			StreamSchema streamSchema = tuple.getStreamSchema();
			int attributeCount = streamSchema.getAttributeCount();
			
			for(int i=0; i<attributeCount; i++)
			{
				Object object = tuple.getObject(i);
				TRACE.log(TraceLevel.DEBUG, "[Control Port:] object: " + object + " " + object.getClass().getName()); //$NON-NLS-1$ //$NON-NLS-2$

				// if it's a map, it must be the mqttConfig attribute
				// handle control signal to switch server
				if (object instanceof Map)
				{									
					Map map = (Map)object;
					Set keySet = map.keySet();
					for (Iterator iterator = keySet.iterator(); iterator
							.hasNext();) {
						Object key = (Object) iterator.next();
						TRACE.log(TraceLevel.DEBUG, "[Control Port:] " + key + " " + key.getClass()); //$NON-NLS-1$ //$NON-NLS-2$
						
						String keyStr = key.toString();
						
						// case insensitive checks
						if (keyStr.toLowerCase().equals(IMqttConstants.CONN_SERVERURI.toLowerCase()))
						{
							Object serverUri = map.get(key);				
							
							String serverUriStr = serverUri.toString();
							
							// only handle if server URI has changed
							if (!serverUriStr.isEmpty() && !serverUriStr.toLowerCase().equals(getServerUri().toLowerCase()))
							{
								// set pending broker URI field to get wrapper
								// to get out of retry loop
								mqttWrapper.setPendingBrokerUri(serverUriStr);
								
								scheduleConnectAndSubscribe(serverUriStr);
								
								// wake up the thread in case it is sleeping
								clientRequestThread.interrupt();
							}
							else if (serverUriStr.isEmpty())
							{
								String errorMsg = Messages.getString("IGNORED_EMPTY_ERVER_URI_FROM_CTRL_SIGNAL"); //$NON-NLS-1$
								
								TRACE.log(TraceLevel.ERROR,errorMsg);
								submitToErrorPort(errorMsg, null);								
							}
							else if (serverUriStr.toLowerCase().equals(getServerUri().toLowerCase())){

								String errorMsg = Messages.getString("SERVER_URI_FROM_SIGNAL_IGNORED_AS_ALREADY_CONNECTED"); //$NON-NLS-1$
								
								TRACE.log(TraceLevel.WARN,errorMsg);
							}
						}					
					}
				}
				else if (object instanceof List)
				{
					List<Tuple> topicList = (List<Tuple>)object;
					for (Tuple topicDesc : topicList) {
						
						// read the control signal
						String signalAction=topicDesc.getString(IMqttConstants.MQTTSRC_TOPICDESC_ACTION);
						List<?> signalTopicList = topicDesc.getList(IMqttConstants.MQTTSRC_TOPICDESC_TOPICS);
						List reqTopics = new ArrayList<String>();
						for (Iterator iterator = signalTopicList.iterator(); iterator
								.hasNext();) {
							// must call toString as the topic can come in as list of RString
							String topicFromSignal = (String) iterator.next().toString();
							
							if (!topicFromSignal.isEmpty()){
								reqTopics.add(topicFromSignal);
							}
							else
							{
								String errorMsg = Messages.getString("CTRL_SIGNAL_CONTAINS_EMPTY_TOPIC"); //$NON-NLS-1$								
								TRACE.log(TraceLevel.WARN,errorMsg);
							}
						}
						int signalQos = topicDesc.getInt(IMqttConstants.MQTTSRC_TOPICDESC_QOS);
						
						if (signalQos >= 0 && signalQos < 3 && reqTopics.size() > 0)
						{
							// construct client request
							MqttClientRequest request = new MqttClientRequest();
							MqttClientRequestType reqType = MqttClientRequest.getRequestType(signalAction);
							
							if (reqType != null)
							{
								request.setReqType(reqType).setTopics((String[])reqTopics.toArray(new String[0])).setQos(signalQos);							
								// submit client request
								clientRequestQueue.put(request);
							}
						}
						else if (reqTopics.size() > 0)
						{
							String errorMsg = Messages.getString("SIGNAL_CONTAINS_INVALID_QOS", reqTopics.toString(), signalQos); //$NON-NLS-1$
							
							TRACE.log(TraceLevel.ERROR,errorMsg);
							submitToErrorPort(errorMsg, null);	
						}
					}
				}
			}
			
			
		} catch (Exception e) {
			
			String tupleAsString = tuple.toString();
			String errorMsg = Messages.getString("CANNOT_PROCESS_CTRL_SIGNAL", tupleAsString); //$NON-NLS-1$

			TRACE.log(TraceLevel.ERROR,errorMsg); //$NON-NLS-1$
						
			submitToErrorPort(errorMsg, null);
		}
	}

	/**
     * Shutdown this operator, which will interrupt the thread
     * executing the <code>produceTuples()</code> method.
     * @throws Exception Operator failure, will cause the enclosing PE to terminate.
     */
    public synchronized void shutdown() throws Exception {
    	
    	shutdown = true;
    	
        if (processThread != null) {
            processThread.interrupt();
            processThread = null;
        }
        OperatorContext context = getOperatorContext();
        Logger.getLogger(this.getClass()).trace("Operator " + context.getName() + " shutting down in PE: " + context.getPE().getPEId() + " in Job: " + context.getPE().getJobId() ); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
        
        mqttWrapper.disconnect();
        mqttWrapper.shutdown();

        // Must call super.shutdown()
        super.shutdown();
    }
    
    @Parameter(name="topics", description=SPLDocConstants.MQTTSRC_PARAM_TOPICS_DESC, optional=false, cardinality=-1)
	public void setTopics(List<String> topics) {
		this.paramTopics = new ArrayList<String>();
		
		for(String csTopics : topics) {
			String[] topic = csTopics.split(IMqttConstants.COMMA);
			
			for(String aTopic : topic) {
				paramTopics.add(aTopic.trim());
			}
		}
	}

    @Parameter(name="qos", description=SPLDocConstants.MQTTSRC_PARAM_QOS_DESC, optional=true, cardinality=-1)
	public void setQos(int[] qos) {    	
    	this.paramQos = new ArrayList<Integer>();
    	for (int i = 0; i < qos.length; i++) {
			paramQos.add(qos[i]);
		}
	}

    public List<String> getTopics() {
		return paramTopics;
	}

	public int[] getQos() {
		int[] qosArray = new int[paramQos.size()];
		for (int i = 0; i < qosArray.length; i++) {
			qosArray[i] = paramQos.get(i);
		}
		return qosArray;
	}

	public List<String> getParamQosStr() {
		return paramQosStr;
	}
     
	@Parameter(name="qosStr", description=SPLDocConstants.MQTTSRC_PARAM_QOS_STR_DESC, optional=true, cardinality=-1)
	public void setParamQosStr(List<String> paramQosStr) {
		
		if(this.paramQos == null) {
			this.paramQos = new ArrayList<Integer>();
		}
		
		for(String aQosList : paramQosStr) {
			String[] qosString = aQosList.split(IMqttConstants.COMMA);
			
			for(String aQos : qosString) {
				try {
					paramQos.add(Integer.parseInt(aQos.trim()));
				} catch (NumberFormatException e) {
					// this should not happen as runtime check should have taken care of invalid number.
				}
			}
		}
		
	}

	@Parameter(name="topicOutAttrName", description=SPLDocConstants.MQTTSRC_PARAM_TOPICATTRNAME_DESC, optional=true)
	public void setTopicOutAttrName(String topicOutAttrName) {
		this.topicOutAttrName = topicOutAttrName;
	}
	
	public String getTopicOutAttrName() {
		return topicOutAttrName;
	}
	
	@Parameter(name="reconnectionBound", description=SPLDocConstants.MQTTSRC_PARAM_RECONN_BOUND_DESC, optional=true)
	public void setReconnectionBound(int reconnectionBound) {
		this.reconnectionBound = reconnectionBound;
	}
	
	@Parameter(name="period", description=SPLDocConstants.MQTTSRC_PARAM_PERIOD_DESC, optional=true)
	public void setPeriod(long period) {
		this.period = period;
	}
	
	public int getReconnectionBound() {
		return reconnectionBound;
	}
	
	public long getPeriod() {
		return period;
	}
	
	public int getMessageQueueSize() {
		return messageQueueSize;
	}
    
	@Parameter(name="messageQueueSize", description=SPLDocConstants.MQTTSRC_PARAM_MESSAGE_SIZE_DESC, optional=true)
	public void setMessageQueueSize(int messageQueueSize) {
		this.messageQueueSize = messageQueueSize;
	}

	@Override
	protected StreamingOutput<OutputTuple> getErrorOutputPort() {
		return getErrorPortFromContext(getOperatorContext());
	}

	private static StreamingOutput<OutputTuple> getErrorPortFromContext(OperatorContext opContext) {
		List<StreamingOutput<OutputTuple>> streamingOutputs = opContext.getStreamingOutputs();
		if (streamingOutputs.size() > 1) {
			return streamingOutputs.get(1);
		}
		return null;
	}
	
}
