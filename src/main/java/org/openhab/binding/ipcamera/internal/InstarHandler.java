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

import static org.openhab.binding.ipcamera.IpCameraBindingConstants.CHANNEL_ENABLE_AUDIO_ALARM;
import static org.openhab.binding.ipcamera.IpCameraBindingConstants.CHANNEL_ENABLE_MOTION_ALARM;
import static org.openhab.binding.ipcamera.IpCameraBindingConstants.CHANNEL_THRESHOLD_AUDIO_ALARM;

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
				ipCameraHandler.sendHttpGET(
						"/cgi-bin/hi3510/param.cgi?cmd=setaudioalarmattr&-aa_enable=1&-aa_value=" + command.toString());
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
		}
	}

	// If a camera does not need to poll a request as often as snapshots, it can be
	// added here. Binding steps through the list.
	public ArrayList<String> getLowPriorityRequests() {
		ArrayList<String> lowPriorityRequests = new ArrayList<String>(2);
		// Poll the audio alarm on/off/threshold/...
		lowPriorityRequests.add("/cgi-bin/hi3510/param.cgi?cmd=getaudioalarmattr");
		// Poll the motion alarm on/off/settings/...
		lowPriorityRequests.add("/cgi-bin/hi3510/param.cgi?cmd=getmdattr");
		return lowPriorityRequests;
	}
}
