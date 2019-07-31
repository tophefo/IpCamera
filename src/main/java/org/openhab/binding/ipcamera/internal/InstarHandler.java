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

package org.openhab.binding.ipcamera.internal;

import static org.openhab.binding.ipcamera.IpCameraBindingConstants.*;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;

import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.library.types.PercentType;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.binding.ThingHandler;
import org.eclipse.smarthome.core.types.Command;
import org.openhab.binding.ipcamera.handler.IpCameraHandler;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.ReferenceCountUtil;

public class InstarHandler extends ChannelDuplexHandler {
    IpCameraHandler ipCameraHandler;

    public InstarHandler(ThingHandler thingHandler) {
        ipCameraHandler = (IpCameraHandler) thingHandler;
    }

    // This handles the incoming http replies back from the camera.
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        String content = null;
        try {
            content = msg.toString();

            if (!content.isEmpty()) {
                ipCameraHandler.logger.trace("HTTP Result back from camera is \t:{}:", content);
            }

            // Audio Alarm
            String aa_enable = ipCameraHandler.searchString(content, "var aa_enable = \"");
            if ("1".equals(aa_enable)) {
                ipCameraHandler.setChannelState(CHANNEL_ENABLE_AUDIO_ALARM, OnOffType.valueOf("ON"));
                String aa_value = ipCameraHandler.searchString(content, "var aa_value = \"");
                // String aa_time = searchString(content, "var aa_time = \"");
                if (!aa_value.isEmpty()) {
                    ipCameraHandler.logger.debug("Threshold is changing to {}", aa_value);
                    ipCameraHandler.setChannelState(CHANNEL_THRESHOLD_AUDIO_ALARM, PercentType.valueOf(aa_value));
                }
            } else if ("0".equals(aa_enable)) {
                ipCameraHandler.setChannelState(CHANNEL_ENABLE_AUDIO_ALARM, OnOffType.valueOf("OFF"));
            }

            // Motion Alarm
            String m1_enable = ipCameraHandler.searchString(content, "var m1_enable=\"");
            if ("1".equals(m1_enable)) {
                ipCameraHandler.setChannelState(CHANNEL_ENABLE_MOTION_ALARM, OnOffType.valueOf("ON"));
            } else if ("0".equals(m1_enable)) {
                ipCameraHandler.setChannelState(CHANNEL_ENABLE_MOTION_ALARM, OnOffType.valueOf("OFF"));
            }

        } finally {
            ReferenceCountUtil.release(msg);
            content = null;
        }
    }

    public String encodeSpecialChars(String text) {
        String Processed = null;
        try {
            Processed = URLEncoder.encode(text, "UTF-8").replace("+", "%20");
        } catch (UnsupportedEncodingException e) {

        }
        return Processed;
    }

    // This handles the commands that come from the Openhab event bus.
    public void handleCommand(ChannelUID channelUID, Command command) {
        if (command.toString() == "REFRESH") {
            return;
        } // end of "REFRESH"
        switch (channelUID.getId()) {
            case CHANNEL_THRESHOLD_AUDIO_ALARM:
                int value = Math.round(Float.valueOf(command.toString()));
                if (value == 0) {
                    ipCameraHandler.sendHttpGET("/cgi-bin/hi3510/param.cgi?cmd=setaudioalarmattr&-aa_enable=0");
                } else {
                    ipCameraHandler.sendHttpGET("/cgi-bin/hi3510/param.cgi?cmd=setaudioalarmattr&-aa_enable=1");
                    ipCameraHandler
                            .sendHttpGET("/cgi-bin/hi3510/param.cgi?cmd=setaudioalarmattr&-aa_enable=1&-aa_value="
                                    + command.toString());
                }
                return;
            case CHANNEL_ENABLE_AUDIO_ALARM:
                if ("ON".equals(command.toString())) {
                    ipCameraHandler.sendHttpGET("/cgi-bin/hi3510/param.cgi?cmd=setaudioalarmattr&-aa_enable=1");
                } else {
                    ipCameraHandler.sendHttpGET("/cgi-bin/hi3510/param.cgi?cmd=setaudioalarmattr&-aa_enable=0");
                }
                return;
            case CHANNEL_ENABLE_MOTION_ALARM:
                if ("ON".equals(command.toString())) {
                    ipCameraHandler.sendHttpGET(
                            "/cgi-bin/hi3510/param.cgi?cmd=setmdattr&-enable=1&-name=1&cmd=setmdattr&-enable=1&-name=2&cmd=setmdattr&-enable=1&-name=3&cmd=setmdattr&-enable=1&-name=4");
                } else {
                    ipCameraHandler.sendHttpGET(
                            "/cgi-bin/hi3510/param.cgi?cmd=setmdattr&-enable=0&-name=1&cmd=setmdattr&-enable=0&-name=2&cmd=setmdattr&-enable=0&-name=3&cmd=setmdattr&-enable=0&-name=4");
                }
                return;
            case CHANNEL_TEXT_OVERLAY:
                String text = encodeSpecialChars(command.toString());
                if ("".contentEquals(text)) {
                    ipCameraHandler.sendHttpGET("/param.cgi?cmd=setoverlayattr&-region=1&-show=0");
                } else {
                    ipCameraHandler.sendHttpGET("/param.cgi?cmd=setoverlayattr&-region=1&-show=1&-name=" + text);
                }
                return;
            case CHANNEL_AUTO_LED:
                if ("ON".equals(command.toString())) {
                    ipCameraHandler.sendHttpGET("/param.cgi?cmd=setinfrared&-infraredstat=auto");
                } else {
                    ipCameraHandler.sendHttpGET("/param.cgi?cmd=setinfrared&-infraredstat=close");
                }
                return;
        }
    }

    public void alarmTriggers(String alarm) {
        ipCameraHandler.logger.info("Alarm has been triggered:{}", alarm);
    }

    // If a camera does not need to poll a request as often as snapshots, it can be
    // added here. Binding steps through the list.
    public ArrayList<String> getLowPriorityRequests() {
        ArrayList<String> lowPriorityRequests = new ArrayList<String>(2);
        // Poll the audio alarm on/off/threshold/...
        lowPriorityRequests.add("/cgi-bin/hi3510/param.cgi?cmd=getaudioalarmattr");
        // Poll the motion alarm on/off/settings/...
        lowPriorityRequests.add("/cgi-bin/hi3510/param.cgi?cmd=getmdattr");

        // param.cgi?cmd=getinfrared
        // param.cgi?cmd=getserverinfo
        // Setup alarm server, tested.
        // http://192.168.1.65/param.cgi?cmd=setalarmserverattr&-as_index=3&-as_server=192.168.1.80&-as_port=30065&-as_path=/instar&-as_activequery=1&-as_area=1&-as_io=1&-as_areaio=1&-as_audio=1
        return lowPriorityRequests;
    }
}
