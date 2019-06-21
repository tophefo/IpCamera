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

public class AmcrestHandler extends ChannelDuplexHandler {
	private String requestUrl = "Empty";
	IpCameraHandler ipCameraHandler;

	public AmcrestHandler(ThingHandler handler) {
		ipCameraHandler = (IpCameraHandler) handler;
	}

	public void setURL(String url) {
		requestUrl = url;
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		try {
			String content = msg.toString();

			if (!content.isEmpty()) {
				ipCameraHandler.logger.trace("HTTP Result back from camera is \t:{}:", content);
			}
			if (content.contains("Error: No Events")) {
				if ("/cgi-bin/eventManager.cgi?action=getEventIndexes&code=VideoMotion".equals(requestUrl)) {
					ipCameraHandler.setChannelState(CHANNEL_MOTION_ALARM, OnOffType.valueOf("OFF"));
					ipCameraHandler.firstMotionAlarm = false;
					ipCameraHandler.motionAlarmUpdateSnapshot = false;
				} else if ("/cgi-bin/eventManager.cgi?action=getEventIndexes&code=AudioMutation".equals(requestUrl)) {
					ipCameraHandler.setChannelState(CHANNEL_AUDIO_ALARM, OnOffType.valueOf("OFF"));
					ipCameraHandler.firstAudioAlarm = false;
					ipCameraHandler.audioAlarmUpdateSnapshot = false;
				}
			} else if (content.contains("channels[0]=0")) {
				if ("/cgi-bin/eventManager.cgi?action=getEventIndexes&code=VideoMotion".equals(requestUrl)) {
					ipCameraHandler.motionDetected(CHANNEL_MOTION_ALARM);
				} else if ("/cgi-bin/eventManager.cgi?action=getEventIndexes&code=AudioMutation".equals(requestUrl)) {
					ipCameraHandler.audioDetected();
				}
			}

			if (content.contains("table.MotionDetect[0].Enable=false")) {
				ipCameraHandler.setChannelState(CHANNEL_ENABLE_MOTION_ALARM, OnOffType.valueOf("OFF"));
			} else if (content.contains("table.MotionDetect[0].Enable=true")) {
				ipCameraHandler.setChannelState(CHANNEL_ENABLE_MOTION_ALARM, OnOffType.valueOf("ON"));
			}
			// determine if the audio alarm is turned on or off.
			if (content.contains("table.AudioDetect[0].MutationDetect=true")) {
				ipCameraHandler.setChannelState(CHANNEL_ENABLE_AUDIO_ALARM, OnOffType.valueOf("ON"));
			} else if (content.contains("table.AudioDetect[0].MutationDetect=false")) {
				ipCameraHandler.setChannelState(CHANNEL_ENABLE_AUDIO_ALARM, OnOffType.valueOf("OFF"));
			}
			// Handle AudioMutationThreshold alarm
			if (content.contains("table.AudioDetect[0].MutationThreold=")) {
				String value = ipCameraHandler.returnValueFromString(content, "table.AudioDetect[0].MutationThreold=");
				ipCameraHandler.setChannelState(CHANNEL_THRESHOLD_AUDIO_ALARM, PercentType.valueOf(value));
			}

		} finally {
			ReferenceCountUtil.release(msg);
			ctx.close();
		}
	}
}
