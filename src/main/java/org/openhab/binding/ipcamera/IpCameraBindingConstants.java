/**
 * Copyright (c) 2014,2018 by the respective copyright holders.
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.ipcamera;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.smarthome.core.thing.ThingTypeUID;

/**
 * The {@link IpCameraBindingConstants} class defines common constants, which are
 * used across the whole binding.
 *
 * @author Matthew Skinner - Initial contribution
 */
@NonNullByDefault
public class IpCameraBindingConstants {

    private static final String BINDING_ID = "ipcamera";

    // List of all Thing Type UIDs
    public static final ThingTypeUID THING_TYPE_ONVIF = new ThingTypeUID(BINDING_ID, "ONVIF");
    public static final ThingTypeUID THING_TYPE_AMCREST = new ThingTypeUID(BINDING_ID, "AMCREST");
    public static final ThingTypeUID THING_TYPE_AXIS = new ThingTypeUID(BINDING_ID, "AXIS");
    public static final ThingTypeUID THING_TYPE_FOSCAM = new ThingTypeUID(BINDING_ID, "FOSCAM");

    // List of all Thing Config items
    public static final String CONFIG_IPADDRESS = "IPADDRESS";
    public static final String CONFIG_ONVIF_PORT = "ONVIF_PORT";
    public static final String CONFIG_USERNAME = "USERNAME";
    public static final String CONFIG_PASSWORD = "PASSWORD";
    public static final String CONFIG_ONVIF_PROFILE_NUMBER = "ONVIF_MEDIA_PROFILE";
    public static final String CONFIG_CHECK_STATUS_DELAY = "CHECK_STATUS_DELAY";

    // List of all Channel ids
    public static final String CHANNEL_1 = "testbutton";
    public static final String CHANNEL_IMAGE = "image";
    public static final String CHANNEL_VIDEO_URL = "videourl";
    public static final String CHANNEL_IMAGE_URL = "imageurl";
    public static final String CHANNEL_ABSOLUTE_PAN = "pan";
    public static final String CHANNEL_ABSOLUTE_TILT = "tilt";
    public static final String CHANNEL_ABSOLUTE_ZOOM = "zoom";
    public static final String CHANNEL_MOTION_ALARM = "motionAlarm";
    public static final String CHANNEL_AUDIO_ALARM = "audioAlarm";

}
