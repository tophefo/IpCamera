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
import io.netty.channel.ChannelFutureListener;
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
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.CharsetUtil;

/**
 * The {@link IpCameraHandler} is responsible for handling commands, which are
 * sent to one of the channels.
 *
 * @author Matthew Skinner - Initial contribution
 */

public class IpCameraHandler extends BaseThingHandler {

    public static final Set<ThingTypeUID> SUPPORTED_THING_TYPES = new HashSet<ThingTypeUID>(
            Arrays.asList(THING_TYPE_ONVIF, THING_TYPE_HTTPONLY, THING_TYPE_AMCREST, THING_TYPE_DAHUA,
                    THING_TYPE_INSTAR, THING_TYPE_AXIS, THING_TYPE_FOSCAM, THING_TYPE_HIKVISION));

    private Configuration config;
    private OnvifDevice onvifCamera;
    private List<Profile> profiles;
    private String username;
    private String password;
    private ScheduledFuture<?> cameraConnectionJob = null;
    private ScheduledFuture<?> fetchCameraOutputJob = null;
    private int selectedMediaProfile = 0;
    private Bootstrap mainBootstrap;
    CommonCameraHandler commonHandler;
    byte countHandlers = 0;
    private String globalMethod;
    private String globalUrl;

    private EventLoopGroup mainEventLoopGroup = new NioEventLoopGroup();

    private String scheme = "http";
    private PTZVector ptzLocation;

    // basicAuth MUST remain private as it holds the password
    private String basicAuth = null;
    public boolean useDigestAuth = false;

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final ScheduledExecutorService cameraConnection = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService fetchCameraOutput = Executors.newSingleThreadScheduledExecutor();
    private String snapshotUri = null;
    private String videoStreamUri = "ONVIF failed, so PTZ will also not work.";
    public String ipAddress = "empty";
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

    // false clears the stored hash of the user/pass
    // true creates the hash
    public void setBasicAuth(boolean useBasic) {

        if (useBasic == false) {
            logger.debug("Removing BASIC auth now and making it NULL.");
            basicAuth = null;
            return;
        }
        logger.debug("Setting up the BASIC auth now, this should only happen once.");
        if (username != null && password != null) {
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
        } else {
            logger.error("Camera is asking for Basic Auth when you have not provided a username and/or password !");
        }
    }

    private String getCorrectUrlFormat(String url) {

        String temp = "Error with URL";
        URI uri;
        try {
            uri = new URI(url);
            if (uri.getRawQuery() == null) {
                temp = uri.getPath();
            } else {
                temp = uri.getPath() + "?" + uri.getRawQuery();
            }
        } catch (URISyntaxException e1) {
            logger.error("a non valid url was given to the binding {} - {}", url, e1);
        }
        return temp;
    }

    // Always use this as sendHttpRequest(GET/POST/PUT/DELETE, "/foo/bar",null)//
    // The authHandler will use this method with a digest string as needed.
    public boolean sendHttpRequest(String httpMethod, String httpRequestURL, String digestString) {

        globalMethod = httpMethod;
        globalUrl = httpRequestURL;

        Channel ch;
        ChannelFuture openChFuture;
        ChannelFuture flushChFuture;
        ChannelFuture closeChFuture;

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
                    // IdleStateHandler(readerIdleTime,writerIdleTime,allIdleTime)
                    socketChannel.pipeline().addLast("idleStateHandler", new IdleStateHandler(5, 5, 5));
                    socketChannel.pipeline().addLast(new HttpClientCodec());
                    socketChannel.pipeline().addLast(new HttpContentDecompressor());
                    socketChannel.pipeline().addLast(
                            new MyNettyAuthHandler(username, password, globalMethod, globalUrl, thing.getHandler()));
                    socketChannel.pipeline().addLast(commonHandler = new CommonCameraHandler(globalMethod, globalUrl));

                    switch (thing.getThingTypeUID().getId()) {
                        case "AMCREST":
                            socketChannel.pipeline().addLast(new AmcrestHandler(globalUrl));
                            break;
                        case "FOSCAM":
                            socketChannel.pipeline().addLast(new FoscamHandler());
                            break;
                        case "HIKVISION":
                            socketChannel.pipeline().addLast(new HikvisionHandler());
                            break;
                        case "INSTAR":
                            socketChannel.pipeline().addLast(new InstarHandler());
                            break;
                        case "DAHUA":
                            socketChannel.pipeline().addLast(new DahuaHandler());
                            break;
                    }
                }
            });
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

        HttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, new HttpMethod(httpMethod),
                httpRequestURL);
        request.headers().set(HttpHeaderNames.HOST, ipAddress);
        // request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        request.headers().set(HttpHeaderNames.ACCEPT_ENCODING, HttpHeaderValues.GZIP);

        if (useDigestAuth && digestString != null) {
            logger.debug("This time the request is using DIGEST");
            request.headers().set(HttpHeaderNames.AUTHORIZATION, "Digest " + digestString);
        }

        if (basicAuth != null) {
            if (useDigestAuth) {
                logger.warn("Camera at IP:{} had both Basic and Digest set to be used", ipAddress);
                setBasicAuth(false);
            } else {
                request.headers().set(HttpHeaderNames.AUTHORIZATION, "Basic " + basicAuth);
            }
        }

        logger.debug("Connecting to camera at IP:{} with request {}:{}", ipAddress, httpMethod, httpRequestURL);

        openChFuture = mainBootstrap.connect(new InetSocketAddress(ipAddress, port));
        openChFuture.awaitUninterruptibly();

        if (!openChFuture.isSuccess()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "Connection Timeout: Check your IP is correct and the camera can be reached.");
            logger.error("Can not connect to the camera at {}:{} check your network for issues.", ipAddress, port);
            dispose();

            cameraConnectionJob = cameraConnection.scheduleAtFixedRate(pollingCameraConnection, 10, 60,
                    TimeUnit.SECONDS);
            return false;
        }

        ch = openChFuture.channel();
        flushChFuture = ch.writeAndFlush(request);
        // Cleanup
        request = null;

        flushChFuture.addListener(commonHandler.flushListener);
        closeChFuture = ch.closeFuture();
        closeChFuture.addListener(commonHandler.closeChannelListener);
        closeChFuture.awaitUninterruptibly(4000);

        if (!closeChFuture.isSuccess()) {
            logger.warn("Camera at {}:{} is not closing the connection quick enough.", ipAddress, port);
            ch.close();// force close to prevent the thread getting locked.
            return false;
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
        private String requestMethod, requestUrl;

        CommonCameraHandler(String httpMethod, String url) {
            requestMethod = httpMethod;
            requestUrl = url;
        }

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
            logger.debug("++++++++ Handler {} created ++++++++ :{}", ++countHandlers, requestUrl);
        }

        @Override
        public void handlerRemoved(ChannelHandlerContext ctx) {
            lastSnapshot = null;
            contentType = null;
            reply = null;
            logger.debug("------- Closing Handler, leaving {} handlers alive ------- :{}", --countHandlers, requestUrl);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            logger.debug("!!! Camera may have closed the connection which can be normal. Cause reported is:{}", cause);
            ctx.close();
        }

        private final ChannelFutureListener flushListener = new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                if (future.isSuccess()) {
                    logger.debug("New request has been sent to camera.");
                } else {
                    future.cause().printStackTrace();
                    future.channel().close();
                }
            }
        };

        private final ChannelFutureListener closeChannelListener = new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                if (future.isSuccess()) {
                    logger.debug("Connection to camera is closed. {}:{}", requestMethod, requestUrl);
                    if (reply != null && !reply.toString().isEmpty()) {
                        logger.debug("Camera replied to this request with :{}", reply);
                    }
                } else {
                    logger.error("!!!!!!!!!!!!!! something is stoping the connection closing {}",
                            future.cause().toString());
                    future.channel().close();
                }
            }
        };
    }

    private class AmcrestHandler extends ChannelDuplexHandler {
        String content;
        String requestUrl;

        AmcrestHandler(String url) {
            requestUrl = url;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            content = msg.toString();
            if (!content.isEmpty()) {
                logger.debug("HTTP Result back from camera is :{}:", content);
            }

            switch (content) {
                case "Error: No Events\r\n":
                    if (requestUrl.equals("/cgi-bin/eventManager.cgi?action=getEventIndexes&code=VideoMotion")) {
                        updateState(CHANNEL_MOTION_ALARM, OnOffType.valueOf("OFF"));
                        firstMotionAlarm = false;
                    } else {
                        updateState(CHANNEL_AUDIO_ALARM, OnOffType.valueOf("OFF"));
                        firstAudioAlarm = false;
                    }
                    break;

                case "channels[0]=0\r\n":
                    if (requestUrl.equals("/cgi-bin/eventManager.cgi?action=getEventIndexes&code=VideoMotion")) {
                        motionDetected(CHANNEL_MOTION_ALARM);
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
        }

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            logger.debug("++++++++ Amcrest Handler created ++++++++ {}", requestUrl);
        }

        @Override
        public void handlerRemoved(ChannelHandlerContext ctx) {
            content = null;
        }
    }

    private class FoscamHandler extends ChannelDuplexHandler {

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
                    sendHttpRequest("GET", getCorrectUrlFormat(snapshotUri), null);
                    firstMotionAlarm = true;
                } else if (updateImageEvents.contains("4")) {
                    sendHttpRequest("GET", getCorrectUrlFormat(snapshotUri), null);
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
                    sendHttpRequest("GET", getCorrectUrlFormat(snapshotUri), null);
                    firstAudioAlarm = true;
                } else if (updateImageEvents.contains("5")) {
                    sendHttpRequest("GET", getCorrectUrlFormat(snapshotUri), null);
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

    private class InstarHandler extends ChannelDuplexHandler {

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            String content = msg.toString();
            logger.debug("HTTP Result back from camera is :{}:", content);

            // Audio Alarm
            String aa_enable = searchString(content, "var aa_enable = \"");
            if (aa_enable.contains("1")) {
                updateState(CHANNEL_ENABLE_AUDIO_ALARM, OnOffType.valueOf("ON"));
                String aa_value = searchString(content, "var aa_value = \"");
                // String aa_time = searchString(content, "var aa_time = \"");
                if (!aa_value.isEmpty()) {
                    logger.debug("Threshold is chaning to {}", aa_value);
                    updateState(CHANNEL_THRESHOLD_AUDIO_ALARM, PercentType.valueOf(aa_value));
                }
            } else {
                updateState(CHANNEL_ENABLE_AUDIO_ALARM, OnOffType.valueOf("OFF"));
            }

            // Motion Alarm
            String m1_enable = searchString(content, "var m1_enable=\"");
            if (m1_enable.contains("1")) {
                updateState(CHANNEL_ENABLE_MOTION_ALARM, OnOffType.valueOf("ON"));
            } else {
                updateState(CHANNEL_ENABLE_MOTION_ALARM, OnOffType.valueOf("OFF"));
            }

            content = null;
            ctx.close();

        }
    }

    private class HikvisionHandler extends ChannelDuplexHandler {

        // this fetches what Alarms the camera supports//
        // sendHttpRequest("GET", "/ISAPI/Event/triggers", false);

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            String content = msg.toString();
            logger.debug("HTTP Result back from camera is :{}:", content);

            // Alarm checking goes in here//
            if (content.contains("<EventNotificationAlert version=")) {

                if (content.contains("<eventType>VMD</eventType>\r\n<eventState>active</eventState>\r\n")) {
                    motionDetected(CHANNEL_MOTION_ALARM);
                } else {
                    updateState(CHANNEL_MOTION_ALARM, OnOffType.valueOf("OFF"));
                    firstMotionAlarm = false;
                }
                if (content.contains("<eventType>linedetection</eventType>\r\n<eventState>active</eventState>\r\n")) {
                    motionDetected(CHANNEL_LINE_CROSSING_ALARM);
                } else {
                    updateState(CHANNEL_LINE_CROSSING_ALARM, OnOffType.valueOf("OFF"));
                }
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

    private class DahuaHandler extends ChannelDuplexHandler {

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            String content = msg.toString();
            logger.debug("HTTP Result back from camera is :{}:", content);

            // determine if the motion detection is turned on or off.
            if (content.contains("table.Alarm[0].Enable=true")) {
                updateState(CHANNEL_ENABLE_MOTION_ALARM, OnOffType.valueOf("ON"));

            } else if (content.contains("table.Alarm[0].Enable=false")) {
                updateState(CHANNEL_ENABLE_MOTION_ALARM, OnOffType.valueOf("OFF"));

            }
            ctx.close();
            content = null;
        }
    }

    public IpCameraHandler(Thing thing) {
        super(thing);
    }

    private void motionDetected(String thisAlarmsChannel) {
        updateState(thisAlarmsChannel.toString(), OnOffType.valueOf("ON"));
        if (updateImageEvents.contains("2") && !firstMotionAlarm) {
            sendHttpRequest("GET", getCorrectUrlFormat(snapshotUri), null);
            firstMotionAlarm = true;
        } else if (updateImageEvents.contains("4")) {
            sendHttpRequest("GET", getCorrectUrlFormat(snapshotUri), null);
        }
    }

    private void audioDetected() {
        updateState(CHANNEL_AUDIO_ALARM, OnOffType.valueOf("ON"));
        if (updateImageEvents.contains("3") && !firstAudioAlarm) {
            sendHttpRequest("GET", getCorrectUrlFormat(snapshotUri), null);
            firstAudioAlarm = true;
        } else if (updateImageEvents.contains("5")) {
            sendHttpRequest("GET", getCorrectUrlFormat(snapshotUri), null);
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

    private PTZVector getPtzPosition() {

        try {
            ptzLocation = ptzDevices.getPosition(profileToken);
            if (ptzLocation != null) {
                return ptzLocation;
            }
        } catch (NullPointerException e) {
            logger.error("NPE occured when trying to fetch the cameras PTZ position");
        }

        logger.warn(
                "Camera replied with null when asked what its position was, going to fake the position so PTZ still works.");
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
                            sendHttpRequest("GET", "/cgi-bin/CGIProxy.fcgi?cmd=getAudioAlarmConfig&usr=" + username
                                    + "&pwd=" + password, null);
                            break;
                    }
                    break;
                case CHANNEL_ENABLE_AUDIO_ALARM:
                    switch (thing.getThingTypeUID().getId()) {
                        case "FOSCAM":
                            sendHttpRequest("GET", "/cgi-bin/CGIProxy.fcgi?cmd=getAudioAlarmConfig&usr=" + username
                                    + "&pwd=" + password, null);
                            break;
                    }
                    break;
                case CHANNEL_ENABLE_MOTION_ALARM:
                    switch (thing.getThingTypeUID().getId()) {

                        case "AMCREST":
                            sendHttpRequest("GET", "/cgi-bin/configManager.cgi?action=getConfig&name=MotionDetect",
                                    null);
                            break;

                        case "FOSCAM":
                            sendHttpRequest("GET",
                                    "/cgi-bin/CGIProxy.fcgi?cmd=getDevState&usr=" + username + "&pwd=" + password,
                                    null);
                            break;
                    }

                    break;

                case CHANNEL_PAN:
                    getAbsolutePan();
                    break;
                case CHANNEL_TILT:
                    getAbsoluteTilt();
                    break;
                case CHANNEL_ZOOM:
                    getAbsoluteZoom();
                    break;
            }
            return; // Return as we have handled the refresh command above and don't need to continue further.
        } // end of "REFRESH"

        switch (channelUID.getId()) {

            case CHANNEL_ALPHA_TEST:

                switch (thing.getThingTypeUID().getId()) {

                    case "AMCREST":
                        // Used to test if a forgotten stream is auto closed
                        sendHttpRequest("GET", "/cgi-bin/configManager.cgi?action=getConfig&name=Snap", null);
                        break;

                    case "HIKVISION":
                        sendHttpRequest("GET", "/ISAPI/Event/notification/alertStream", null);
                        break;

                    case "INSTAR":
                        sendHttpRequest("GET", "/cgi-bin/hi3510/param.cgi?cmd=getaudioalarmattr", null);
                        break;
                }
                break;

            case CHANNEL_UPDATE_IMAGE_NOW:
                if (snapshotUri != null) {
                    sendHttpRequest("GET", getCorrectUrlFormat(snapshotUri), null);
                }

                break;

            case CHANNEL_THRESHOLD_AUDIO_ALARM:

                switch (thing.getThingTypeUID().getId()) {

                    case "FOSCAM":
                        int value = Math.round(Float.valueOf(command.toString()));
                        if (value == 0) {
                            sendHttpRequest("GET", "/cgi-bin/CGIProxy.fcgi?cmd=setAudioAlarmConfig&isEnable=0&usr="
                                    + username + "&pwd=" + password, null);
                        } else if (value <= 33) {
                            sendHttpRequest("GET",
                                    "/cgi-bin/CGIProxy.fcgi?cmd=setAudioAlarmConfig&isEnable=1&sensitivity=0&usr="
                                            + username + "&pwd=" + password,
                                    null);
                        } else if (value <= 66) {
                            sendHttpRequest("GET",
                                    "/cgi-bin/CGIProxy.fcgi?cmd=setAudioAlarmConfig&isEnable=1&sensitivity=1&usr="
                                            + username + "&pwd=" + password,
                                    null);
                        } else {
                            sendHttpRequest("GET",
                                    "/cgi-bin/CGIProxy.fcgi?cmd=setAudioAlarmConfig&isEnable=1&sensitivity=2&usr="
                                            + username + "&pwd=" + password,
                                    null);
                        }

                        break;

                    case "INSTAR":
                        value = Math.round(Float.valueOf(command.toString()));
                        if (value == 0) {
                            sendHttpRequest("GET", "/cgi-bin/hi3510/param.cgi?cmd=setaudioalarmattr&-aa_enable=0",
                                    null);
                        } else {
                            sendHttpRequest("GET", "/cgi-bin/hi3510/param.cgi?cmd=setaudioalarmattr&-aa_enable=1",
                                    null);
                            sendHttpRequest("GET",
                                    "/cgi-bin/hi3510/param.cgi?cmd=setaudioalarmattr&-aa_enable=1&-aa_value="
                                            + command.toString(),
                                    null);
                        }

                        break;
                }

                break;

            case CHANNEL_ENABLE_AUDIO_ALARM:

                switch (thing.getThingTypeUID().getId()) {

                    case "FOSCAM":

                        if ("ON".equals(command.toString())) {
                            sendHttpRequest("GET", "/cgi-bin/CGIProxy.fcgi?cmd=setAudioAlarmConfig&isEnable=1&usr="
                                    + username + "&pwd=" + password, null);
                        } else {
                            sendHttpRequest("GET", "/cgi-bin/CGIProxy.fcgi?cmd=setAudioAlarmConfig&isEnable=0&usr="
                                    + username + "&pwd=" + password, null);
                        }
                        break;

                    case "INSTAR":

                        if ("ON".equals(command.toString())) {
                            sendHttpRequest("GET", "/cgi-bin/hi3510/param.cgi?cmd=setaudioalarmattr&-aa_enable=1",
                                    null);
                        } else {
                            sendHttpRequest("GET", "/cgi-bin/hi3510/param.cgi?cmd=setaudioalarmattr&-aa_enable=0",
                                    null);
                        }
                        break;
                }

                break;
            case CHANNEL_ENABLE_MOTION_ALARM:

                switch (thing.getThingTypeUID().getId()) {
                    case "AMCREST":
                        if ("ON".equals(command.toString())) {
                            sendHttpRequest("GET",
                                    "/cgi-bin/configManager.cgi?action=setConfig&MotionDetect[0].Enable=true", null);
                        } else {
                            sendHttpRequest("GET",
                                    "/cgi-bin/configManager.cgi?action=setConfig&MotionDetect[0].Enable=false", null);
                        }
                        break;

                    case "FOSCAM":
                        if ("ON".equals(command.toString())) {
                            sendHttpRequest("GET", "/cgi-bin/CGIProxy.fcgi?cmd=setMotionDetectConfig&isEnable=1&usr="
                                    + username + "&pwd=" + password, null);
                        } else {
                            sendHttpRequest("GET", "/cgi-bin/CGIProxy.fcgi?cmd=setMotionDetectConfig&isEnable=0&usr="
                                    + username + "&pwd=" + password, null);
                        }
                        break;
                    case "HIKVISION":
                        if ("ON".equals(command.toString())) {
                            sendHttpRequest("GET", "/MotionDetection/1", null);

                        } else {
                            sendHttpRequest("GET", "/MotionDetection/1", null);
                        }
                        break;

                    case "INSTAR":
                        if ("ON".equals(command.toString())) {
                            sendHttpRequest("GET",
                                    "/cgi-bin/hi3510/param.cgi?cmd=setmdattr&-enable=1&-name=1&cmd=setmdattr&-enable=1&-name=2&cmd=setmdattr&-enable=1&-name=3&cmd=setmdattr&-enable=1&-name=4",
                                    null);
                        } else {
                            sendHttpRequest("GET",
                                    "/cgi-bin/hi3510/param.cgi?cmd=setmdattr&-enable=0&-name=1&cmd=setmdattr&-enable=0&-name=2&cmd=setmdattr&-enable=0&-name=3&cmd=setmdattr&-enable=0&-name=4",
                                    null);
                        }
                        break;

                    case "DAHUA":
                        if ("ON".equals(command.toString())) {
                            sendHttpRequest("GET",
                                    "/cgi-bin/configManager.cgi?action=setConfig&MotionDetect[0].Enable=true", null);
                        } else {
                            sendHttpRequest("GET",
                                    "/cgi-bin/configManager.cgi?action=setConfig&MotionDetect[0].Enable=false", null);
                        }
                        break;
                }

                break;

            case CHANNEL_PAN:
                setAbsolutePan(Float.valueOf(command.toString()));
                break;

            case CHANNEL_TILT:
                setAbsoluteTilt(Float.valueOf(command.toString()));
                break;

            case CHANNEL_ZOOM:
                setAbsoluteZoom(Float.valueOf(command.toString()));
                break;
        }
    }

    void getAbsolutePan() {
        if (ptzDevices != null) {
            currentPanPercentage = (((panRange.getMin() - ptzLocation.getPanTilt().getX()) * -1)
                    / ((panRange.getMin() - panRange.getMax()) * -1)) * 100;
            currentPanCamValue = ((((panRange.getMin() - panRange.getMax()) * -1) / 100) * currentPanPercentage
                    + panRange.getMin());
            logger.debug("Pan is updating to:{}", Math.round(currentPanPercentage));
            updateState(CHANNEL_PAN, new PercentType(Math.round(currentPanPercentage)));
        }
    }

    void getAbsoluteTilt() {
        if (ptzDevices != null) {
            currentTiltPercentage = (((tiltRange.getMin() - ptzLocation.getPanTilt().getY()) * -1)
                    / ((tiltRange.getMin() - tiltRange.getMax()) * -1)) * 100;
            currentTiltCamValue = ((((tiltRange.getMin() - tiltRange.getMax()) * -1) / 100) * currentTiltPercentage
                    + tiltRange.getMin());
            logger.debug("Tilt is updating to:{}", Math.round(currentTiltPercentage));
            updateState(CHANNEL_TILT, new PercentType(Math.round(currentTiltPercentage)));
        }

    }

    void getAbsoluteZoom() {
        if (ptzDevices != null) {
            currentZoomPercentage = (((zoomMin - ptzLocation.getZoom().getX()) * -1) / ((zoomMin - zoomMax) * -1))
                    * 100;
            currentZoomCamValue = ((((zoomMin - zoomMax) * -1) / 100) * currentZoomPercentage + zoomMin);
            logger.debug("Zoom is updating to:{}", Math.round(currentZoomPercentage));
            updateState(CHANNEL_ZOOM, new PercentType(Math.round(currentZoomPercentage)));
        }
    }

    void setAbsolutePan(Float panValue) {

        if (ptzDevices != null) {
            currentPanCamValue = ((((panRange.getMin() - panRange.getMax()) * -1) / 100) * panValue
                    + panRange.getMin());
            logger.debug("Cameras Pan  has changed to:{}", currentPanCamValue);
            if (onvifCamera != null && panRange != null && tiltRange != null) {
                try {
                    ptzDevices.absoluteMove(profileToken, currentPanCamValue, currentTiltCamValue, currentZoomCamValue);
                } catch (SOAPException e) {
                    logger.error("SOAP exception occured");
                }
            }
        }
    }

    void setAbsoluteTilt(Float tiltValue) {
        if (ptzDevices != null) {
            currentTiltCamValue = ((((tiltRange.getMin() - tiltRange.getMax()) * -1) / 100) * tiltValue
                    + tiltRange.getMin());
            logger.debug("Cameras Tilt has changed to:{}", currentTiltCamValue);
            if (onvifCamera != null && panRange != null && tiltRange != null) {
                try {
                    ptzDevices.absoluteMove(profileToken, currentPanCamValue, currentTiltCamValue, currentZoomCamValue);
                } catch (SOAPException e) {
                    logger.error("SOAP exception occured");
                }
            }
        }
    }

    void setAbsoluteZoom(Float zoomValue) {

        if (ptzDevices != null) {
            currentZoomCamValue = ((((zoomMin - zoomMax) * -1) / 100) * zoomValue + zoomMin);
            logger.debug("Cameras Zoom has changed to:{}", currentZoomCamValue);
            if (onvifCamera != null && panRange != null && tiltRange != null) {
                try {
                    ptzDevices.absoluteMove(profileToken, currentPanCamValue, currentTiltCamValue, currentZoomCamValue);
                } catch (SOAPException e) {
                    logger.error("SOAP exception occured");
                }
            }
        }
    }

    Runnable pollingCameraConnection = new Runnable() {
        @Override
        public void run() {

            if (thing.getThingTypeUID().getId().equals("HTTPONLY")) {

                if (!snapshotUri.isEmpty()) {
                    logger.debug("Camera at {} has a snapshot address of:{}:", ipAddress, snapshotUri);
                    if (sendHttpRequest("GET", getCorrectUrlFormat(snapshotUri), null)) {
                        updateStatus(ThingStatus.ONLINE);
                        cameraConnectionJob.cancel(true);
                        cameraConnectionJob = null;

                        fetchCameraOutputJob = fetchCameraOutput.scheduleAtFixedRate(pollingCamera, 5000,
                                Integer.parseInt(config.get(CONFIG_POLL_CAMERA_MS).toString()), TimeUnit.MILLISECONDS);
                        sendHttpRequest("GET", getCorrectUrlFormat(snapshotUri), null);
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

                    logger.info("Fetching the number of Media Profile this camera supports.");
                    profiles = onvifCamera.getDevices().getProfiles();
                    if (profiles == null) {
                        logger.error("Camera replied with NULL when trying to get a list of the media profiles");
                    }
                    logger.info("Checking the selected Media Profile is a valid number.");
                    if (selectedMediaProfile > profiles.size()) {
                        logger.warn(
                                "The selected Media Profile in the binding is higher than the max supported profiles. Changing to use Media Profile 0.");
                        selectedMediaProfile = 0;
                    }

                    logger.info("Fetching a Token for the selected Media Profile.");
                    profileToken = profiles.get(selectedMediaProfile).getToken();
                    if (profileToken == null) {
                        logger.error("Camera replied with NULL when trying to get a media profile token.");
                    }

                    if (snapshotUri == null) {
                        logger.info("Auto fetching the snapshot URL for the selected Media Profile.");
                        snapshotUri = onvifCamera.getMedia().getSnapshotUri(profileToken);
                    }

                    if (logger.isDebugEnabled()) {

                        logger.debug("About to fetch some information about the Media Profiles from the camera");
                        for (int x = 0; x < profiles.size(); x++) {
                            VideoEncoderConfiguration result = profiles.get(x).getVideoEncoderConfiguration();
                            logger.debug(
                                    "********************* Media Profile {} details reported by camera at IP:{} *********************",
                                    x, ipAddress);
                            if (selectedMediaProfile == x) {
                                logger.debug(
                                        "Camera will use this Media Profile unless you change it in the binding by pressing on the pencil icon in PaperUI.");
                            }
                            logger.debug("Media Profile {} is named:{}", x, result.getName());
                            logger.debug("Media Profile {} uses video encoder\t:{}", x, result.getEncoding());
                            logger.debug("Media Profile {} uses video quality\t:{}", x, result.getQuality());
                            logger.debug("Media Profile {} uses video resoltion\t:{} x {}", x,
                                    result.getResolution().getWidth(), result.getResolution().getHeight());
                            logger.debug("Media Profile {} uses video bitrate\t:{}", x,
                                    result.getRateControl().getBitrateLimit());
                        }
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

                            getPtzPosition();

                        } else {
                            logger.info("Camera is reporting that it does NOT support Absolute PTZ controls via ONVIF");
                            // null will stop code from running on cameras that do not support PTZ features.
                            ptzDevices = null;
                        }
                    }
                    logger.info("Finished with PTZ, now fetching the Video URL's the camera supports.");
                    videoStreamUri = onvifCamera.getMedia().getRTSPStreamUri(profileToken);

                } catch (ConnectException e) {
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                            "Can not connect to camera with ONVIF: Check that IP ADDRESS, ONVIF PORT, USERNAME and PASSWORD are correct.");
                    logger.error(
                            "Can not connect to camera with ONVIF at IP:{}, it may be the wrong ONVIF_PORT. Fault was {}",
                            ipAddress, e.toString());
                } catch (SOAPException e) {
                    logger.error(
                            "The camera connection had a SOAP error, this may indicate your camera does not fully support ONVIF, check for an updated firmware for your camera. Not to worry, we will still try and connect. Camera at IP:{}, fault was {}",
                            ipAddress, e.toString());
                } catch (NullPointerException e) {
                    logger.error("NPE occured when trying to connect to the camera with ONVIF");
                }

                if (snapshotUri != null) {

                    sendHttpRequest("GET", getCorrectUrlFormat(snapshotUri), null);

                    updateState(CHANNEL_IMAGE_URL, new StringType(snapshotUri));
                    if (videoStreamUri != null) {
                        updateState(CHANNEL_VIDEO_URL, new StringType(videoStreamUri));
                    }

                    cameraConnectionJob.cancel(false);
                    cameraConnectionJob = null;

                    fetchCameraOutputJob = fetchCameraOutput.scheduleAtFixedRate(pollingCamera, 5000,
                            Integer.parseInt(config.get(CONFIG_POLL_CAMERA_MS).toString()), TimeUnit.MILLISECONDS);

                    updateStatus(ThingStatus.ONLINE);

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
                sendHttpRequest("GET", getCorrectUrlFormat(snapshotUri), null);
            }

            switch (thing.getThingTypeUID().getId()) {
                case "AMCREST":
                    sendHttpRequest("GET", "/cgi-bin/eventManager.cgi?action=getEventIndexes&code=VideoMotion", null);
                    sendHttpRequest("GET", "/cgi-bin/eventManager.cgi?action=getEventIndexes&code=AudioMutation", null);
                    break;

                case "FOSCAM":
                    sendHttpRequest("GET",
                            "/cgi-bin/CGIProxy.fcgi?cmd=getDevState&usr=" + username + "&pwd=" + password, null);
                    break;
                case "HIKVISION":
                    // check to see if motion alarm is turned on or off
                    sendHttpRequest("GET", "/MotionDetection/1", null);
                    // Check if any alarms are going off
                    // sendHttpRequest("GET", "/ISAPI/Event/notification/alertStream", false);
                    sendHttpRequest("GET", "/Event/notification/alertStream", null);
                    break;
                case "INSTAR":
                    // Poll the audio alarm on/off/threshold/...
                    sendHttpRequest("GET", "/cgi-bin/hi3510/param.cgi?cmd=getaudioalarmattr", null);
                    // Poll the motion alarm on/off/settings/...
                    sendHttpRequest("GET", "/cgi-bin/hi3510/param.cgi?cmd=getmdattr", null);
                    break;
                case "DAHUA":
                    // Poll the alarm configs ie on/off/...
                    sendHttpRequest("GET", "/cgi-bin/configManager.cgi?action=getConfig&name=Alarm", null);
                    // Not sure if this reports if the alarm is happening or not ?
                    sendHttpRequest("GET",
                            "/cgi-bin/configManager.cgi?action=getConfig&name=MotionDetect[0].EventHandler", null);
                    break;
            }
        }
    };

    @Override
    public void initialize() {
        config = thing.getConfiguration();
        ipAddress = config.get(CONFIG_IPADDRESS).toString();
        logger.debug("Getting configuration to initialize a new IP Camera at IP {}", ipAddress);
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

        countHandlers = 0;
        cameraConnectionJob = cameraConnection.scheduleAtFixedRate(pollingCameraConnection, 0, 60, TimeUnit.SECONDS);
    }

    @Override
    public void dispose() {
        logger.debug("Camera dispose called, about to remove/change a Cameras settings.");

        onvifCamera = null;
        basicAuth = null; // clear out stored hash
        useDigestAuth = false;

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
