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

import static org.openhab.binding.ipcamera.IpCameraBindingConstants.CHANNEL_AUDIO_ALARM;
import static org.openhab.binding.ipcamera.IpCameraBindingConstants.CHANNEL_ENABLE_AUDIO_ALARM;
import static org.openhab.binding.ipcamera.IpCameraBindingConstants.CHANNEL_ENABLE_LED;
import static org.openhab.binding.ipcamera.IpCameraBindingConstants.CHANNEL_ENABLE_MOTION_ALARM;
import static org.openhab.binding.ipcamera.IpCameraBindingConstants.CHANNEL_MOTION_ALARM;
import static org.openhab.binding.ipcamera.IpCameraBindingConstants.CHANNEL_THRESHOLD_AUDIO_ALARM;

import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.library.types.PercentType;
import org.eclipse.smarthome.core.thing.binding.ThingHandler;
import org.openhab.binding.ipcamera.handler.IpCameraHandler;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.ReferenceCountUtil;

public class FoscamHandler extends ChannelDuplexHandler {
	IpCameraHandler ipCameraHandler;

	public FoscamHandler(ThingHandler handler) {
		ipCameraHandler = (IpCameraHandler) handler;
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		String content = null;
		try {
			content = msg.toString();
			if (!content.isEmpty()) {
				ipCameraHandler.logger.trace("HTTP Result back from camera is \t:{}:", content);
			}

			////////////// Motion Alarm //////////////
			if (content.contains("<motionDetectAlarm>")) {
				if (content.contains("<motionDetectAlarm>0</motionDetectAlarm>")) {
					ipCameraHandler.setChannelState(CHANNEL_ENABLE_MOTION_ALARM, OnOffType.valueOf("OFF"));
				} else if (content.contains("<motionDetectAlarm>1</motionDetectAlarm>")) { // Enabled but no alarm
					ipCameraHandler.setChannelState(CHANNEL_ENABLE_MOTION_ALARM, OnOffType.valueOf("ON"));
					ipCameraHandler.setChannelState(CHANNEL_MOTION_ALARM, OnOffType.valueOf("OFF"));
					ipCameraHandler.firstMotionAlarm = false;
					ipCameraHandler.motionAlarmUpdateSnapshot = false;
				} else if (content.contains("<motionDetectAlarm>2</motionDetectAlarm>")) {// Enabled, alarm on
					ipCameraHandler.setChannelState(CHANNEL_ENABLE_MOTION_ALARM, OnOffType.valueOf("ON"));
					ipCameraHandler.motionDetected(CHANNEL_MOTION_ALARM);
				}
			}

			////////////// Sound Alarm //////////////
			if (content.contains("<soundAlarm>0</soundAlarm>")) {
				ipCameraHandler.setChannelState(CHANNEL_ENABLE_AUDIO_ALARM, OnOffType.valueOf("OFF"));
				ipCameraHandler.setChannelState(CHANNEL_AUDIO_ALARM, OnOffType.valueOf("OFF"));
			}
			if (content.contains("<soundAlarm>1</soundAlarm>")) {
				ipCameraHandler.setChannelState(CHANNEL_ENABLE_AUDIO_ALARM, OnOffType.valueOf("ON"));
				ipCameraHandler.setChannelState(CHANNEL_AUDIO_ALARM, OnOffType.valueOf("OFF"));
				ipCameraHandler.firstAudioAlarm = false;
				ipCameraHandler.audioAlarmUpdateSnapshot = false;
			}
			if (content.contains("<soundAlarm>2</soundAlarm>")) {
				ipCameraHandler.setChannelState(CHANNEL_ENABLE_AUDIO_ALARM, OnOffType.valueOf("ON"));
				ipCameraHandler.audioDetected();
			}

			////////////// Sound Threshold //////////////
			if (content.contains("<sensitivity>0</sensitivity>")) {
				ipCameraHandler.setChannelState(CHANNEL_THRESHOLD_AUDIO_ALARM, PercentType.valueOf("0"));
			}
			if (content.contains("<sensitivity>1</sensitivity>")) {
				ipCameraHandler.setChannelState(CHANNEL_THRESHOLD_AUDIO_ALARM, PercentType.valueOf("50"));
			}
			if (content.contains("<sensitivity>2</sensitivity>")) {
				ipCameraHandler.setChannelState(CHANNEL_THRESHOLD_AUDIO_ALARM, PercentType.valueOf("100"));
			}

			//////////////// Infrared LED /////////////////////
			if (content.contains("<infraLedState>0</infraLedState>")) {
				ipCameraHandler.setChannelState(CHANNEL_ENABLE_LED, OnOffType.valueOf("OFF"));
			}
			if (content.contains("<infraLedState>1</infraLedState>")) {
				ipCameraHandler.setChannelState(CHANNEL_ENABLE_LED, OnOffType.valueOf("ON"));
			}

			if (content.contains("</CGI_Result>")) {
				ctx.close();
				ipCameraHandler.logger.debug("End of FOSCAM handler reached, so closing the channel to the camera now");
			}

		} finally {
			ReferenceCountUtil.release(msg);
			content = null;
		}
	}
}