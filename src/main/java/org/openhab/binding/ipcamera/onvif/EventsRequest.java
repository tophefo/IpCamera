/**
 * Copyright (c) 2010-2019 Contributors to the openHAB project
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
package org.openhab.binding.ipcamera.onvif;

import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.core.thing.binding.ThingHandler;

import be.teletask.onvif.models.OnvifMediaProfile;
import be.teletask.onvif.models.OnvifType;
import be.teletask.onvif.requests.OnvifRequest;

/**
 * The {@link EventsRequest} is responsible for handling onvif events and alarms.
 *
 * @author Matthew Skinner - Initial contribution
 */

public class EventsRequest implements OnvifRequest {

    String profileToken = "1";
    String requestType = "GetConfigurations";
    // private IpCameraHandler thisCamera;

    public EventsRequest(String requestType, OnvifMediaProfile onvifMediaProfile) {
        this.requestType = requestType;
        profileToken = onvifMediaProfile.getToken();
    }

    public EventsRequest(String string, OnvifMediaProfile onvifMediaProfile, @Nullable ThingHandler handler) {
        this(string, onvifMediaProfile);
        // thisCamera = (IpCameraHandler) handler;
    }

    @Override
    public String getXml() {
        switch (requestType) {
            case "GetEventProperties": // Note: this is not yet tested but it should be right.
                return "<GetEventProperties xmlns=\"http://www.onvif.org/ver10/events/wsdl\"></GetEventProperties>";
            // Need to implement the two below before onvif events can be polled and parsed.
            // CreatePullPointSubscription
            // PullMessagesRequest
        }
        return "notfound";
    }

    @Override
    public OnvifType getType() {
        return OnvifType.CUSTOM;
    }

    public String getParsedResult(String result) {
        return "notImplementedYet";
    }
}
