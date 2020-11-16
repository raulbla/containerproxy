/**
 * ContainerProxy
 *
 * Copyright (C) 2016-2020 Open Analytics
 *
 * ===========================================================================
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the Apache License as published by
 * The Apache Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * Apache License for more details.
 *
 * You should have received a copy of the Apache License
 * along with this program.  If not, see <http://www.apache.org/licenses/>
 */
package eu.openanalytics.containerproxy.service;

import java.util.HashMap;
import java.util.Map;

import javax.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import eu.openanalytics.containerproxy.backend.IContainerBackend;
import eu.openanalytics.containerproxy.model.runtime.Container;
import eu.openanalytics.containerproxy.model.runtime.Proxy;
import eu.openanalytics.containerproxy.model.runtime.ProxyStatus;
import eu.openanalytics.containerproxy.model.spec.ProxySpec;
import eu.openanalytics.containerproxy.spec.IProxySpecProvider;

@Service
public class SessionPersistenceService {

	protected static final String PROPERTY_PERSISTENCE_SESSIONS = "proxy.persistence_sessions";

	
	private Logger log = LogManager.getLogger(UserService.class);

	@Inject
	private Environment environment;
	
	@Inject
	private IContainerBackend containerBackend;
	
	@Inject
	private IProxySpecProvider proxySpecProvider;
	
	@Inject
	private ProxyService proxyService;
	
	@Inject
	private HeartbeatService heartbeatService;
	
	private boolean isReady = false;

	@EventListener(ApplicationReadyEvent.class)
	public void resumePreviousSessions() throws Exception {
		if (Boolean.valueOf(environment.getProperty("proxy.persistence_sessions", "false"))) {
			log.info("Peristence sessions enabled");

			Map<String, Proxy> proxies = new HashMap();

			for (ExistingContaienrInfo containerInfo: containerBackend.scanExistingContainers()) {				
				if (!proxies.containsKey(containerInfo.getProxyId())) {
					ProxySpec proxySpec = proxySpecProvider.getSpec(containerInfo.getProxySpecId());
					if (proxySpec == null) {
						log.warn(String.format("Found existing container (%s) but not corresponding proxy spec.", containerInfo.getContainerId()));
						continue;
					}
					Proxy proxy = new Proxy();
					proxy.setId(containerInfo.getProxyId());
					proxy.setSpec(proxySpec);
					proxy.setStatus(ProxyStatus.Stopped);
					proxy.setStartupTimestamp(containerInfo.getStartupTimestamp());
					proxy.setUserId(containerInfo.getUserId());
					proxies.put(containerInfo.getProxyId(), proxy);
				} 
				Proxy proxy = proxies.get(containerInfo.getProxyId());
				Container container = new Container();
				container.setId(containerInfo.getContainerId());
				container.setParameters(containerInfo.getParameters());
				container.setSpec(proxy.getSpec().getContainerSpec(containerInfo.getImage()));
				proxy.addContainer(container);
				
				containerBackend.setupPortMappingExistingProxy(proxy, container, containerInfo.getPortBindings());
				
				if (containerInfo.getRunning()) {
					// as soon as one container of the Proxy is running, the Proxy is Up
					// TODO discuss this
					proxy.setStatus(ProxyStatus.Up);
				}
			}

			for (Proxy proxy: proxies.values()) {
				proxyService.addExistingProxy(proxy);
				heartbeatService.heartbeatReceived(proxy.getId());
			}
			
		} else {
			log.info("Peristence sessions disabled");
		}
		
		isReady = true;
	}

	public boolean isReady() {
		return isReady;
	}
	
}
