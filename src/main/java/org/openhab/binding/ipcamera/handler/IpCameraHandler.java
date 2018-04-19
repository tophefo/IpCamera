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
import java.net.URI;
import java.net.URISyntaxException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLException;
import javax.xml.soap.SOAPException;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.library.types.PercentType;
import org.eclipse.smarthome.core.library.types.StringType;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingStatusDetail;
import org.eclipse.smarthome.core.thing.ThingTypeUID;
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
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.base64.Base64;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.CharsetUtil;

/**
 * The {@link IpCameraHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author Matthew Skinner - Initial contribution
 */

// Special note and thanks to authors of HttpSnoopClient which is sample code for the Netty library from which some code
// is based heavily from and is released under Apache License version 2.0//

public class IpCameraHandler extends BaseThingHandler {

    public static final Set<ThingTypeUID> SUPPORTED_THING_TYPES = new HashSet<ThingTypeUID>(
            Arrays.asList(THING_TYPE_ONVIF, THING_TYPE_AMCREST, THING_TYPE_AXIS, THING_TYPE_FOSCAM));

    private OnvifDevice onvifCamera;
    private List<Profile> profiles;
    private String username;
    private String password;
    private FloatRange panRange;
    private FloatRange tiltRange;
    private PtzDevices ptzDevices;
    private ScheduledFuture<?> cameraConnectionJob = null;
    private ScheduledFuture<?> checkAlarmJob = null;
    private int selectedMediaProfile = 0;
    private EventLoopGroup eventLoopGroup;
    private Bootstrap bootstrap;
    private String fullRequestPath;
    private PTZVector ptzLocation;

    @NonNull
    private final Logger logger = LoggerFactory.getLogger(IpCameraHandler.class);
    private final ScheduledExecutorService cameraConnection = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService checkAlarm = Executors.newSingleThreadScheduledExecutor();
    private String snapshotUri = "empty";
    private String videoStreamUri = "empty";
    private String ipAddress = "empty";
    private String profileToken = "empty";
    private boolean useDigestAuth = false;
    private boolean useBasicAuth = false;
    private String digestString = "false";
    private int ncCounter = 0;
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

    private void sendHttpRequest(String httpRequest) {

        if (eventLoopGroup == null) {
            eventLoopGroup = new NioEventLoopGroup();
        }

        if (bootstrap == null) {
            bootstrap = new Bootstrap();
            bootstrap.group(eventLoopGroup);
            // following needed for HTTPS support when I have time to look//
            // bootstrap.group(group).channel(NioSocketChannel.class)
            // .handler(new HttpSnoopClientInitializer(sslCtx));
            bootstrap.channel(NioSocketChannel.class);
            bootstrap.option(ChannelOption.SO_KEEPALIVE, true);
            bootstrap.handler(new ChannelInitializer<SocketChannel>() {
                @Override
                public void initChannel(SocketChannel socketChannel) throws Exception {
                    socketChannel.pipeline().addLast(new HttpClientCodec());
                    socketChannel.pipeline().addLast(new HttpContentDecompressor());

                    switch (thing.getThingTypeUID().getId()) {
                        case "AMCREST":
                            socketChannel.pipeline().addLast(new AmcrestHandler());
                            break;
                        case "FOSCAM":
                            socketChannel.pipeline().addLast(new FoscamHandler());
                            break;
                        default:
                            // Use the most tested one for now.
                            socketChannel.pipeline().addLast(new AmcrestHandler());
                            break;
                    }
                }
            });
        }

        try {
            URI uri;
            uri = new URI(httpRequest);

            String scheme = uri.getScheme() == null ? "http" : uri.getScheme();
            int port = uri.getPort();
            if (port == -1) {
                if ("http".equalsIgnoreCase(scheme)) {
                    port = 80;
                } else if ("https".equalsIgnoreCase(scheme)) {
                    port = 443;
                }
            }

            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                logger.error("Only HTTP(S) is supported.");
                return;
            }

            // Configure SSL context if necessary.
            final boolean ssl = "https".equalsIgnoreCase(scheme);
            final SslContext sslCtx;
            if (ssl) {
                try {
                    sslCtx = SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
                } catch (SSLException e) {
                    logger.error("SSL exception occured:{}", e);
                }
            } else {
                sslCtx = null;
            }

            Channel ch = bootstrap.connect(ipAddress, port).sync().channel();

            if (uri.getRawQuery() == null) {
                fullRequestPath = uri.getPath();
            } else {
                fullRequestPath = uri.getPath() + "?" + uri.getRawQuery();
            }

            logger.debug("The request is going to be :{}", fullRequestPath);

            HttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, fullRequestPath);
            request.headers().set(HttpHeaderNames.HOST, ipAddress);
            request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            request.headers().set(HttpHeaderNames.ACCEPT_ENCODING, HttpHeaderValues.GZIP);

            if (useDigestAuth) {
                request.headers().set(HttpHeaderNames.AUTHORIZATION, "Digest " + digestString);
            }

            if (useBasicAuth) {
                String authString = username + ":" + password;
                ByteBuf byteBuf = null;
                try {
                    byteBuf = Base64.encode(Unpooled.wrappedBuffer(authString.getBytes(CharsetUtil.UTF_8)));
                    request.headers().set(HttpHeaderNames.AUTHORIZATION,
                            "Basic " + byteBuf.toString(CharsetUtil.UTF_8));
                } finally {
                    if (byteBuf != null) {
                        byteBuf.release();
                    }
                }
            }

            ch.writeAndFlush(request);
            ch.closeFuture().sync();

        } catch (URISyntaxException | InterruptedException e) {
            logger.error("Following error occured:{}", e);
        }
    }

    private String searchString(String rawString, String searchedString) {
        String result = "";
        int index = 0;
        index = rawString.indexOf(searchedString);
        if (index != -1) // -1 means "not found"
        {
            result = rawString.substring(index + searchedString.length(), rawString.length());
            index = result.indexOf(',');
            if (index == -1) {
                index = result.indexOf('"');
                if (index == -1) {
                    index = result.indexOf('}');
                    if (index == -1) {
                        return result;
                    } else {
                        return result.substring(0, index);
                    }
                } else {
                    return result.substring(0, index);
                }
            } else {
                result = result.substring(0, index);
                index = result.indexOf('"');
                if (index == -1) {
                    return result;
                } else {
                    return result.substring(0, index);
                }
            }
        }
        return null;
    }

    private String calcMD5Hash(String toHash) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("MD5");
            byte[] array = messageDigest.digest(toHash.getBytes());
            StringBuffer stringBuffer = new StringBuffer();
            for (int i = 0; i < array.length; ++i) {
                stringBuffer.append(Integer.toHexString((array[i] & 0xFF) | 0x100).substring(1, 3));
            }
            return stringBuffer.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            logger.error("NoSuchAlgorithmException when calculating MD5 hash");
        }
        return null;
    }

    void processAuth(String rawString) {

        String realm = searchString(rawString, "Basic realm=\"");

        if (realm != null) {
            useDigestAuth = false;
            useBasicAuth = true;
            return;
        }
        realm = searchString(rawString, "Digest realm=\"");
        if (realm == null) {
            useDigestAuth = false;
            useBasicAuth = false;
            return;
        }
        useDigestAuth = true;
        // Must increase each time otherwise a new nonce and cnonce is needed which increases traffic.
        ncCounter = (ncCounter <= 65000) ? ncCounter + 1 : 1;
        Random random = new Random();
        // Can keep the cnonce the same to save network traffic in certain cases.
        String cnonce = Integer.toHexString(random.nextInt());
        String nonce = searchString(rawString, "nonce=\"");
        String opaque = searchString(rawString, "opaque=\"");
        String qop = searchString(rawString, "qop=\"");
        // create the MD5 hashes
        String ha1 = username + ":" + realm + ":" + password;
        ha1 = calcMD5Hash(ha1);
        String ha2 = "GET:" + fullRequestPath;
        ha2 = calcMD5Hash(ha2);
        String request = ha1 + ":" + nonce + ":" + ncCounter + ":" + cnonce + ":" + qop + ":" + ha2;
        request = calcMD5Hash(request);

        digestString = "username=\"" + username + "\", realm=\"" + realm + "\", nonce=\"" + nonce + "\", uri=\""
                + fullRequestPath + "\", qop=" + qop + ", nc=" + ncCounter + ", cnonce=\"" + cnonce + "\", response=\""
                + request + "\", opaque=\"" + opaque + "\"";

        sendHttpRequest(fullRequestPath);
    }

    public class AmcrestHandler extends ChannelInboundHandlerAdapter {

        private void processResponseContent(String content) {

            logger.debug("HTTP Result back from camera is :{}:", content);

            switch (content) {
                case "Error: No Events\r\n":
                    updateState(CHANNEL_MOTION_ALARM, OnOffType.valueOf("OFF"));
                    break;

                case "channels[0]=0\r\n":
                    updateState(CHANNEL_MOTION_ALARM, OnOffType.valueOf("ON"));
                    break;
            }
        }

        @Override
        public void channelActive(ChannelHandlerContext channelHandlerContext) throws Exception {
        }

        // This method handles the Servers response
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            logger.debug(msg.toString());

            if (msg instanceof HttpResponse) {
                HttpResponse response = (HttpResponse) msg;

                // WWW-Authenticate
                if (!response.headers().isEmpty()) {
                    for (CharSequence name : response.headers().names()) {
                        for (CharSequence value : response.headers().getAll(name)) {
                            // logger.debug("HEADER: {} = {}", name, value);
                            if (name.toString().equals("WWW-Authenticate")) {
                                processAuth(value.toString());
                            }
                        }
                    }
                }

                if (HttpUtil.isTransferEncodingChunked(response)) {
                    logger.debug("CHUNKED CONTENT {");
                } else {
                    logger.debug("CONTENT {");
                }
            }
            if (msg instanceof HttpContent) {
                HttpContent content = (HttpContent) msg;
                logger.debug(content.content().toString(CharsetUtil.UTF_8));

                // Process the contents of a response //
                if (!content.content().toString().isEmpty()) {
                    processResponseContent(content.content().toString(CharsetUtil.UTF_8));
                }

                if (content instanceof LastHttpContent) {
                    logger.debug("} END OF CONTENT");
                    ctx.close();
                }
            }
        }
    }

    public class FoscamHandler extends ChannelInboundHandlerAdapter {

        private void processResponseContent(String content) {
            logger.debug("HTTP Result back from camera is :{}:", content);

            if (searchString(content, "<MotionDetectAlarm> 1 </ motionDetectAlarm>") != null) {
                updateState(CHANNEL_MOTION_ALARM, OnOffType.valueOf("OFF"));
            } else if (searchString(content, "<MotionDetectAlarm> 2 </ motionDetectAlarm>") != null) {
                updateState(CHANNEL_MOTION_ALARM, OnOffType.valueOf("ON"));
            } else if (searchString(content, "<MotionDetectAlarm> 0 </ motionDetectAlarm>") != null) {
                logger.debug("Motion Alarm is turned off in camera settings.");
                updateState(CHANNEL_MOTION_ALARM, OnOffType.valueOf("OFF"));
            } else if (searchString(content, "<Sound alarm> 0 </ sound alarm>") != null) {
                updateState(CHANNEL_AUDIO_ALARM, OnOffType.valueOf("OFF"));
            } else if (searchString(content, "<Sound alarm> 1 </ sound alarm>") != null) {
                updateState(CHANNEL_AUDIO_ALARM, OnOffType.valueOf("OFF"));
            } else if (searchString(content, "<Sound alarm> 2 </ sound alarm>") != null) {
                updateState(CHANNEL_AUDIO_ALARM, OnOffType.valueOf("ON"));
            }
        }

        @Override
        public void channelActive(ChannelHandlerContext channelHandlerContext) throws Exception {
        }

        // This method handles the Cameras response
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            logger.debug(msg.toString());

            if (msg instanceof HttpResponse) {
                HttpResponse response = (HttpResponse) msg;

                // WWW-Authenticate
                if (!response.headers().isEmpty()) {
                    for (CharSequence name : response.headers().names()) {
                        for (CharSequence value : response.headers().getAll(name)) {
                            // logger.debug("HEADER: {} = {}", name, value);
                            if (name.toString().equals("WWW-Authenticate")) {
                                processAuth(value.toString());
                            }
                        }
                    }
                }

                if (HttpUtil.isTransferEncodingChunked(response)) {
                    logger.debug("CHUNKED CONTENT {");
                } else {
                    logger.debug("CONTENT {");
                }
            }
            if (msg instanceof HttpContent) {
                HttpContent content = (HttpContent) msg;
                logger.debug(content.content().toString(CharsetUtil.UTF_8));

                // Process the contents of a response //
                if (!content.content().toString().isEmpty()) {
                    processResponseContent(content.content().toString(CharsetUtil.UTF_8));
                }

                if (content instanceof LastHttpContent) {
                    logger.debug("} END OF CONTENT");
                    ctx.close();
                }
            }
        }
    }

    public IpCameraHandler(Thing thing) {
        super(thing);
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {

        if (onvifCamera == null) {
            return; // connection is lost or has not been made yet.
        }

        if (command.toString() == "REFRESH") {

            switch (channelUID.getId()) {
                case CHANNEL_IMAGE_URL:

                    break;

                case CHANNEL_VIDEO_URL:

                    break;
                case CHANNEL_ABSOLUTE_PAN:
                    if (ptzDevices != null) {
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

                logger.debug("button pushed");
                // sendHttpRequest("http://192.168.1.108/really cool address ;)");

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
                    logger.info("About to connect to IP Camera at IP:{}:{}", ipAddress,
                            thing.getConfiguration().get(CONFIG_ONVIF_PORT).toString());

                    if (username != null && password != null) {
                        onvifCamera = new OnvifDevice(
                                ipAddress + ":" + thing.getConfiguration().get(CONFIG_ONVIF_PORT).toString(), username,
                                password);
                    } else {
                        onvifCamera = new OnvifDevice(
                                ipAddress + ":" + thing.getConfiguration().get(CONFIG_ONVIF_PORT).toString());
                    }
                    logger.info("About to fetch the Media Profile list from the camera");
                    profiles = onvifCamera.getDevices().getProfiles();

                    if (selectedMediaProfile > profiles.size()) {
                        logger.warn(
                                "The selected Media Profile in the binding is higher than the max supported profiles. Changing to use Media Profile 0.");
                        selectedMediaProfile = 0;
                    }

                    for (int x = 0; x < profiles.size(); x++) {
                        VideoEncoderConfiguration result = profiles.get(x).getVideoEncoderConfiguration();
                        logger.info(
                                "********************* Media Profile {} details reported by camera at IP:{} *********************",
                                x, ipAddress);
                        if (selectedMediaProfile == x) {
                            logger.info(
                                    "Camera will use this Media Profile unless you change it in the binding by pressing on the pencil icon in PaperUI.");
                        }
                        logger.info("Media Profile {} is named:{}", x, result.getName());
                        logger.info("Media Profile {} uses video encoder\t:{}", x, result.getEncoding());
                        logger.info("Media Profile {} uses video quality\t:{}", x, result.getQuality());
                        logger.info("Media Profile {} uses video resoltion\t:{} x {}", x,
                                result.getResolution().getWidth(), result.getResolution().getHeight());
                        logger.info("Media Profile {} uses video bitrate\t:{}", x,
                                result.getRateControl().getBitrateLimit());
                    }

                    profileToken = profiles.get(selectedMediaProfile).getToken();
                    snapshotUri = onvifCamera.getMedia().getSnapshotUri(profileToken);
                    videoStreamUri = onvifCamera.getMedia().getHTTPStreamUri(profileToken);
                    logger.info(
                            "This camera supports the following Video links. NOTE: The camera may report a link or error that does not match the header, this is the camera not a bug in the binding.");
                    logger.info("HTTP Stream:{}", videoStreamUri);
                    logger.info("TCP Stream:{}", onvifCamera.getMedia().getTCPStreamUri(profileToken));
                    logger.info("RTSP Stream:{}", onvifCamera.getMedia().getRTSPStreamUri(profileToken));
                    logger.info("UDP Stream:{}", onvifCamera.getMedia().getUDPStreamUri(profileToken));

                    if (!videoStreamUri.contains("http") && !videoStreamUri.contains("HTTP")) {
                        videoStreamUri = onvifCamera.getMedia().getRTSPStreamUri(profileToken);
                    }

                    ptzDevices = onvifCamera.getPtz();
                    if (ptzDevices.isPtzOperationsSupported(profileToken)
                            && ptzDevices.isAbsoluteMoveSupported(profileToken)) {

                        panRange = ptzDevices.getPanSpaces(profileToken);
                        tiltRange = ptzDevices.getTiltSpaces(profileToken);
                        zoomMin = ptzDevices.getZoomSpaces(profileToken).getMin();
                        zoomMax = ptzDevices.getZoomSpaces(profileToken).getMax();

                        logger.info("Camera is reporting it supports PTZ controls via ONVIF");
                        if (logger.isDebugEnabled()) {
                            logger.debug("The camera can Pan  from {} to {}", panRange.getMin(), panRange.getMax());
                            logger.debug("The camera can Tilt from {} to {}", tiltRange.getMin(), tiltRange.getMax());
                            logger.debug("The camera can Zoom from {} to {}", zoomMin, zoomMax);
                        }
                        ptzLocation = ptzDevices.getPosition(profileToken);

                    } else {
                        logger.info("Camera is reporting that it does NOT support Absolute PTZ controls via ONVIF");
                        // null will stop code from running on cameras that do not support PTZ features.
                        ptzDevices = null;
                    }

                    updateState(CHANNEL_IMAGE_URL, new StringType(snapshotUri));
                    updateState(CHANNEL_VIDEO_URL, new StringType(videoStreamUri));
                    cameraConnectionJob.cancel(true);
                    cameraConnectionJob = null;
                    cameraConnectionJob = cameraConnection.scheduleAtFixedRate(pollingCameraConnection, 29, 29,
                            TimeUnit.SECONDS);
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
            }
            //////////////// Code below is when Camera is already connected ////////////////
            else {

                logger.debug("Checking camera is still online. This occurs every 29 seconds.");

                try {
                    onvifCamera.getMedia().getSnapshotUri(profileToken);
                    ptzLocation = (ptzLocation == null) ? null : ptzDevices.getPosition(profileToken);
                } catch (ConnectException e) {
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                            "Can not access camera during 30 second poll: Check your ADDRESS, USERNAME and PASSWORD are correctand the camera can be reached.");
                    onvifCamera = null;
                } catch (SOAPException e) {
                    logger.error("Camera gave a SOAP exception during the 30 second poll");
                }
            }
        }
    };

    Runnable pollingAlarm = new Runnable() {
        @Override
        public void run() {

            switch (thing.getThingTypeUID().getId()) {
                case "AMCREST":
                    sendHttpRequest(
                            "http://192.168.1.108/cgi-bin/eventManager.cgi?action=getEventIndexes&code=VideoMotion");
                    break;

                case "FOSCAM":
                    sendHttpRequest("http://192.168.1.108/cgi-bin/CGIProxy.fcgi?cmd=getDevState");
                    break;
            }
        }
    };

    @Override
    public void initialize() {
        logger.debug("Getting configuration to initialize an IP Camera.");

        if (cameraConnectionJob != null) {
            cameraConnectionJob.cancel(true);
        }
        onvifCamera = null; // needed for when the ip,user,pass are changed to non valid values from valid ones.
        ipAddress = thing.getConfiguration().get(CONFIG_IPADDRESS).toString();
        username = (thing.getConfiguration().get(CONFIG_USERNAME) == null) ? null
                : thing.getConfiguration().get(CONFIG_USERNAME).toString();
        password = (thing.getConfiguration().get(CONFIG_PASSWORD) == null) ? null
                : thing.getConfiguration().get(CONFIG_PASSWORD).toString();
        selectedMediaProfile = (thing.getConfiguration().get(CONFIG_ONVIF_PROFILE_NUMBER) == null) ? 0
                : Integer.parseInt(thing.getConfiguration().get(CONFIG_ONVIF_PROFILE_NUMBER).toString());

        cameraConnectionJob = cameraConnection.scheduleAtFixedRate(pollingCameraConnection, 0, 180, TimeUnit.SECONDS);

        if (thing.getThingTypeUID().getId().equals("AMCREST") || thing.getThingTypeUID().getId().equals("FOSCAM")) {
            checkAlarmJob = checkAlarm.scheduleAtFixedRate(pollingAlarm,
                    Integer.parseInt(thing.getConfiguration().get(CONFIG_CHECK_STATUS_DELAY).toString()),
                    Integer.parseInt(thing.getConfiguration().get(CONFIG_CHECK_STATUS_DELAY).toString()),
                    TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void dispose() {
        logger.debug("Camera dispose called, about to remove the Camera thing.");
        if (cameraConnectionJob != null) {
            cameraConnectionJob.cancel(true);
        }
        if (checkAlarmJob != null) {
            checkAlarmJob.cancel(true);
        }
    }
}
