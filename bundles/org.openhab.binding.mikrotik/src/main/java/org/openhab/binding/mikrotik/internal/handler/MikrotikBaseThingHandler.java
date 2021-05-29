/**
 * Copyright (c) 2010-2021 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.mikrotik.internal.handler;

import static org.openhab.core.thing.ThingStatus.*;
import static org.openhab.core.thing.ThingStatusDetail.CONFIGURATION_ERROR;
import static org.openhab.core.types.RefreshType.REFRESH;

import java.lang.reflect.ParameterizedType;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.mikrotik.internal.config.ConfigValidation;
import org.openhab.binding.mikrotik.internal.model.RouterosDevice;
import org.openhab.core.thing.*;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.thing.binding.builder.ThingStatusInfoBuilder;
import org.openhab.core.types.Command;
import org.openhab.core.types.State;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link MikrotikBaseThingHandler} is a base class for all other RouterOS things of map-value nature.
 * It is responsible for handling commands, which are sent to one of the channels and emit channel updates
 * whenever required.
 *
 * @author Oleg Vivtash - Initial contribution
 *
 *
 * @param <C> config - the config class used by this base thing handler
 *
 */
@NonNullByDefault
public abstract class MikrotikBaseThingHandler<C extends ConfigValidation> extends BaseThingHandler {
    private final Logger logger = LoggerFactory.getLogger(MikrotikBaseThingHandler.class);
    protected @Nullable C config;
    private @Nullable ScheduledFuture<?> refreshJob;
    protected LocalDateTime lastModelsRefresh = LocalDateTime.now();
    protected Map<String, State> currentState = new HashMap<>();

    // public static boolean supportsThingType(ThingTypeUID thingTypeUID) <- in subclasses

    public MikrotikBaseThingHandler(Thing thing) {
        super(thing);
    }

    protected @Nullable MikrotikRouterosBridgeHandler getVerifiedBridgeHandler() {
        @Nullable
        Bridge bridgeRef = getBridge();
        if (bridgeRef != null && bridgeRef.getHandler() != null
                && (bridgeRef.getHandler() instanceof MikrotikRouterosBridgeHandler)) {
            return (MikrotikRouterosBridgeHandler) bridgeRef.getHandler();
        }
        return null;
    }

    protected final @Nullable RouterosDevice getRouteros() {
        @Nullable
        MikrotikRouterosBridgeHandler bridgeHandler = getVerifiedBridgeHandler();
        return bridgeHandler == null ? null : bridgeHandler.getRouteros();
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        logger.debug("Handling command = {} for channel = {}", command, channelUID);
        if (getThing().getStatus() == ONLINE) {
            RouterosDevice routeros = getRouteros();
            if (routeros != null) {
                if (command == REFRESH) {
                    throttledRefreshModels();
                    refreshChannel(channelUID);
                } else {
                    try {
                        executeCommand(channelUID, command);
                    } catch (Exception e) {
                        logger.warn("Unexpected error handling command = {} for channel = {} : {}", command, channelUID,
                                e.getMessage());
                    }
                }
            }
        }
    }

    @Override
    public void initialize() {
        logger.debug("Start initializing {}!", getThing().getUID());
        cancelRefreshJob();
        if (getVerifiedBridgeHandler() == null) {
            updateStatus(OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "This thing requires a RouterOS bridge");
            return;
        }

        Class<?> klass = (Class<?>) (((ParameterizedType) getClass().getGenericSuperclass())
                .getActualTypeArguments()[0]);
        this.config = (C) getConfigAs(klass);
        logger.trace("Config for for {} ({}) is {}", getThing().getUID(), getThing().getStatus(), config);

        if (!config.isValid()) {
            updateStatus(OFFLINE, CONFIGURATION_ERROR, String.format("%s is invalid", klass.getSimpleName()));
            return;
        }

        updateStatus(ONLINE);
        logger.debug("Finished initializing {}!", getThing().getUID());
    }

    @Override
    protected void updateStatus(ThingStatus status, ThingStatusDetail statusDetail, @Nullable String description) {
        logger.trace("Updating {} status to {} / {}", getThing().getUID(), status, statusDetail);
        if (status == ONLINE || (status == OFFLINE && statusDetail == ThingStatusDetail.COMMUNICATION_ERROR)) {
            scheduleRefreshJob();
        } else if (status == OFFLINE
                && (statusDetail == ThingStatusDetail.CONFIGURATION_ERROR || statusDetail == ThingStatusDetail.GONE)) {
            cancelRefreshJob();
        }

        // update the status only if it's changed
        ThingStatusInfo statusInfo = ThingStatusInfoBuilder.create(status, statusDetail).withDescription(description)
                .build();
        if (!statusInfo.equals(getThing().getStatusInfo())) {
            super.updateStatus(status, statusDetail, description);
        }
    }

    private void scheduleRefreshJob() {
        synchronized (this) {
            if (refreshJob == null) {
                int refreshPeriod = getVerifiedBridgeHandler().getBridgeConfig().refresh;
                logger.debug("Scheduling refresh job every {}s", refreshPeriod);
                refreshJob = scheduler.scheduleWithFixedDelay(this::scheduledRun, refreshPeriod, refreshPeriod,
                        TimeUnit.SECONDS);
            }
        }
    }

    private void cancelRefreshJob() {
        synchronized (this) {
            if (refreshJob != null) {
                logger.debug("Cancelling refresh job");
                refreshJob.cancel(true);
                refreshJob = null;
            }
        }
    }

    private void scheduledRun() {
        logger.trace("scheduledRun() called for {}", getThing().getUID());
        try {
            if (getVerifiedBridgeHandler() == null) {
                updateStatus(OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE, "Failed reaching out to RouterOS bridge");
                return;
            }
            if (getBridge() != null && getBridge().getStatus() == OFFLINE) {
                updateStatus(OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE, "The RouterOS bridge is currently offline");
                return;
            }

            if (getThing().getStatus() != ONLINE)
                updateStatus(ONLINE);
            logger.debug("Refreshing all {} channels", getThing().getUID());
            for (Channel channel : getThing().getChannels()) {
                refreshChannel(channel.getUID());
            }
        } catch (Exception e) {
            logger.warn("Unhandled exception while refreshing the {} Mikrotik thing", getThing().getUID(), e);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
        }
    }

    protected final void refresh() throws ChannelUpdateException {
        if (getThing().getStatus() == ONLINE) {
            if (getRouteros() != null) {
                throttledRefreshModels();
                for (Channel channel : getThing().getChannels()) {
                    ChannelUID channelUID = channel.getUID();
                    try {
                        refreshChannel(channelUID);
                    } catch (RuntimeException e) {
                        throw new ChannelUpdateException(getThing().getUID(), channelUID, e);
                    }
                }
            }
        }
    }

    protected void throttledRefreshModels() {
        MikrotikRouterosBridgeHandler bridgeHandler = (MikrotikRouterosBridgeHandler) getBridge().getHandler();
        if (LocalDateTime.now().isAfter(lastModelsRefresh.plusSeconds(bridgeHandler.getBridgeConfig().refresh))) {
            lastModelsRefresh = LocalDateTime.now();
            if (getRouteros() != null && config != null) {
                refreshModels();
            } else {
                logger.trace("getRouteros() || config is null, skipping {}.refreshModels()",
                        getClass().getSimpleName());
            }
        }
    }

    @Override
    public void dispose() {
        logger.debug("Disposing Mikrotik Thing {}", getThing().getUID());
        cancelRefreshJob();
    }

    protected abstract void refreshModels();

    protected abstract void refreshChannel(ChannelUID channelUID);

    protected abstract void executeCommand(ChannelUID channelUID, Command command);
}
