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
import static org.openhab.binding.ipcamera.IpCameraBindingConstants.CHANNEL_EXTERNAL_ALARM_INPUT;
import static org.openhab.binding.ipcamera.IpCameraBindingConstants.CHANNEL_EXTERNAL_ALARM_INPUT2;
import static org.openhab.binding.ipcamera.IpCameraBindingConstants.CHANNEL_FACE_DETECTED;
import static org.openhab.binding.ipcamera.IpCameraBindingConstants.CHANNEL_FIELD_DETECTION_ALARM;
import static org.openhab.binding.ipcamera.IpCameraBindingConstants.CHANNEL_ITEM_LEFT;
import static org.openhab.binding.ipcamera.IpCameraBindingConstants.CHANNEL_ITEM_TAKEN;
import static org.openhab.binding.ipcamera.IpCameraBindingConstants.CHANNEL_LINE_CROSSING_ALARM;
import static org.openhab.binding.ipcamera.IpCameraBindingConstants.CHANNEL_MOTION_ALARM;
import static org.openhab.binding.ipcamera.IpCameraBindingConstants.CHANNEL_PARKING_ALARM;
import static org.openhab.binding.ipcamera.IpCameraBindingConstants.CHANNEL_THRESHOLD_AUDIO_ALARM;

import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.library.types.PercentType;
import org.eclipse.smarthome.core.thing.binding.ThingHandler;
import org.openhab.binding.ipcamera.handler.IpCameraHandler;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.ReferenceCountUtil;

public class DahuaHandler extends ChannelDuplexHandler {
	IpCameraHandler ipCameraHandler;
	String nvrChannel;

	public DahuaHandler(ThingHandler handler, String nvrChannel) {
		ipCameraHandler = (IpCameraHandler) handler;
		this.nvrChannel = nvrChannel;
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		String content = null;
		try {
			content = msg.toString();
			if (!content.isEmpty()) {
				ipCameraHandler.logger.trace("HTTP Result back from camera is \t:{}:", content);
			}
			// determine if the motion detection is turned on or off.
			if (content.contains("table.MotionDetect[0].Enable=true")) {
				ipCameraHandler.setChannelState(CHANNEL_ENABLE_MOTION_ALARM, OnOffType.valueOf("ON"));
			} else if (content.contains("table.MotionDetect[" + nvrChannel + "].Enable=false")) {
				ipCameraHandler.setChannelState(CHANNEL_ENABLE_MOTION_ALARM, OnOffType.valueOf("OFF"));
			}
			// Handle motion alarm
			if (content.contains("Code=VideoMotion;action=Start;index=0")) {
				ipCameraHandler.motionDetected(CHANNEL_MOTION_ALARM);
			} else if (content.contains("Code=VideoMotion;action=Stop;index=0")) {
				ipCameraHandler.setChannelState(CHANNEL_MOTION_ALARM, OnOffType.valueOf("OFF"));
				ipCameraHandler.firstMotionAlarm = false;
				ipCameraHandler.motionAlarmUpdateSnapshot = false;
			}
			// Handle item taken alarm
			if (content.contains("Code=TakenAwayDetection;action=Start;index=0")) {
				ipCameraHandler.motionDetected(CHANNEL_ITEM_TAKEN);
			} else if (content.contains("Code=TakenAwayDetection;action=Stop;index=0")) {
				ipCameraHandler.setChannelState(CHANNEL_ITEM_TAKEN, OnOffType.valueOf("OFF"));
				ipCameraHandler.firstMotionAlarm = false;
				ipCameraHandler.motionAlarmUpdateSnapshot = false;
			}
			// Handle item left alarm
			if (content.contains("Code=LeftDetection;action=Start;index=0")) {
				ipCameraHandler.motionDetected(CHANNEL_ITEM_LEFT);
			} else if (content.contains("Code=LeftDetection;action=Stop;index=0")) {
				ipCameraHandler.setChannelState(CHANNEL_ITEM_LEFT, OnOffType.valueOf("OFF"));
				ipCameraHandler.firstMotionAlarm = false;
				ipCameraHandler.motionAlarmUpdateSnapshot = false;
			}
			// Handle CrossLineDetection alarm
			if (content.contains("Code=CrossLineDetection;action=Start;index=0")) {
				ipCameraHandler.motionDetected(CHANNEL_LINE_CROSSING_ALARM);
			} else if (content.contains("Code=CrossLineDetection;action=Stop;index=0")) {
				ipCameraHandler.setChannelState(CHANNEL_LINE_CROSSING_ALARM, OnOffType.valueOf("OFF"));
				ipCameraHandler.firstMotionAlarm = false;
				ipCameraHandler.motionAlarmUpdateSnapshot = false;
			}
			// determine if the audio alarm is turned on or off.
			if (content.contains("table.AudioDetect[0].MutationDetect=true")) {
				ipCameraHandler.setChannelState(CHANNEL_ENABLE_AUDIO_ALARM, OnOffType.valueOf("ON"));
			} else if (content.contains("table.AudioDetect[0].MutationDetect=false")) {
				ipCameraHandler.setChannelState(CHANNEL_ENABLE_AUDIO_ALARM, OnOffType.valueOf("OFF"));
			}
			// Handle AudioMutation alarm
			if (content.contains("Code=AudioMutation;action=Start;index=0")) {
				ipCameraHandler.audioDetected();
			} else if (content.contains("Code=AudioMutation;action=Stop;index=0")) {
				ipCameraHandler.setChannelState(CHANNEL_AUDIO_ALARM, OnOffType.valueOf("OFF"));
				ipCameraHandler.firstAudioAlarm = false;
				ipCameraHandler.audioAlarmUpdateSnapshot = false;
			}
			// Handle AudioMutationThreshold alarm
			if (content.contains("table.AudioDetect[0].MutationThreold=")) {
				String value = ipCameraHandler.returnValueFromString(content, "table.AudioDetect[0].MutationThreold=");
				ipCameraHandler.setChannelState(CHANNEL_THRESHOLD_AUDIO_ALARM, PercentType.valueOf(value));
			}
			// Handle FaceDetection alarm
			if (content.contains("Code=FaceDetection;action=Start;index=0")) {
				ipCameraHandler.motionDetected(CHANNEL_FACE_DETECTED);
			} else if (content.contains("Code=FaceDetection;action=Stop;index=0")) {
				ipCameraHandler.setChannelState(CHANNEL_FACE_DETECTED, OnOffType.valueOf("OFF"));
				ipCameraHandler.firstMotionAlarm = false;
				ipCameraHandler.motionAlarmUpdateSnapshot = false;
			}
			// Handle ParkingDetection alarm
			if (content.contains("Code=ParkingDetection;action=Start;index=0")) {
				ipCameraHandler.motionDetected(CHANNEL_PARKING_ALARM);
			} else if (content.contains("Code=ParkingDetection;action=Stop;index=0")) {
				ipCameraHandler.setChannelState(CHANNEL_PARKING_ALARM, OnOffType.valueOf("OFF"));
				ipCameraHandler.firstMotionAlarm = false;
				ipCameraHandler.motionAlarmUpdateSnapshot = false;
			}
			// Handle CrossRegionDetection alarm
			if (content.contains("Code=CrossRegionDetection;action=Start;index=0")) {
				ipCameraHandler.motionDetected(CHANNEL_FIELD_DETECTION_ALARM);
			} else if (content.contains("Code=CrossRegionDetection;action=Stop;index=0")) {
				ipCameraHandler.setChannelState(CHANNEL_FIELD_DETECTION_ALARM, OnOffType.valueOf("OFF"));
				ipCameraHandler.firstMotionAlarm = false;
				ipCameraHandler.motionAlarmUpdateSnapshot = false;
			}
			// Handle External Input alarm
			if (content.contains("Code=AlarmLocal;action=Start;index=0")) {
				ipCameraHandler.setChannelState(CHANNEL_EXTERNAL_ALARM_INPUT, OnOffType.valueOf("ON"));
			} else if (content.contains("Code=AlarmLocal;action=Stop;index=0")) {
				ipCameraHandler.setChannelState(CHANNEL_EXTERNAL_ALARM_INPUT, OnOffType.valueOf("OFF"));
			}
			// Handle External Input alarm2
			if (content.contains("Code=AlarmLocal;action=Start;index=1")) {
				ipCameraHandler.setChannelState(CHANNEL_EXTERNAL_ALARM_INPUT2, OnOffType.valueOf("ON"));
			} else if (content.contains("Code=AlarmLocal;action=Stop;index=1")) {
				ipCameraHandler.setChannelState(CHANNEL_EXTERNAL_ALARM_INPUT2, OnOffType.valueOf("OFF"));
			}
		} finally {
			ReferenceCountUtil.release(msg);
			content = null;
		}
	}

}