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

import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.library.types.PercentType;
import org.eclipse.smarthome.core.thing.binding.ThingHandler;
import org.openhab.binding.ipcamera.handler.IpCameraHandler;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.ReferenceCountUtil;

public class InstarHandler extends ChannelDuplexHandler {
	IpCameraHandler ipCameraHandler;

	public InstarHandler(ThingHandler thingHandler) {
		ipCameraHandler = (IpCameraHandler) thingHandler;
	}

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
}
