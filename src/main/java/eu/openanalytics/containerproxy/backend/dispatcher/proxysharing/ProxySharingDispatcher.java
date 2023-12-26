/**
 * ContainerProxy
 *
 * Copyright (C) 2016-2023 Open Analytics
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
package eu.openanalytics.containerproxy.backend.dispatcher.proxysharing;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import eu.openanalytics.containerproxy.ContainerProxyException;
import eu.openanalytics.containerproxy.ProxyFailedToStartException;
import eu.openanalytics.containerproxy.backend.dispatcher.IProxyDispatcher;
import eu.openanalytics.containerproxy.backend.dispatcher.proxysharing.store.ISeatStore;
import eu.openanalytics.containerproxy.event.PendingProxyEvent;
import eu.openanalytics.containerproxy.event.SeatAvailableEvent;
import eu.openanalytics.containerproxy.event.SeatClaimedEvent;
import eu.openanalytics.containerproxy.event.SeatReleasedEvent;
import eu.openanalytics.containerproxy.model.runtime.Container;
import eu.openanalytics.containerproxy.model.runtime.Proxy;
import eu.openanalytics.containerproxy.model.runtime.ProxyStartupLog;
import eu.openanalytics.containerproxy.model.runtime.ProxyStatus;
import eu.openanalytics.containerproxy.model.runtime.ProxyStopReason;
import eu.openanalytics.containerproxy.model.runtime.runtimevalues.PublicPathKey;
import eu.openanalytics.containerproxy.model.runtime.runtimevalues.RuntimeValue;
import eu.openanalytics.containerproxy.model.runtime.runtimevalues.RuntimeValueKeyRegistry;
import eu.openanalytics.containerproxy.model.runtime.runtimevalues.TargetIdKey;
import eu.openanalytics.containerproxy.model.spec.ProxySpec;
import eu.openanalytics.containerproxy.model.store.IProxyStore;
import eu.openanalytics.containerproxy.service.StructuredLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.security.core.Authentication;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static net.logstash.logback.argument.StructuredArguments.kv;

public class ProxySharingDispatcher implements IProxyDispatcher {

    private ProxySharingMicrometer proxySharingMicrometer = null;
    private final ProxySpec proxySpec;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final IDelegateProxyStore delegateProxyStore;
    private final ISeatStore seatStore;
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final StructuredLogger slogger = new StructuredLogger(logger);
    private final IProxyStore proxyStore;
    private final Cache<String, CompletableFuture<Void>> pendingDelegatingProxies;

    static {
        RuntimeValueKeyRegistry.addRuntimeValueKey(SeatIdKey.inst);
        RuntimeValueKeyRegistry.addRuntimeValueKey(DelegateProxyKey.inst);
    }

    public ProxySharingDispatcher(ProxySpec proxySpec, IDelegateProxyStore delegateProxyStore,
                                  ISeatStore seatStore,
                                  ApplicationEventPublisher applicationEventPublisher,
                                  IProxyStore proxyStore) {
        this.proxySpec = proxySpec;
        this.delegateProxyStore = delegateProxyStore;
        this.seatStore = seatStore;
        this.applicationEventPublisher = applicationEventPublisher;
        this.proxyStore = proxyStore;
        pendingDelegatingProxies = Caffeine
            .newBuilder()
            .expireAfterWrite(10, TimeUnit.MINUTES) // TODO consider configurable timeout
            .build();
    }

    public static boolean supportSpec(ProxySpec proxySpec) {
        return proxySpec.getSpecExtension(ProxySharingSpecExtension.class).minimumSeatsAvailable != null;
    }

    public Seat claimSeat(String claimingProxyId) {
        return seatStore.claimSeat(claimingProxyId).orElse(null);
    }

    @Override
    public Proxy startProxy(Authentication user, Proxy proxy, ProxySpec spec, ProxyStartupLog.ProxyStartupLogBuilder proxyStartupLogBuilder) throws ProxyFailedToStartException {
        proxyStartupLogBuilder.startingApplication();
        LocalDateTime startTime = LocalDateTime.now();
        Seat seat = claimSeat(proxy.getId());
        if (seat == null) {
            slogger.info(proxy,"Seat not immediately available");
            CompletableFuture<Void> future = new CompletableFuture<>();
            pendingDelegatingProxies.put(proxy.getId(), future);

            // trigger scale-up in scaler (possibly on different replica)
            applicationEventPublisher.publishEvent(new PendingProxyEvent(proxySpec.getId(), proxy.getId()));

            // no seat available, wait until one becomes available
            for (int i = 0; i < 600; i++) { // TODO make limit configurable?
                try {
                    future.get(3, TimeUnit.SECONDS);
                } catch (InterruptedException | ExecutionException e) {
                    throw new RuntimeException(e);
                } catch (CancellationException e) {
                    // proxy was stopped, do not claim a seat, just return existing object
                    return proxy;
                } catch (TimeoutException e) {
                    // timeout reached, try to claim anyway in case some event was missed
                }
                if (proxyWasStopped(proxy)) {
                    // proxy was stopped, do not claim a seat, just return existing object
                    return proxy;
                }
                seat = claimSeat(proxy.getId());
                if (seat != null) {
                    slogger.info(proxy,"Seat available attempt: " + i);
                    break;
                }
            }
            if (seat == null) {
                throw new ProxyFailedToStartException("Could not claim a seat", null, proxy);
            }
        }
        info(proxy, seat, "Seat claimed");
        applicationEventPublisher.publishEvent(new SeatClaimedEvent(spec.getId(), proxy.getId()));
        LocalDateTime endTime = LocalDateTime.now();
        if (proxySharingMicrometer != null) {
            proxySharingMicrometer.registerSeatWaitTime(spec.getId(), Duration.between(startTime, endTime));
        }

        // TODO NPE
        Proxy delegateProxy = delegateProxyStore.getDelegateProxy(seat.getDelegateProxyId()).getProxy();

        Proxy.ProxyBuilder resultProxy = proxy.toBuilder();
        resultProxy.targetId(delegateProxy.getId());
        resultProxy.addTargets(delegateProxy.getTargets());
        String publicPath = proxy.getRuntimeObjectOrNull(PublicPathKey.inst);
        if (publicPath != null) {
            resultProxy.addRuntimeValue(new RuntimeValue(PublicPathKey.inst, publicPath.replaceAll(proxy.getId(), delegateProxy.getId())), true);
        }
        resultProxy.addRuntimeValue(new RuntimeValue(TargetIdKey.inst, delegateProxy.getId()), true);
        resultProxy.addRuntimeValue(new RuntimeValue(SeatIdKey.inst, seat.getId()), true);

        Container resultContainer = proxy.getContainer(0).toBuilder().id(UUID.randomUUID().toString()).build();
        resultProxy.updateContainer(resultContainer);

        return resultProxy.build();
    }

    @Override
    public void stopProxy(Proxy proxy, ProxyStopReason proxyStopReason) throws ContainerProxyException {
        String seatId = proxy.getRuntimeObjectOrNull(SeatIdKey.inst);
        if (seatId != null) {
            seatStore.releaseSeat(seatId);
            info(proxy, seatStore.getSeat(seatId), "Seat released");
            applicationEventPublisher.publishEvent(new SeatReleasedEvent(proxy.getSpecId(), seatId, proxy.getId(), proxyStopReason));
        }

        // if proxy is still starting, cancel the future
        CompletableFuture<Void> future = pendingDelegatingProxies.getIfPresent(proxy.getId());
        if (future == null) {
            return;
        }
        pendingDelegatingProxies.invalidate(proxy.getId());
        future.cancel(true);
    }

    @Override
    public void pauseProxy(Proxy proxy) {
        throw new IllegalStateException("Not available"); // TODO
    }

    @Override
    public Proxy resumeProxy(Authentication user, Proxy proxy, ProxySpec proxySpec) throws ProxyFailedToStartException {
        throw new IllegalStateException("Not available"); // TODO
    }

    @Override
    public boolean supportsPause() {
        return false;
    }

    @Override
    public Proxy addRuntimeValuesBeforeSpel(Authentication user, ProxySpec spec, Proxy proxy) {
        return proxy; // TODO
    }

    @EventListener
    public void onSeatAvailableEvent(SeatAvailableEvent event) {
        if (!Objects.equals(event.getSpecId(), proxySpec.getId())) {
            // only handle events for this spec
            return;
        }
        slogger.info(null, String.format("Received SeatAvailableEvent: %s %s", event.getIntendedProxyId(), event.getSpecId()));
        CompletableFuture<Void> future = pendingDelegatingProxies.getIfPresent(event.getIntendedProxyId());
        if (future == null) {
            return;
        }
        pendingDelegatingProxies.invalidate(event.getIntendedProxyId());
        future.complete(null);
    }

    public Long getNumUnclaimedSeats() {
        return seatStore.getNumUnclaimedSeats();
    }

    public Long getNumClaimedSeats() {
        return seatStore.getNumClaimedSeats();
    }

    public void setProxySharingMicrometer(ProxySharingMicrometer proxySharingMicrometer) {
        this.proxySharingMicrometer = proxySharingMicrometer;
    }

    public ProxySpec getSpec() {
        return proxySpec;
    }

    private void info(Proxy proxy, Seat seat, String message) {
        logger.info("[{} {} {} {} {}] " + message, kv("user", proxy.getUserId()), kv("proxyId", proxy.getId()), kv("specId", proxy.getSpecId()), kv("delegateProxyId", seat.getDelegateProxyId()), kv("seatId", seat.getId()));
    }

    private boolean proxyWasStopped(Proxy startingProxy) {
        // fetch proxy from proxyStore in order to check if it was stopped
        Proxy proxy = proxyStore.getProxy(startingProxy.getId());
        if (proxy == null || proxy.getStatus().equals(ProxyStatus.Stopped)
            || proxy.getStatus().equals(ProxyStatus.Stopping)) {
            return true;
        }
        return false;
    }

}
