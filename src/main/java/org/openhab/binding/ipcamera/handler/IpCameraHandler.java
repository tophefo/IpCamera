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
package org.openhab.binding.ipcamera.handler;

import static org.openhab.binding.ipcamera.IpCameraBindingConstants.*;

import java.net.ConnectException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.xml.soap.SOAPException;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.config.core.Configuration;
import org.eclipse.smarthome.core.library.types.PercentType;
import org.eclipse.smarthome.core.library.types.StringType;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.core.thing.binding.BaseThingHandler;
import org.eclipse.smarthome.core.types.Command;
import org.onvif.ver10.schema.FloatRange;
import org.onvif.ver10.schema.PTZVector;
import org.onvif.ver10.schema.Profile;
import org.onvif.ver10.schema.VideoEncoderConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.onvif.soap.OnvifDevice;
import de.onvif.soap.devices.PtzDevices;

/**
 * The {@link IpCameraHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author Matthew Skinner - Initial contribution
 */

public class IpCameraHandler extends BaseThingHandler {

    @Nullable
    private OnvifDevice onvifCamera;
    private List<Profile> profiles;
    private String username;
    private String password;
    private FloatRange panRange;
    private FloatRange tiltRange;
    private PtzDevices ptzDevices;
    private ScheduledFuture<?> cameraConnectionJob = null;
    private int profileNumber = 0;

    @NonNull
    private String snapshotUri = "empty";
    private String httpStreamUri = "empty";
    private String ipAddress = "empty";
    int httpPort = 80;
    private String profileToken = "empty";

    // These hold the cameras PTZ position in the range that the camera uses, ie mine is -1 to +1
    private float currentPanCamValue = 0.0f;
    private float currentTiltCamValue = 0.0f;
    private float currentZoomCamValue = 0.0f;
    private float zoomMin = 0;
    private float zoomMax = 0;
    // These hold the PTZ values for updating Openhabs controls in 0-100 range
    private float currentPanPercentage = 0.0f;
    private float currentTiltPercentage = 0.0f;
    private float currentZoomPercentage = 0.0f;

    private final ScheduledExecutorService cameraConnection = Executors.newSingleThreadScheduledExecutor();

    private final Logger logger = LoggerFactory.getLogger(IpCameraHandler.class);

    @Nullable
    private Configuration config;

    public IpCameraHandler(Thing thing) {
        super(thing);
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {

        if (onvifCamera == null) {
            return; // connection is lost or has not been made yet and the following code may cause NPE if allowed to
                    // run so we return.
        }

        if (command.toString() == "REFRESH") {
            PTZVector ptzLocation;

            switch (channelUID.getId()) {
                case CHANNEL_IMAGE_URL:
                    if (onvifCamera != null) {
                        try {
                            snapshotUri = onvifCamera.getMedia().getSnapshotUri(profileToken);
                            updateState(CHANNEL_IMAGE_URL, new StringType(snapshotUri));
                        } catch (ConnectException | SOAPException e) {
                            logger.error("ONVIF error occured on refresh of image url");
                        }
                    }
                    break;

                case CHANNEL_VIDEO_URL:

                    break;
                case CHANNEL_ABSOLUTE_PAN:
                    if (ptzDevices != null) {
                        ptzLocation = ptzDevices.getPosition(profileToken);
                        currentPanPercentage = (((panRange.getMin() - ptzLocation.getPanTilt().getX()) * -1)
                                / ((panRange.getMin() - panRange.getMax()) * -1)) * 100;
                        currentPanCamValue = ((((panRange.getMin() - panRange.getMax()) * -1) / 100)
                                * currentPanPercentage + panRange.getMin());
                        logger.debug("Pan is updating to:{}", Math.round(currentPanPercentage));
                        updateState(CHANNEL_ABSOLUTE_PAN, new PercentType(Math.round(currentPanPercentage)));
                    }
                    break;
                case CHANNEL_ABSOLUTE_TILT:
                    if (ptzDevices != null) {
                        ptzLocation = ptzDevices.getPosition(profileToken);

                        currentTiltPercentage = (((tiltRange.getMin() - ptzLocation.getPanTilt().getY()) * -1)
                                / ((tiltRange.getMin() - tiltRange.getMax()) * -1)) * 100;
                        currentTiltCamValue = ((((tiltRange.getMin() - tiltRange.getMax()) * -1) / 100)
                                * currentTiltPercentage + tiltRange.getMin());
                        logger.debug("Tilt is updating to:{}", Math.round(currentTiltPercentage));
                        updateState(CHANNEL_ABSOLUTE_TILT, new PercentType(Math.round(currentTiltPercentage)));
                    }
                    break;
                case CHANNEL_ABSOLUTE_ZOOM:
                    if (ptzDevices != null) {
                        ptzLocation = ptzDevices.getPosition(profileToken);
                        currentZoomPercentage = (((zoomMin - ptzLocation.getZoom().getX()) * -1)
                                / ((zoomMin - zoomMax) * -1)) * 100;
                        currentZoomCamValue = ((((zoomMin - zoomMax) * -1) / 100) * currentZoomPercentage + zoomMin);
                        logger.debug("Zoom is updating to:{}", Math.round(currentZoomPercentage));
                        updateState(CHANNEL_ABSOLUTE_ZOOM, new PercentType(Math.round(currentZoomPercentage)));
                    }
                    break;
            }
            return; // Return as we have handled the refresh command above and don't need to continue further.
        } // end of "REFRESH"

        switch (channelUID.getId()) {
            case CHANNEL_1:

                logger.info("button pushed");

                /*
                 * NettyClient foo = new NettyClient();
                 *
                 * try {
                 * foo.connectToCam();
                 * } catch (Exception e) {
                 * // TODO Auto-generated catch block
                 * e.printStackTrace();
                 * }
                 */
                break;

            case CHANNEL_ABSOLUTE_PAN:
                if (ptzDevices != null) {
                    currentPanCamValue = ((((panRange.getMin() - panRange.getMax()) * -1) / 100)
                            * Float.valueOf(command.toString()) + panRange.getMin());
                    logger.debug("Cameras Pan  has changed to:{}", currentPanCamValue);
                    if (onvifCamera != null && panRange != null && tiltRange != null) {
                        try {
                            ptzDevices.absoluteMove(profileToken, currentPanCamValue, currentTiltCamValue,
                                    currentZoomCamValue);
                        } catch (SOAPException e) {
                            logger.error("SOAP exception occured");
                        }
                    }
                }
                break;
            case CHANNEL_ABSOLUTE_TILT:
                if (ptzDevices != null) {
                    currentTiltCamValue = ((((tiltRange.getMin() - tiltRange.getMax()) * -1) / 100)
                            * Float.valueOf(command.toString()) + tiltRange.getMin());
                    logger.debug("Cameras Tilt has changed to:{}", currentTiltCamValue);
                    if (onvifCamera != null && panRange != null && tiltRange != null) {
                        try {
                            ptzDevices.absoluteMove(profileToken, currentPanCamValue, currentTiltCamValue,
                                    currentZoomCamValue);
                        } catch (SOAPException e) {
                            logger.error("SOAP exception occured");
                        }
                    }
                }
                break;

            case CHANNEL_ABSOLUTE_ZOOM:
                if (ptzDevices != null) {
                    currentZoomCamValue = ((((zoomMin - zoomMax) * -1) / 100) * Float.valueOf(command.toString())
                            + zoomMin);
                    logger.debug("Cameras Zoom has changed to:{}", currentZoomCamValue);
                    if (onvifCamera != null && panRange != null && tiltRange != null) {
                        try {
                            ptzDevices.absoluteMove(profileToken, currentPanCamValue, currentTiltCamValue,
                                    currentZoomCamValue);
                        } catch (SOAPException e) {
                            logger.error("SOAP exception occured");
                        }
                    }
                }
                break;

        }
    }

    Runnable pollingCameraConnection = new Runnable() {
        @Override
        public void run() {

            if (onvifCamera == null) {
                try {
                    logger.info("About to connect to IP Camera at IP:{}", ipAddress);

                    if (username != null && password != null) {
                        onvifCamera = new OnvifDevice(ipAddress, username, password);
                    } else {
                        onvifCamera = new OnvifDevice(ipAddress);
                    }

                    profiles = onvifCamera.getDevices().getProfiles();

                    if (profileNumber > profiles.size()) {
                        logger.warn(
                                "The selected Profile Number in the binding is higher than the max supported profiles. Changing to profile 0.");
                        profileNumber = 0;
                    }

                    for (int x = 0; x < profiles.size(); x++) {
                        VideoEncoderConfiguration result = profiles.get(x).getVideoEncoderConfiguration();
                        logger.info(
                                "********************* Profile {} details reported by camera at IP:{} *********************",
                                x, ipAddress);
                        if (profileNumber == x) {
                            logger.info(
                                    "Camera will use this profile unless you change it in the binding by pressing on pencil icon in paper UI");
                        }
                        logger.info("Camera Profile Number {} is named:{}", x, result.getName());
                        logger.info("Camera Profile Number {} uses video encoder\t:{}", x, result.getEncoding());
                        logger.info("Camera Profile Number {} uses video quality\t:{}", x, result.getQuality());
                        logger.info("Camera Profile Number {} uses video resoltion\t:{} x {}", x,
                                result.getResolution().getWidth(), result.getResolution().getHeight());
                        logger.info("Camera Profile Number {} uses video bitrate\t:{}", x,
                                result.getRateControl().getBitrateLimit());
                    }

                    profileToken = profiles.get(profileNumber).getToken();
                    snapshotUri = onvifCamera.getMedia().getSnapshotUri(profileToken);
                    httpStreamUri = onvifCamera.getMedia().getHTTPStreamUri(profileToken);

                    ptzDevices = onvifCamera.getPtz();
                    if (ptzDevices.isPtzOperationsSupported(profileToken)
                            && ptzDevices.isAbsoluteMoveSupported(profileToken)) {

                        panRange = ptzDevices.getPanSpaces(profileToken);
                        tiltRange = ptzDevices.getTiltSpaces(profileToken);
                        zoomMin = ptzDevices.getZoomSpaces(profileToken).getMin();
                        zoomMax = ptzDevices.getZoomSpaces(profileToken).getMax();

                        logger.info("Camera is reporting that it supports PTZ controls via ONVIF");
                        logger.debug("The camera can Pan  from {} to {}", panRange.getMin(), panRange.getMax());
                        logger.debug("The camera can Tilt from {} to {}", tiltRange.getMin(), tiltRange.getMax());
                        logger.debug("The camera can Zoom from {} to {}", zoomMin, zoomMax);

                    } else {
                        logger.info("Camera is reporting that it does NOT support Absolute PTZ controls via ONVIF");
                        ptzDevices = null; // this should disable all code from trying to use the PTZ features on
                                           // cameras that do not have them.
                    }

                    updateState(CHANNEL_IMAGE_URL, new StringType(snapshotUri));
                    updateState(CHANNEL_VIDEO_URL, new StringType(httpStreamUri));
                    updateStatus(ThingStatus.ONLINE);

                } catch (ConnectException e) {
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                            "Can not access camera: Check that your IP ADDRESS, USERNAME and PASSWORD are correct and the camera can be reached.");
                    logger.error(e.toString());
                } catch (SOAPException e) {
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                            "Camera gave a SOAP exception during initial connection attempt");
                    logger.error(e.toString());
                }
            } else {

                logger.debug("Checking camera is still online. This occurs every 30 seconds.");

                try {
                    onvifCamera.getMedia().getSnapshotUri(profileToken);
                } catch (ConnectException e) {
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                            "Can not access camera: Check your ADDRESS, USERNAME and PASSWORD are correctand the camera can be reached.");
                    onvifCamera = null;
                } catch (SOAPException e) {
                    logger.error("camera gave a SOAP exception during the 30 second poll.");
                }

            }
        }
    };

    @Override
    public void initialize() {

        logger.debug("Getting configuration data for a camera thing.");
        config = getThing().getConfiguration();
        ipAddress = config.get(CONFIG_IPADDRESS).toString();
        if (config.get(CONFIG_USERNAME) != null) {
            username = config.get(CONFIG_USERNAME).toString();
        }
        if (config.get(CONFIG_PASSWORD) != null) {
            password = config.get(CONFIG_PASSWORD).toString();
        }
        profileNumber = Integer.parseInt(config.get(CONFIG_ONVIF_PROFILE_NUMBER).toString());
        onvifCamera = null; // needed for when the ip,user,pass are changed to non valid values from valid ones.
        if (cameraConnectionJob != null) {
            cameraConnectionJob.cancel(true);
        }

        cameraConnectionJob = cameraConnection.scheduleAtFixedRate(pollingCameraConnection, 0, 30, TimeUnit.SECONDS);

    }

    @Override
    public void dispose() {
        logger.debug("Camera dispose called, about to remove the Camera thing.");
        if (cameraConnectionJob != null) {
            cameraConnectionJob.cancel(true);
        }
    }
}
