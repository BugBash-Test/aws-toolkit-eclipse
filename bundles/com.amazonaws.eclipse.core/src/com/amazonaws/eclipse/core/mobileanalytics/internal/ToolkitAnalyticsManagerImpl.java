/*
 * Copyright 2015 Amazon Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 *    http://aws.amazon.com/apache2.0
 *
 * This file is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
 * OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and
 * limitations under the License.
 */
package com.amazonaws.eclipse.core.mobileanalytics.internal;

import static com.amazonaws.eclipse.core.util.ValidationUtils.validateNonNull;

import com.amazonaws.annotation.ThreadSafe;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.eclipse.core.AWSClientFactory;
import com.amazonaws.eclipse.core.AwsToolkitCore;
import com.amazonaws.eclipse.core.mobileanalytics.ToolkitAnalyticsManager;
import com.amazonaws.eclipse.core.mobileanalytics.ToolkitEvent;
import com.amazonaws.eclipse.core.mobileanalytics.ToolkitEvent.ToolkitEventBuilder;
import com.amazonaws.eclipse.core.mobileanalytics.batchclient.MobileAnalyticsBatchClient;
import com.amazonaws.eclipse.core.mobileanalytics.batchclient.internal.MobileAnalyticsBatchClientImpl;
import com.amazonaws.eclipse.core.mobileanalytics.context.ClientContextConfig;
import com.amazonaws.eclipse.core.mobileanalytics.context.ClientContextJsonHelper;
import com.amazonaws.eclipse.core.regions.Region;
import com.amazonaws.eclipse.core.regions.RegionUtils;
import com.amazonaws.services.securitytoken.AWSSecurityTokenService;
import com.amazonaws.services.securitytoken.model.GetCallerIdentityRequest;
import com.fasterxml.jackson.core.JsonProcessingException;

import software.amazon.awssdk.services.toolkittelemetry.model.MetadataEntry;
import software.amazon.awssdk.services.toolkittelemetry.model.MetricDatum;

@ThreadSafe
public class ToolkitAnalyticsManagerImpl implements ToolkitAnalyticsManager {

    private volatile boolean enabled = true;
    private volatile String userId = null;

    /**
     * The low level client for sending PutEvents requests, which also deals
     * with event batching transparently
     */
    private final MobileAnalyticsBatchClient batchClient;

    /**
     * Write access to this field is protected by this manager instance.
     */
    private volatile ToolkitSession currentSession;

    /**
     * @param credentialsProvider
     *            the credentials provider for Mobile Analytics API calls
     * @param clientContextConfig
     *            the client context to be specified in all the PutEvents
     *            requests.
     * @throws JsonProcessingException
     *             if the clientContextConfig fails to be serialized to JSON
     *             format
     */
    public ToolkitAnalyticsManagerImpl(
            AWSCredentialsProvider credentialsProvider,
            ClientContextConfig clientContextConfig)
            throws JsonProcessingException {

        this(new MobileAnalyticsBatchClientImpl(
                credentialsProvider, ClientContextJsonHelper
                .toJsonString(validateNonNull(clientContextConfig,
                        "clientContextConfig"))));
    }

    /**
     * @param batchClient
     *            the client that is responsible for sending the events to
     *            mobile analytics service.
     */
    ToolkitAnalyticsManagerImpl(MobileAnalyticsBatchClient batchClient) {
        this.batchClient = validateNonNull(batchClient, "batchClient");
    }

    @Override
    public synchronized void startSession(boolean forceFlushEvents) {
        if (!this.enabled) {
            return;
        }

        // create a new session with a random id
        ToolkitSession newSession = ToolkitSession.newSession();

        // create a new session.start event
        // make sure the event timestamp is aligned to the startTimestamp of the new session
        ToolkitEvent startSessionEvent = new ToolkitEventBuilder(newSession)
                .setEventType(Constants.SESSION_START_EVENT_TYPE)
                .setTimestamp(newSession.getStartTimestamp())
                .build();
        publishEvent(startSessionEvent);

        this.currentSession = newSession;
        AwsToolkitCore.getDefault().getAccountManager().addAccountInfoChangeListener(this::onAccountInfoChange);

        if (forceFlushEvents) {
            this.batchClient.flush();
        }
    }

    @Override
    public synchronized void endSession(boolean forceFlushEvents) {

        if (!this.enabled) {
            return;
        }

        // Do nothing if we are not in a session
        if (this.currentSession == null) {
            return;
        }

        ToolkitEvent endSessionEvent = new ToolkitEventBuilder(this.currentSession)
                .setEventType(Constants.SESSION_STOP_EVENT_TYPE)
                .build();
        publishEvent(endSessionEvent);

        if (forceFlushEvents) {
            this.batchClient.flush();
        }
    }

    @Override
    public ToolkitEventBuilder eventBuilder() {
        // Start building the event based on the current session
        return new ToolkitEventBuilder(this.currentSession);
    }

    @Override
    public void publishEvent(ToolkitEvent event) {
        if (!this.enabled) {
            return;
        }

        if (event.isValid()) {
        		final MetricDatum metricDatum = event.toMetricDatum();
        	    injectAccountMetadata(metricDatum);
            this.batchClient.putEvent(metricDatum);
        } else {
            AwsToolkitCore.getDefault().logInfo("Discarding invalid analytics event");
        }
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
    
    private void injectAccountMetadata(MetricDatum metricDatum) {
    	final String awsAccount = userId;
        String region = null;
		try {
			Region r = RegionUtils.getCurrentRegion();
			if (r != null) {
				region = r.getId();
			}
			// regionutils throws a runtime exception if it can't determine region, ignore
			// if this happens
		} catch (Exception e) {
			;
		}
		if(region != null) {
		    metricDatum.getMetadata().add(new MetadataEntry().key("awsRegion").value(region));
		}
		if(awsAccount != null) {
			metricDatum.getMetadata().add(new MetadataEntry().key("awsAccount").value(userId));
		}
    }
    
    private void onAccountInfoChange() {
    	try {
    	    final AWSClientFactory clientFactory = AwsToolkitCore.getClientFactory();
    	    AWSSecurityTokenService service = clientFactory.getSTSClient();
    	    userId = service.getCallerIdentity(new GetCallerIdentityRequest()).getAccount();
    	} catch(Exception e) {
    		// wipe out the field if it's enabled
    		userId = null;
    	}
    }
}
