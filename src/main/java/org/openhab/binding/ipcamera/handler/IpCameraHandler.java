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
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLException;
import javax.xml.soap.SOAPException;

import org.eclipse.smarthome.config.core.Configuration;
import org.eclipse.smarthome.core.library.types.OnOffType;
import org.eclipse.smarthome.core.library.types.PercentType;
import org.eclipse.smarthome.core.library.types.RawType;
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
import org.onvif.ver10.schema.Vector1D;
import org.onvif.ver10.schema.Vector2D;
import org.onvif.ver10.schema.VideoEncoderConfiguration;
import org.openhab.binding.ipcamera.internal.MyNettyAuthHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.onvif.soap.OnvifDevice;
import de.onvif.soap.devices.PtzDevices;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.base64.Base64;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
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

public class IpCameraHandler extends BaseThingHandler {

    public static final Set<ThingTypeUID> SUPPORTED_THING_TYPES = new HashSet<ThingTypeUID>(
            Arrays.asList(THING_TYPE_ONVIF, THING_TYPE_HTTPONLY, THING_TYPE_AMCREST, THING_TYPE_AXIS, THING_TYPE_FOSCAM,
                    THING_TYPE_HIKVISION));
    private Configuration config;
    private OnvifDevice onvifCamera;
    private List<Profile> profiles;
    private String username;
    private String password;
    private ScheduledFuture<?> cameraConnectionJob = null;
    private ScheduledFuture<?> fetchCameraOutputJob = null;
    private int selectedMediaProfile = 0;
    private Bootstrap mainBootstrap;

    private EventLoopGroup mainEventLoopGroup = new NioEventLoopGroup();

    public String correctedRequestURL, httpMethod;
    private String scheme = "http";
    private PTZVector ptzLocation;
    private Channel ch;
    private ChannelFuture mainChFuture;
    // Following used for digest Auth as it is allowed to reuse a NONCE to speed up comms if a camera supports this.//
    public int ncCounter = 0;
    public String opaque;
    public String qop;
    public String realm;
    // basicAuth MUST remain private as it holds the password
    private String basicAuth = null;
    public boolean useDigestAuth = false;
    public String digestString = "false";
    public String nonce;

    private String channelCheckingNow = "NONE";
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final ScheduledExecutorService cameraConnection = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService fetchCameraOutput = Executors.newSingleThreadScheduledExecutor();
    private String snapshotUri = "";
    private String videoStreamUri = "empty";
    private String ipAddress = "empty";
    private int port;
    private String profileToken = "empty";

    private String updateImageEvents;
    boolean firstAudioAlarm = false;
    boolean firstMotionAlarm = false;

    private FloatRange panRange;
    private FloatRange tiltRange;
    private PtzDevices ptzDevices;
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

    // Special note and thanks to authors of HttpSnoopClient which is sample code for the Netty library//
    // I used it as a starting point as it is released under Apache License version 2.0//

    public void setBasicAuth(boolean useBasic) {

        if (useBasic == false) {
            logger.debug("Removing BASIC auth now and making it NULL.");
            basicAuth = null;
            return;
        }
        logger.debug("Setting up the BASIC auth now, this should only happen once.");
        String authString = username + ":" + password;
        ByteBuf byteBuf = null;
        try {
            byteBuf = Base64.encode(Unpooled.wrappedBuffer(authString.getBytes(CharsetUtil.UTF_8)));
            basicAuth = byteBuf.getCharSequence(0, byteBuf.capacity(), CharsetUtil.UTF_8).toString();
        } finally {
            if (byteBuf != null) {
                byteBuf.release();
                byteBuf = null;
            }
        }
    }

    // Always use this as sendHttpRequest(GET/POST/PUT/DELETE, http://192.168.7.6/foo/bar,false)//
    // The authHandler will use this method with useAuth as true when needed.
    public boolean sendHttpRequest(String httpMethod, String httpRequestURL, boolean useAuth) {

        this.httpMethod = httpMethod; // AuthHandler needs this for when I switch to full async.

        if (mainBootstrap == null) {
            mainBootstrap = new Bootstrap();
            mainBootstrap.group(mainEventLoopGroup);
            mainBootstrap.channel(NioSocketChannel.class);
            mainBootstrap.option(ChannelOption.SO_KEEPALIVE, true);
            mainBootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000);
            mainBootstrap.option(ChannelOption.SO_SNDBUF, 1024 * 128);
            mainBootstrap.option(ChannelOption.SO_RCVBUF, 1024 * 1024);
            mainBootstrap.option(ChannelOption.TCP_NODELAY, true);
            mainBootstrap.handler(new ChannelInitializer<SocketChannel>() {

                @Override
                public void initChannel(SocketChannel socketChannel) throws Exception {
                    // RtspResponseDecoder //RtspRequestEncoder // try in the pipeline soon//
                    socketChannel.pipeline().addLast(new HttpClientCodec());
                    socketChannel.pipeline().addLast(new HttpContentDecompressor());
                    socketChannel.pipeline().addLast(new MyNettyAuthHandler(username, password, thing.getHandler()));
                    socketChannel.pipeline().addLast(new CommonCameraHandler());

                    switch (thing.getThingTypeUID().getId()) {
                        case "AMCREST":
                            socketChannel.pipeline().addLast(new AmcrestHandler());
                            break;
                        case "FOSCAM":
                            socketChannel.pipeline().addLast(new FoscamHandler());
                            break;
                        case "HIKVISION":
                            socketChannel.pipeline().addLast(new HikvisionHandler());
                            break;
                    }
                }
            });
        }

        try {
            URI uri = new URI(httpRequestURL);

            if (uri.getRawQuery() == null) {
                correctedRequestURL = uri.getPath();
            } else {
                correctedRequestURL = uri.getPath() + "?" + uri.getRawQuery();
            }

            // Configure SSL context if necessary. //Code not finished yet//
            if ("https".equalsIgnoreCase(scheme)) {
                final SslContext sslCtx;
                try {
                    sslCtx = SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
                    if (sslCtx != null) {
                        // need to add something like this to the pipe line
                        // socketChannel.pipeline().addFirst(sslCtx.newHandler(ch.alloc()));
                    }
                } catch (SSLException e) {
                    logger.error("Exception occured when trying to create an SSL for HTTPS:{}", e);
                }
            }

            logger.debug("Trying to connect with new request for camera at IP:{}", ipAddress);
            mainChFuture = mainBootstrap.connect(new InetSocketAddress(ipAddress, port));
            mainChFuture.awaitUninterruptibly(10000);
            if (!mainChFuture.isSuccess()) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                        "Timeout occured when trying to reach the Cameras IP and PORT: Check your IP ADDRESS is correct and the camera can be reached.");
                logger.error(
                        "Can not connect to the camera at {}:{} check your network for issues or change cameras settings.",
                        ipAddress, port);
                dispose();
                cameraConnectionJob = cameraConnection.scheduleAtFixedRate(pollingCameraConnection, 10, 60,
                        TimeUnit.SECONDS);
                return false;
            }

            ch = mainChFuture.channel();

            HttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, new HttpMethod(httpMethod),
                    correctedRequestURL);
            request.headers().set(HttpHeaderNames.HOST, ipAddress);
            request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            request.headers().set(HttpHeaderNames.ACCEPT_ENCODING, HttpHeaderValues.GZIP);

            logger.debug("+ The request is going to be {}:{}:", httpMethod, correctedRequestURL);

            if (useDigestAuth && useAuth) {
                // logger.debug("Send using this header:{}", digestString);
                request.headers().set(HttpHeaderNames.AUTHORIZATION, "Digest " + digestString);
            }

            if (basicAuth != null) {
                if (useDigestAuth) {
                    logger.warn("Camera at IP:{} had both Basic and Digest set to be used", ipAddress);
                    setBasicAuth(false);
                } else {
                    logger.debug("Camera at IP:{} is using Basic Auth", ipAddress);
                    request.headers().set(HttpHeaderNames.AUTHORIZATION, "Basic " + basicAuth);
                }
            }

            ch.writeAndFlush(request);
            // wait for camera to reply and close the connection after 4 seconds//
            mainChFuture = ch.closeFuture();
            mainChFuture.awaitUninterruptibly(4000);

            // Get the handler instance to retrieve the answer.
            // ChannelHandler handler = ch.pipeline().last();

            // Cleanup
            request = null;
            uri = null;

            if (!mainChFuture.isSuccess()) {
                logger.warn("Camera at {}:{} is not closing the connection quick enough.", ipAddress, port);
                // mainChFuture.cancel(true);
                // mainChFuture = null;
                ch.close();// force close to prevent the thread getting locked.
                return false;
            }

        } catch (URISyntaxException e) {
            logger.error("Following error occured:{}", e);
        }

        return true;
    }

    // These methods handle the response from all Camera brands, nothing specific to any brand should be in here //
    private class CommonCameraHandler extends ChannelDuplexHandler {
        private int bytesToRecieve = 0;
        private int bytesAlreadyRecieved = 0;
        private byte[] lastSnapshot;
        private String contentType;
        private Object reply = null;

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            logger.debug(msg.toString()); // Helpful to have this when getting users to try new features.

            if (msg instanceof HttpResponse) {
                HttpResponse response = (HttpResponse) msg;
                if (!response.headers().isEmpty()) {
                    for (CharSequence name : response.headers().names()) {
                        for (CharSequence value : response.headers().getAll(name)) {
                            if (name.toString().equalsIgnoreCase("Content-Type")) {
                                contentType = value.toString();
                            } else if (name.toString().equalsIgnoreCase("Content-Length")) {
                                bytesToRecieve = Integer.parseInt(value.toString());
                            }
                        }
                    }
                }
            }
            if (msg instanceof HttpContent) {
                HttpContent content = (HttpContent) msg;

                // If it is not an image send it on to the next handler//
                if (!"image/jpeg".equalsIgnoreCase(contentType)) {
                    reply = content.content().toString(CharsetUtil.UTF_8);
                    content.content().release();
                    bytesAlreadyRecieved = 0;
                    lastSnapshot = null;
                    super.channelRead(ctx, reply);
                }

                if (content instanceof LastHttpContent) {
                    ctx.close();
                }

                if (content instanceof DefaultHttpContent) {
                    if ("image/jpeg".equalsIgnoreCase(contentType)) {
                        for (int i = 0; i < content.content().capacity(); i++) {
                            if (lastSnapshot == null) {
                                lastSnapshot = new byte[bytesToRecieve];
                            }
                            lastSnapshot[bytesAlreadyRecieved++] = content.content().getByte(i);
                        }
                        content.content().release();// must be here or a memory leak occurs.
                        if (bytesAlreadyRecieved >= bytesToRecieve) {
                            if (bytesToRecieve != bytesAlreadyRecieved) {
                                logger.error(
                                        "We got too many packets back from the camera for some reason, please report this.");
                            }
                            updateState(CHANNEL_IMAGE, new RawType(lastSnapshot, "image/jpeg"));
                            bytesAlreadyRecieved = 0;
                            lastSnapshot = null;
                            ctx.close();
                        }
                    }
                }
            }
        }

        @Override
        public void channelReadComplete(ChannelHandlerContext ctx) {
        }

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
        }

        @Override
        public void handlerRemoved(ChannelHandlerContext ctx) {
            lastSnapshot = null;
            contentType = null;
            reply = null;
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            logger.debug("!!! Camera may have closed the connection which can be normal. Cause reported is:{}", cause);
            ctx.close();
        }
    }

    public class AmcrestHandler extends ChannelDuplexHandler {

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            String content = msg.toString();
            logger.debug("HTTP Result back from camera is :{}:", content);

            switch (content) {
                case "Error: No Events\r\n":
                    if (channelCheckingNow.contains("motion")) {
                        updateState(CHANNEL_MOTION_ALARM, OnOffType.valueOf("OFF"));
                        firstMotionAlarm = false;
                    } else {
                        updateState(CHANNEL_AUDIO_ALARM, OnOffType.valueOf("OFF"));
                        firstAudioAlarm = false;
                    }
                    break;

                case "channels[0]=0\r\n":
                    if (channelCheckingNow.contains("motion")) {
                        motionDetected();
                    } else {
                        audioDetected();
                    }
                    break;
            }

            if (content.contains("table.MotionDetect[0].Enable=false")) {
                updateState(CHANNEL_ENABLE_MOTION_ALARM, OnOffType.valueOf("OFF"));
            } else if (content.contains("table.MotionDetect[0].Enable=true")) {
                updateState(CHANNEL_ENABLE_MOTION_ALARM, OnOffType.valueOf("ON"));
            }
            ctx.close();
            content = null;
        }
    }

    public class FoscamHandler extends ChannelDuplexHandler {

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            String content = msg.toString();
            logger.debug("HTTP Result back from camera is :{}:", content);

            ////////////// Motion Alarm //////////////
            if (content.contains("<motionDetectAlarm>0</motionDetectAlarm>")) {
                updateState(CHANNEL_ENABLE_MOTION_ALARM, OnOffType.valueOf("OFF"));
            }
            if (content.contains("<motionDetectAlarm>1</motionDetectAlarm>")) { // means it is enabled but no alarm
                updateState(CHANNEL_MOTION_ALARM, OnOffType.valueOf("OFF"));
                updateState(CHANNEL_ENABLE_MOTION_ALARM, OnOffType.valueOf("ON"));
                firstMotionAlarm = false;
            }
            if (content.contains("<motionDetectAlarm>2</motionDetectAlarm>")) {// means it is enabled and alarm on
                updateState(CHANNEL_MOTION_ALARM, OnOffType.valueOf("ON"));
                updateState(CHANNEL_ENABLE_MOTION_ALARM, OnOffType.valueOf("ON"));
                if (updateImageEvents.contains("2") && !firstMotionAlarm) {
                    sendHttpRequest("GET", snapshotUri, false);
                    firstMotionAlarm = true;
                } else if (updateImageEvents.contains("4")) {
                    sendHttpRequest("GET", snapshotUri, false);
                }
            }

            ////////////// Sound Alarm //////////////
            if (content.contains("<soundAlarm>0</soundAlarm>")) {
                updateState(CHANNEL_AUDIO_ALARM, OnOffType.valueOf("OFF"));
                updateState(CHANNEL_ENABLE_AUDIO_ALARM, OnOffType.valueOf("OFF"));
                firstAudioAlarm = false;
            }
            if (content.contains("<soundAlarm>1</soundAlarm>")) {
                updateState(CHANNEL_AUDIO_ALARM, OnOffType.valueOf("OFF"));
                updateState(CHANNEL_ENABLE_AUDIO_ALARM, OnOffType.valueOf("ON"));
                firstAudioAlarm = false;
            }
            if (content.contains("<soundAlarm>2</soundAlarm>")) {
                updateState(CHANNEL_AUDIO_ALARM, OnOffType.valueOf("ON"));
                updateState(CHANNEL_ENABLE_AUDIO_ALARM, OnOffType.valueOf("ON"));
                if (updateImageEvents.contains("3") && !firstAudioAlarm) {
                    sendHttpRequest("GET", snapshotUri, false);
                    firstAudioAlarm = true;
                } else if (updateImageEvents.contains("5")) {
                    sendHttpRequest("GET", snapshotUri, false);
                }
            }

            ////////////// Sound Threshold //////////////
            if (content.contains("<sensitivity>0</sensitivity>")) {
                updateState(CHANNEL_THRESHOLD_AUDIO_ALARM, PercentType.valueOf("0"));
            }
            if (content.contains("<sensitivity>1</sensitivity>")) {
                updateState(CHANNEL_THRESHOLD_AUDIO_ALARM, PercentType.valueOf("50"));
            }
            if (content.contains("<sensitivity>2</sensitivity>")) {
                updateState(CHANNEL_THRESHOLD_AUDIO_ALARM, PercentType.valueOf("100"));
            }
            ctx.close();
            content = null;
        }
    }

    public class HikvisionHandler extends ChannelDuplexHandler {

        // this fetches what Alarms the camera supports//
        // sendHttpRequest("GET", "http://192.168.1.108/ISAPI/Event/triggers", false);

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            String content = msg.toString();
            logger.debug("HTTP Result back from camera is :{}:", content);

            if (content.contains("<eventType>VMD</eventType>\r\n<eventState>active</eventState>\r\n")) {
                motionDetected();
            }
            if (content.contains("<eventType>VMD</eventType>\r\n<eventState>inactive</eventState>\r\n")) {
                updateState(CHANNEL_MOTION_ALARM, OnOffType.valueOf("OFF"));
                firstMotionAlarm = false;
            }
            // determine if the motion detection is turned on or off.
            if (content.contains("<MotionDetection version=\"")) {

                if (content.contains("<enabled>true</enabled>")) {
                    updateState(CHANNEL_ENABLE_MOTION_ALARM, OnOffType.valueOf("ON"));

                } else if (content.contains("<enabled>false</enabled>")) {
                    updateState(CHANNEL_ENABLE_MOTION_ALARM, OnOffType.valueOf("OFF"));

                }
            }

            ctx.close();
            content = null;
        }
    }

    public IpCameraHandler(Thing thing) {
        super(thing);
    }

    private void motionDetected() {
        updateState(CHANNEL_MOTION_ALARM, OnOffType.valueOf("ON"));
        if (updateImageEvents.contains("2") && !firstMotionAlarm) {
            sendHttpRequest("GET", snapshotUri, false);
            firstMotionAlarm = true;
        } else if (updateImageEvents.contains("4")) {
            sendHttpRequest("GET", snapshotUri, false);
        }
    }

    private void audioDetected() {
        updateState(CHANNEL_AUDIO_ALARM, OnOffType.valueOf("ON"));
        if (updateImageEvents.contains("3") && !firstAudioAlarm) {
            sendHttpRequest("GET", snapshotUri, false);
            firstAudioAlarm = true;
        } else if (updateImageEvents.contains("5")) {
            sendHttpRequest("GET", snapshotUri, false);
        }
    }

    private PTZVector getPosition() {
        try {
            ptzLocation = ptzDevices.getPosition(profileToken);
            if (ptzLocation != null) {
                return ptzLocation;
            } else {
                logger.error("Camera replied with null when asked what its position was");
            }
        } catch (NullPointerException e) {
            logger.error("NPE occured when trying to fetch the cameras PTZ position");
        }

        PTZVector pv = new PTZVector();
        pv.setPanTilt(new Vector2D());
        pv.setZoom(new Vector1D());
        return pv;
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {

        if (command.toString() == "REFRESH") {

            switch (channelUID.getId()) {
                case CHANNEL_THRESHOLD_AUDIO_ALARM:
                    switch (thing.getThingTypeUID().getId()) {
                        case "FOSCAM":
                            sendHttpRequest("GET",
                                    "http://192.168.1.108/cgi-bin/CGIProxy.fcgi?cmd=getAudioAlarmConfig&usr=" + username
                                            + "&pwd=" + password,
                                    false);
                            break;
                    }
                    break;
                case CHANNEL_ENABLE_AUDIO_ALARM:
                    switch (thing.getThingTypeUID().getId()) {
                        case "FOSCAM":
                            sendHttpRequest("GET",
                                    "http://192.168.1.108/cgi-bin/CGIProxy.fcgi?cmd=getAudioAlarmConfig&usr=" + username
                                            + "&pwd=" + password,
                                    false);
                            break;
                    }
                    break;
                case CHANNEL_ENABLE_MOTION_ALARM:
                    switch (thing.getThingTypeUID().getId()) {

                        case "AMCREST":
                            sendHttpRequest("GET",
                                    "http://192.168.1.108/cgi-bin/configManager.cgi?action=getConfig&name=MotionDetect",
                                    false);
                            break;

                        case "FOSCAM":
                            sendHttpRequest("GET", "http://192.168.1.108/cgi-bin/CGIProxy.fcgi?cmd=getDevState&usr="
                                    + username + "&pwd=" + password, false);
                            break;
                    }

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

            case CHANNEL_ALPHA_TEST:

                if (thing.getThingTypeUID().getId().contentEquals("AMCREST")) {
                    // Used to test if a forgotten stream is auto closed
                    sendHttpRequest("GET", "http://192.168.1.50/cgi-bin/configManager.cgi?action=getConfig&name=Snap",
                            false);

                    sendHttpRequest("GET",
                            "http://192.168.1.50/cgi-bin/audio.cgi?action=getAudio&httptype=singlepart&channel=1",
                            false);

                } else if (thing.getThingTypeUID().getId().contentEquals("HIKVISION")) {
                    sendHttpRequest("GET", "http://192.168.1.108/ISAPI/Event/notification/alertStream", false);
                }

                break;

            case CHANNEL_UPDATE_IMAGE_NOW:
                if (snapshotUri != null) {
                    sendHttpRequest("GET", snapshotUri, false);
                }

                break;

            case CHANNEL_THRESHOLD_AUDIO_ALARM:

                switch (thing.getThingTypeUID().getId()) {

                    case "FOSCAM":
                        int value = Math.round(Float.valueOf(command.toString()));
                        if (value == 0) {
                            sendHttpRequest("GET",
                                    "http://192.168.1.108/cgi-bin/CGIProxy.fcgi?cmd=setAudioAlarmConfig&isEnable=0&usr="
                                            + username + "&pwd=" + password,
                                    false);
                        } else if (value <= 33) {
                            sendHttpRequest("GET",
                                    "http://192.168.1.108/cgi-bin/CGIProxy.fcgi?cmd=setAudioAlarmConfig&isEnable=1&sensitivity=0&usr="
                                            + username + "&pwd=" + password,
                                    false);
                        } else if (value <= 66) {
                            sendHttpRequest("GET",
                                    "http://192.168.1.108/cgi-bin/CGIProxy.fcgi?cmd=setAudioAlarmConfig&isEnable=1&sensitivity=1&usr="
                                            + username + "&pwd=" + password,
                                    false);
                        } else {
                            sendHttpRequest("GET",
                                    "http://192.168.1.108/cgi-bin/CGIProxy.fcgi?cmd=setAudioAlarmConfig&isEnable=1&sensitivity=2&usr="
                                            + username + "&pwd=" + password,
                                    false);
                        }

                        break;
                }

                break;

            case CHANNEL_ENABLE_AUDIO_ALARM:

                switch (thing.getThingTypeUID().getId()) {

                    case "FOSCAM":

                        if ("ON".equals(command.toString())) {
                            sendHttpRequest("GET",
                                    "http://192.168.1.108/cgi-bin/CGIProxy.fcgi?cmd=setAudioAlarmConfig&isEnable=1&usr="
                                            + username + "&pwd=" + password,
                                    false);
                        } else {
                            sendHttpRequest("GET",
                                    "http://192.168.1.108/cgi-bin/CGIProxy.fcgi?cmd=setAudioAlarmConfig&isEnable=0&usr="
                                            + username + "&pwd=" + password,
                                    false);
                        }
                        break;
                }

                break;
            case CHANNEL_ENABLE_MOTION_ALARM:

                switch (thing.getThingTypeUID().getId()) {
                    case "AMCREST":
                        if ("ON".equals(command.toString())) {
                            sendHttpRequest("GET",
                                    "http://192.168.1.108/cgi-bin/configManager.cgi?action=setConfig&MotionDetect[0].Enable=true",
                                    false);
                        } else {
                            sendHttpRequest("GET",
                                    "http://192.168.1.108/cgi-bin/configManager.cgi?action=setConfig&MotionDetect[0].Enable=false",
                                    false);
                        }
                        break;

                    case "FOSCAM":
                        if ("ON".equals(command.toString())) {
                            sendHttpRequest("GET",
                                    "http://192.168.1.108/cgi-bin/CGIProxy.fcgi?cmd=setMotionDetectConfig&isEnable=1&usr="
                                            + username + "&pwd=" + password,
                                    false);
                        } else {
                            sendHttpRequest("GET",
                                    "http://192.168.1.108/cgi-bin/CGIProxy.fcgi?cmd=setMotionDetectConfig&isEnable=0&usr="
                                            + username + "&pwd=" + password,
                                    false);
                        }
                        break;
                    case "HIKVISION":
                        if ("ON".equals(command.toString())) {
                            sendHttpRequest("GET", "http://192.168.1.108/MotionDetection/1", false);

                        } else {
                            sendHttpRequest("GET", "http://192.168.1.108/MotionDetection/1", false);
                        }
                }

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

            if (thing.getThingTypeUID().getId().equals("HTTPONLY")) {

                if (!snapshotUri.isEmpty()) {
                    logger.debug("Camera at {} has a snapshot address of:{}:", ipAddress, snapshotUri);
                    if (sendHttpRequest("GET", snapshotUri, false)) {
                        updateStatus(ThingStatus.ONLINE);
                        cameraConnectionJob.cancel(true);
                        cameraConnectionJob = null;

                        fetchCameraOutputJob = fetchCameraOutput.scheduleAtFixedRate(pollingCamera, 5000,
                                Integer.parseInt(config.get(CONFIG_POLL_CAMERA_MS).toString()), TimeUnit.MILLISECONDS);
                        sendHttpRequest("GET", snapshotUri, false);
                        updateState(CHANNEL_IMAGE_URL, new StringType(snapshotUri));
                    }
                } else {
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                            "Can not find a valid url, check camera setup settings by clicking on the pencil icon in PaperUI.");
                    logger.error(" Camera at IP {} has no url entered in its camera setup.", ipAddress);
                }

                return;
            } /////////////// end of HTTPONLY connection maker//

            if (onvifCamera == null) {

                try {
                    logger.info("About to connect to the IP Camera using the ONVIF PORT at IP:{}:{}", ipAddress,
                            config.get(CONFIG_ONVIF_PORT).toString());

                    if (username != null && password != null) {
                        onvifCamera = new OnvifDevice(ipAddress + ":" + config.get(CONFIG_ONVIF_PORT).toString(),
                                username, password);
                    } else {
                        onvifCamera = new OnvifDevice(ipAddress + ":" + config.get(CONFIG_ONVIF_PORT).toString());
                    }

                    logger.info("Checking the selected Media Profile is a valid number.");
                    profiles = onvifCamera.getDevices().getProfiles();
                    if (profiles == null) {
                        logger.error("Camera replied with NULL when trying to get a list of the media profiles");
                    }

                    if (selectedMediaProfile > profiles.size()) {
                        logger.warn(
                                "The selected Media Profile in the binding is higher than the max supported profiles. Changing to use Media Profile 0.");
                        selectedMediaProfile = 0;
                    }

                    logger.info("Fetching the snapshot URL for the selected Media Profile.");
                    profileToken = profiles.get(selectedMediaProfile).getToken();
                    if (profileToken == null) {
                        logger.error("Camera replied with NULL when trying to get a media profile token.");
                    }

                    if (snapshotUri == null) {
                        snapshotUri = onvifCamera.getMedia().getSnapshotUri(profileToken);
                    }

                    logger.info("About to fetch some information about the Media Profiles from the camera");
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

                    logger.info("About to interrogate the camera to see if it supports PTZ.");

                    ptzDevices = onvifCamera.getPtz();
                    if (ptzDevices != null) {

                        if (ptzDevices.isPtzOperationsSupported(profileToken)
                                && ptzDevices.isAbsoluteMoveSupported(profileToken)) {

                            panRange = ptzDevices.getPanSpaces(profileToken);
                            tiltRange = ptzDevices.getTiltSpaces(profileToken);
                            zoomMin = ptzDevices.getZoomSpaces(profileToken).getMin();
                            zoomMax = ptzDevices.getZoomSpaces(profileToken).getMax();

                            logger.info("Camera is reporting it supports PTZ controls via ONVIF");
                            if (logger.isDebugEnabled()) {
                                logger.debug("The camera can Pan  from {} to {}", panRange.getMin(), panRange.getMax());
                                logger.debug("The camera can Tilt from {} to {}", tiltRange.getMin(),
                                        tiltRange.getMax());
                                logger.debug("The camera can Zoom from {} to {}", zoomMin, zoomMax);
                            }
                            ptzLocation = getPosition();

                        } else {
                            logger.info("Camera is reporting that it does NOT support Absolute PTZ controls via ONVIF");
                            // null will stop code from running on cameras that do not support PTZ features.
                            ptzDevices = null;
                        }
                    }
                    logger.info(
                            "Finished with PTZ, now reporting what Video URL's the camera supports which can only be seen in DEBUG logging.");
                    videoStreamUri = onvifCamera.getMedia().getRTSPStreamUri(profileToken);

                } catch (ConnectException e) {
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                            "Can not connect to camera with ONVIF: Check that IP ADDRESS, ONVIF PORT, USERNAME and PASSWORD are correct.");
                    logger.error(
                            "Can not connect to camera with ONVIF at IP:{}, it may be the wrong ONVIF_PORT. Fault was {}",
                            ipAddress, e.toString());
                } catch (SOAPException e) {
                    logger.error(
                            "The camera connection had a SOAP error, this may indicate your camera does not fully ONVIF or is an older version. Not to worry, we will still try and connect. Camera at IP:{}, fault was {}",
                            ipAddress, e.toString());
                } catch (NullPointerException e) {
                    logger.error("NPE occured when trying to connect to the camera with ONVIF");
                }

                if (snapshotUri != null) {

                    // Disable PTZ if it failed during setting up PTZ
                    // if (ptzLocation == null) {
                    // ptzDevices = null;
                    // }

                    updateState(CHANNEL_IMAGE_URL, new StringType(snapshotUri));
                    if (videoStreamUri != null) {
                        updateState(CHANNEL_VIDEO_URL, new StringType(videoStreamUri));
                    }
                    cameraConnectionJob.cancel(true);
                    cameraConnectionJob = null;
                    sendHttpRequest("GET", snapshotUri, false);
                    sendHttpRequest("GET", snapshotUri, false);
                    updateStatus(ThingStatus.ONLINE);

                    fetchCameraOutputJob = fetchCameraOutput.scheduleAtFixedRate(pollingCamera, 5000,
                            Integer.parseInt(config.get(CONFIG_POLL_CAMERA_MS).toString()), TimeUnit.MILLISECONDS);

                } else {
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                            "Camera failed to report a valid Snaphot URL, try over-riding the Snapshot URL auto detection by entering a known URL.");
                    logger.error(
                            "Camera failed to report a valid Snaphot URL, try over-riding the Snapshot URL auto detection by entering a known URL.");

                }

            }
        }
    };

    Runnable pollingCamera = new Runnable() {
        @Override
        public void run() {

            if (snapshotUri != null && updateImageEvents.contains("1")) {
                sendHttpRequest("GET", snapshotUri, false);
            }

            switch (thing.getThingTypeUID().getId()) {
                case "AMCREST":
                    channelCheckingNow = "motionAlarm";
                    sendHttpRequest("GET",
                            "http://192.168.1.108/cgi-bin/eventManager.cgi?action=getEventIndexes&code=VideoMotion",
                            false);
                    channelCheckingNow = "audioAlarm";
                    sendHttpRequest("GET",
                            "http://192.168.1.108/cgi-bin/eventManager.cgi?action=getEventIndexes&code=AudioMutation",
                            false);
                    break;

                case "FOSCAM":
                    sendHttpRequest("GET", "http://192.168.1.108/cgi-bin/CGIProxy.fcgi?cmd=getDevState&usr=" + username
                            + "&pwd=" + password, false);
                    break;
                case "HIKVISION":
                    // check to see if motion alarm is turned on or off
                    sendHttpRequest("GET", "http://192.168.1.108/MotionDetection/1", false);

                    break;
            }
        }
    };

    @Override
    public void initialize() {
        config = thing.getConfiguration();
        ipAddress = config.get(CONFIG_IPADDRESS).toString();

        logger.debug("Getting configuration to initialize a new IP Camera at IP {}", ipAddress);
        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_PENDING,
                "Making a new connection to the camera now.");

        port = Integer.parseInt(config.get(CONFIG_PORT).toString());
        username = (config.get(CONFIG_USERNAME) == null) ? null : config.get(CONFIG_USERNAME).toString();
        password = (config.get(CONFIG_PASSWORD) == null) ? null : config.get(CONFIG_PASSWORD).toString();
        if (config.get(CONFIG_USE_HTTPS) != null) {
            scheme = (config.get(CONFIG_USE_HTTPS).equals(true)) ? "https" : "http";
        }
        snapshotUri = (config.get(CONFIG_SNAPSHOT_URL_OVERIDE) == null) ? null
                : config.get(CONFIG_SNAPSHOT_URL_OVERIDE).toString();
        selectedMediaProfile = (config.get(CONFIG_ONVIF_PROFILE_NUMBER) == null) ? 0
                : Integer.parseInt(config.get(CONFIG_ONVIF_PROFILE_NUMBER).toString());
        updateImageEvents = config.get(CONFIG_IMAGE_UPDATE_EVENTS).toString();

        cameraConnectionJob = cameraConnection.scheduleAtFixedRate(pollingCameraConnection, 0, 60, TimeUnit.SECONDS);
    }

    @Override
    public void dispose() {
        logger.debug("Camera dispose called, about to remove/change or fix a Cameras connection or settings.");

        onvifCamera = null;

        if (cameraConnectionJob != null) {
            cameraConnectionJob.cancel(true);
            cameraConnectionJob = null;
        }
        if (fetchCameraOutputJob != null) {
            fetchCameraOutputJob.cancel(true);
            fetchCameraOutputJob = null;
        }
        // mainBootstrap = null;
        // secondBootstrap = null;
    }
}
