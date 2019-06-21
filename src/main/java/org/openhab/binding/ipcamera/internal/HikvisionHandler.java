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
import static org.openhab.binding.ipcamera.IpCameraBindingConstants.CHANNEL_ENABLE_FIELD_DETECTION_ALARM;
import static org.openhab.binding.ipcamera.IpCameraBindingConstants.CHANNEL_ENABLE_LINE_CROSSING_ALARM;
import static org.openhab.binding.ipcamera.IpCameraBindingConstants.CHANNEL_ENABLE_MOTION_ALARM;
import static org.openhab.binding.ipcamera.IpCameraBindingConstants.CHANNEL_EXTERNAL_ALARM_INPUT;
import static org.openhab.binding.ipcamera.IpCameraBindingConstants.CHANNEL_FACE_DETECTED;
import static org.openhab.binding.ipcamera.IpCameraBindingConstants.CHANNEL_FIELD_DETECTION_ALARM;
import static org.openhab.binding.ipcamera.IpCameraBindingConstants.CHANNEL_ITEM_LEFT;
import static org.openhab.binding.ipcamera.IpCameraBindingConstants.CHANNEL_ITEM_TAKEN;
import static org.openhab.binding.ipcamera.IpCameraBindingConstants.CHANNEL_LINE_CROSSING_ALARM;
import static org.openhab.binding.ipcamera.IpCameraBindingConstants.CHANNEL_MOTION_ALARM;
import static org.openhab.binding.ipcamera.IpCameraBindingConstants.CHANNEL_PIR_ALARM;

import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.thing.binding.ThingHandler;
import org.openhab.binding.ipcamera.handler.IpCameraHandler;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.ReferenceCountUtil;

public class HikvisionHandler extends ChannelDuplexHandler {
	IpCameraHandler ipCameraHandler;
	String nvrChannel;
	int lineCount, vmdCount, leftCount, takenCount, faceCount, pirCount, fieldCount = 0;

	public HikvisionHandler(ThingHandler handler, String nvrChannel) {
		ipCameraHandler = (IpCameraHandler) handler;
		this.nvrChannel = nvrChannel;
	}

	void countDown() {
		if (lineCount > 1) {
			lineCount--;
		} else if (lineCount == 1) {
			ipCameraHandler.setChannelState(CHANNEL_LINE_CROSSING_ALARM, OnOffType.valueOf("OFF"));
			lineCount--;
		}
		if (vmdCount > 1) {
			vmdCount--;
		} else if (vmdCount == 1) {
			ipCameraHandler.setChannelState(CHANNEL_MOTION_ALARM, OnOffType.valueOf("OFF"));
			vmdCount--;
		}
		if (leftCount > 1) {
			leftCount--;
		} else if (leftCount == 1) {
			ipCameraHandler.setChannelState(CHANNEL_ITEM_LEFT, OnOffType.valueOf("OFF"));
			leftCount--;
		}
		if (takenCount > 1) {
			takenCount--;
		} else if (takenCount == 1) {
			ipCameraHandler.setChannelState(CHANNEL_ITEM_TAKEN, OnOffType.valueOf("OFF"));
			takenCount--;
		}
		if (faceCount > 1) {
			faceCount--;
		} else if (faceCount == 1) {
			ipCameraHandler.setChannelState(CHANNEL_FACE_DETECTED, OnOffType.valueOf("OFF"));
			faceCount--;
		}
		if (pirCount > 1) {
			pirCount--;
		} else if (pirCount == 1) {
			ipCameraHandler.setChannelState(CHANNEL_PIR_ALARM, OnOffType.valueOf("OFF"));
			pirCount--;
		}
		if (fieldCount > 1) {
			fieldCount--;
		} else if (fieldCount == 1) {
			ipCameraHandler.setChannelState(CHANNEL_FIELD_DETECTION_ALARM, OnOffType.valueOf("OFF"));
			fieldCount--;
		}
	}

	@Override
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		String content = null;
		int debounce = 3;
		try {
			content = msg.toString();
			if (!content.isEmpty()) {
				ipCameraHandler.logger.trace("HTTP Result back from camera is \t:{}:", content);
			} else {
				return;
			}

			// Alarm checking goes in here//
			if (content.contains("<EventNotificationAlert version=\"")) {
				if (content.contains("hannelID>" + nvrChannel + "</")) {// some camera use c or <dynChannelID>

					if (content.contains("<eventType>linedetection</eventType>")) {
						ipCameraHandler.motionDetected(CHANNEL_LINE_CROSSING_ALARM);
						lineCount = debounce;
					}
					if (content.contains("<eventType>fielddetection</eventType>")) {
						ipCameraHandler.motionDetected(CHANNEL_FIELD_DETECTION_ALARM);
						fieldCount = debounce;
					}
					if (content.contains("<eventType>VMD</eventType>")) {
						ipCameraHandler.motionDetected(CHANNEL_MOTION_ALARM);
						vmdCount = debounce;
					}
					if (content.contains("<eventType>facedetection</eventType>")) {
						ipCameraHandler.setChannelState(CHANNEL_FACE_DETECTED, OnOffType.valueOf("ON"));
						faceCount = debounce;
					}
					if (content.contains("<eventType>unattendedBaggage</eventType>")) {
						ipCameraHandler.setChannelState(CHANNEL_ITEM_LEFT, OnOffType.valueOf("ON"));
						leftCount = debounce;
					}
					if (content.contains("<eventType>attendedBaggage</eventType>")) {
						ipCameraHandler.setChannelState(CHANNEL_ITEM_TAKEN, OnOffType.valueOf("ON"));
						takenCount = debounce;
					}
					if (content.contains("<eventType>PIR</eventType>")) {
						ipCameraHandler.motionDetected(CHANNEL_PIR_ALARM);
						pirCount = debounce;
					}
					if (content.contains("<eventType>videoloss</eventType>\r\n<eventState>inactive</eventState>")) {
						ipCameraHandler.audioAlarmUpdateSnapshot = false;
						ipCameraHandler.motionAlarmUpdateSnapshot = false;
						ipCameraHandler.firstMotionAlarm = false;
						countDown();
						countDown();
					}
				} else if (content.contains("<channelID>0</channelID>")) {// NVR uses channel 0 to say all channels
					if (content.contains("<eventType>videoloss</eventType>\r\n<eventState>inactive</eventState>")) {
						ipCameraHandler.audioAlarmUpdateSnapshot = false;
						ipCameraHandler.motionAlarmUpdateSnapshot = false;
						ipCameraHandler.firstMotionAlarm = false;
						countDown();
						countDown();
					}
				}
				countDown();
			}

			// determine if the motion detection is turned on or off.
			else if (content.contains("<MotionDetection version=\"2.0\" xmlns=\"http://www.")) {
				ipCameraHandler.lock.lock();
				try {
					byte indexInLists = (byte) ipCameraHandler.listOfRequests
							.indexOf("/ISAPI/System/Video/inputs/channels/" + nvrChannel + "01/motionDetection");
					if (indexInLists >= 0) {
						ipCameraHandler.logger.debug(
								"!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!! Storing new Motion reply {}",
								content);
						ipCameraHandler.listOfReplies.set(indexInLists, content);
					}
				} finally {
					ipCameraHandler.lock.unlock();
				}

				if (content.contains("<enabled>true</enabled>")) {
					ipCameraHandler.setChannelState(CHANNEL_ENABLE_MOTION_ALARM, OnOffType.valueOf("ON"));
				} else if (content.contains("<enabled>false</enabled>")) {
					ipCameraHandler.setChannelState(CHANNEL_ENABLE_MOTION_ALARM, OnOffType.valueOf("OFF"));
				}
			} else if (content.contains("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n" + "<LineDetection>")) {
				ipCameraHandler.lock.lock();
				try {
					byte indexInLists = (byte) ipCameraHandler.listOfRequests
							.indexOf("/ISAPI/Smart/LineDetection/" + nvrChannel + "01");
					if (indexInLists >= 0) {
						ipCameraHandler.logger.debug(
								"!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!! Storing new Line Crossing reply {}",
								content);
						ipCameraHandler.listOfReplies.set(indexInLists, content);
					}
				} finally {
					ipCameraHandler.lock.unlock();
				}
				if (content.contains("<enabled>true</enabled>")) {
					ipCameraHandler.setChannelState(CHANNEL_ENABLE_LINE_CROSSING_ALARM, OnOffType.valueOf("ON"));
				} else if (content.contains("<enabled>false</enabled>")) {
					ipCameraHandler.setChannelState(CHANNEL_ENABLE_LINE_CROSSING_ALARM, OnOffType.valueOf("OFF"));
				}
			} else if (content.contains("<AudioDetection version=\"2.0\" xmlns=\"http://www.")) {
				ipCameraHandler.lock.lock();
				try {
					byte indexInLists = (byte) ipCameraHandler.listOfRequests
							.indexOf("/ISAPI/Smart/AudioDetection/channels/" + nvrChannel + "01");
					if (indexInLists >= 0) {
						ipCameraHandler.listOfReplies.set(indexInLists, content);
					}
				} finally {
					ipCameraHandler.lock.unlock();
				}
				if (content.contains("<enabled>true</enabled>")) {
					ipCameraHandler.setChannelState(CHANNEL_ENABLE_AUDIO_ALARM, OnOffType.valueOf("ON"));
				} else if (content.contains("<enabled>false</enabled>")) {
					ipCameraHandler.setChannelState(CHANNEL_ENABLE_AUDIO_ALARM, OnOffType.valueOf("OFF"));
				}
			} ////////////////// External Alarm Input ///////////////
			else if (content.contains("<IOPortStatus version=\"2.0\" xmlns=\"http://www.")) {
				if (content.contains("<ioState>active</ioState>")) {
					ipCameraHandler.setChannelState(CHANNEL_EXTERNAL_ALARM_INPUT, OnOffType.valueOf("ON"));
				} else if (content.contains("<ioState>inactive</ioState>")) {
					ipCameraHandler.setChannelState(CHANNEL_EXTERNAL_ALARM_INPUT, OnOffType.valueOf("OFF"));
				}
			} else if (content.contains("<FieldDetection version=\"2.0\" xmlns=\"http://www.")) {
				ipCameraHandler.lock.lock();
				try {
					byte indexInLists = (byte) ipCameraHandler.listOfRequests
							.indexOf("/ISAPI/Smart/FieldDetection/" + nvrChannel + "01");
					if (indexInLists >= 0) {
						ipCameraHandler.logger.debug(
								"!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!! Storing new FieldDetection reply {}",
								content);
						ipCameraHandler.listOfReplies.set(indexInLists, content);
					}
				} finally {
					ipCameraHandler.lock.unlock();
				}
				if (content.contains("<enabled>true</enabled>")) {
					ipCameraHandler.setChannelState(CHANNEL_ENABLE_FIELD_DETECTION_ALARM, OnOffType.valueOf("ON"));
				} else if (content.contains("<enabled>false</enabled>")) {
					ipCameraHandler.setChannelState(CHANNEL_ENABLE_FIELD_DETECTION_ALARM, OnOffType.valueOf("OFF"));
				}
			}
		} finally {
			ReferenceCountUtil.release(msg);
			content = null;
		}
	}
}
