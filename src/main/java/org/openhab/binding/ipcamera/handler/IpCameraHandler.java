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

import org.eclipse.jdt.annotation.NonNull;
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

    public static final Set<ThingTypeUID> SUPPORTED_THING_TYPES = new HashSet<ThingTypeUID>(Arrays
            .asList(THING_TYPE_ONVIF, THING_TYPE_NON_ONVIF, THING_TYPE_AMCREST, THING_TYPE_AXIS, THING_TYPE_FOSCAM));
    private Configuration config;
    private OnvifDevice onvifCamera;
    private List<Profile> profiles;
    private String username;
    private String password;
    private FloatRange panRange;
    private FloatRange tiltRange;
    private PtzDevices ptzDevices;
    private ScheduledFuture<?> cameraConnectionJob = null;
    private ScheduledFuture<?> fetchCameraOutputJob = null;
    private int selectedMediaProfile = 0;
    private Bootstrap mainBootstrap;
    private Bootstrap secondBootstrap;
    ChannelFuture secondchfuture;
    int nettyNumHandlers = 0;

    private EventLoopGroup mainEventLoopGroup = new NioEventLoopGroup();
    private EventLoopGroup secondEventLoopGroup = new NioEventLoopGroup();

    public String fullRequestPath;
    private String scheme;
    private PTZVector ptzLocation;
    Channel ch;
    Channel digestChannel;
    ChannelFuture mainChFuture;
    MyNettyAuthHandler authChecker;
    public int ncCounter = 0;
    public String opaque;
    public String qop;
    public String realm;

    @NonNull
    private String channelCheckingNow = "NONE";
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final ScheduledExecutorService cameraConnection = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService fetchCameraOutput = Executors.newSingleThreadScheduledExecutor();
    private String snapshotUri = "";
    private String videoStreamUri = "empty";
    private String ipAddress = "empty";
    private int port;
    private String profileToken = "empty";
    public boolean useDigestAuth = false;
    public boolean useBasicAuth = false;
    public String digestString = "false";
    public String nonce;
    private String updateImageEvents;
    boolean firstAudioAlarm = false;
    boolean firstMotionAlarm = false;

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
    // I used it as a starting point and it is released under Apache License version 2.0//

    public void sendHttpRequest(String digestFullRequestPath, String newDigestString) {
        // Second channel as I may need to keep one for streaming and the other for commands//
        if (secondBootstrap == null) {
            secondBootstrap = new Bootstrap();
            secondBootstrap.group(secondEventLoopGroup);
            secondBootstrap.channel(NioSocketChannel.class);
            secondBootstrap.option(ChannelOption.SO_KEEPALIVE, true);
            secondBootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000);
            secondBootstrap.option(ChannelOption.SO_SNDBUF, 1024 * 128);
            secondBootstrap.option(ChannelOption.SO_RCVBUF, 1024 * 1024);
            secondBootstrap.option(ChannelOption.TCP_NODELAY, true);
            secondBootstrap.handler(new ChannelInitializer<SocketChannel>() {
                @Override
                public void initChannel(SocketChannel socketChannel) throws Exception {

                    socketChannel.pipeline().addLast(new HttpClientCodec());
                    socketChannel.pipeline().addLast(new HttpContentDecompressor());
                    socketChannel.pipeline().addLast(new MyNettyAuthHandler(username, password, thing.getHandler()));

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
        HttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, digestFullRequestPath);
        request.headers().set(HttpHeaderNames.HOST, ipAddress);
        request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        // request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        request.headers().set(HttpHeaderNames.ACCEPT_ENCODING, HttpHeaderValues.GZIP);
        if (useDigestAuth) {
            request.headers().set(HttpHeaderNames.AUTHORIZATION, "Digest " + newDigestString);
        }

        secondchfuture = secondBootstrap.connect(new InetSocketAddress(ipAddress, port));
        secondchfuture.awaitUninterruptibly(3000);
        digestChannel = secondchfuture.channel();
        logger.debug("+++ A digest request is just sent {}", digestFullRequestPath);
        digestChannel.writeAndFlush(request);
        secondchfuture = digestChannel.closeFuture();
        secondchfuture.awaitUninterruptibly(2000);
    }

    public boolean sendHttpRequest(String httpRequest) {

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
                    // RtspResponseDecoder //RtspRequestEncoder // to try in the pipeline soon//

                    socketChannel.pipeline().addLast(new HttpClientCodec());
                    socketChannel.pipeline().addLast(new HttpContentDecompressor());
                    socketChannel.pipeline()
                            .addLast(authChecker = new MyNettyAuthHandler(username, password, thing.getHandler()));

                    switch (thing.getThingTypeUID().getId()) {
                        case "AMCREST":
                            socketChannel.pipeline().addLast(new AmcrestHandler());
                            break;
                        case "FOSCAM":
                            socketChannel.pipeline().addLast(new FoscamHandler());
                            break;
                        default:
                            // Use the most tested one for now as default.
                            socketChannel.pipeline().addLast(new AmcrestHandler());
                            break;
                    }
                }
            });
        }

        try {
            URI uri = new URI(httpRequest);

            if (uri.getRawQuery() == null) {
                fullRequestPath = uri.getPath();
            } else {
                fullRequestPath = uri.getPath() + "?" + uri.getRawQuery();
            }

            // Configure SSL context if necessary.
            final boolean usingHTTPS = "https".equalsIgnoreCase(scheme);
            final SslContext sslCtx;
            if (usingHTTPS) {
                try {
                    sslCtx = SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build();
                    if (sslCtx != null) {
                        // need to add something like this to the pipe line
                        // socketChannel.pipeline().addFirst(sslCtx.newHandler(ch.alloc()));
                    }
                } catch (SSLException e) {
                    logger.error("Exception occured when trying to create an SSL for HTTPS:{}", e);
                }
            } else {
                sslCtx = null;
            }

            logger.debug("Trying to connect with new request for camera at IP:{}", ipAddress);
            // Try to connect then catch any connection timeouts.
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

            HttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, fullRequestPath);
            request.headers().set(HttpHeaderNames.HOST, ipAddress);
            request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            // request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            request.headers().set(HttpHeaderNames.ACCEPT_ENCODING, HttpHeaderValues.GZIP);

            logger.debug("++ The request is going to be :{}:", fullRequestPath);

            if (useDigestAuth) {
                // following line causes nc to increase by 2
                // digestString = authChecker.processAuth(null, fullRequestPath, false);
                // logger.debug("Send method using this header:{}", digestString);
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
                        byteBuf = null;
                    }
                }
            }

            ch.writeAndFlush(request);
            // wait for camera to reply and close the connection for 3 seconds//
            mainChFuture = ch.closeFuture();
            mainChFuture.awaitUninterruptibly(3000);

            if (!mainChFuture.isSuccess()) {
                logger.warn("Camera at {}:{} is not closing the connection quick enough. Check for digest stale=.",
                        ipAddress, port);
                ch.close();// force close to prevent the thread getting locked.
                // cleanup then return
                request = null;
                uri = null;
                return false;
            }

        } catch (URISyntaxException e) {
            logger.error("Following error occured:{}", e);
        }

        return true;
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

    public class AmcrestHandler extends ChannelDuplexHandler {
        private int bytesToRecieve = 0;
        private int bytesAlreadyRecieved = 0;
        private byte[] lastSnapshot;
        private String contentType;

        // Any camera specific changes are to be in here to make this easier to maintain//
        private void processResponseContent(String content) {

            logger.debug("HTTP Result back from camera is :{}:", content);

            switch (content) {
                case "Error: No Events\r\n":
                    updateState(channelCheckingNow, OnOffType.valueOf("OFF"));
                    if (channelCheckingNow.contains("audio")) {
                        firstAudioAlarm = false;
                    } else {
                        firstMotionAlarm = false;
                    }
                    return;

                case "channels[0]=0\r\n":
                    updateState(channelCheckingNow, OnOffType.valueOf("ON"));

                    if (updateImageEvents.contains("3") && channelCheckingNow.contains("audio") && !firstAudioAlarm) {
                        sendHttpRequest(snapshotUri);
                        firstAudioAlarm = true;
                    } else if (updateImageEvents.contains("2") && channelCheckingNow.contains("motion")
                            && !firstMotionAlarm) {
                        sendHttpRequest(snapshotUri);
                        firstMotionAlarm = true;
                    } else if (updateImageEvents.contains("5") && channelCheckingNow.contains("audio")) {
                        sendHttpRequest(snapshotUri);
                    } else if (updateImageEvents.contains("4") && channelCheckingNow.contains("motion")) {
                        sendHttpRequest(snapshotUri);
                    }
                    return;
            }

            if (searchString(content, "table.MotionDetect[0].Enable=false") != null) {
                updateState(CHANNEL_ENABLE_MOTION_ALARM, OnOffType.valueOf("OFF"));
                return;
            } else if (searchString(content, "table.MotionDetect[0].Enable=true") != null) {
                updateState(CHANNEL_ENABLE_MOTION_ALARM, OnOffType.valueOf("ON"));
                return;
            }
        }

        // These methods handle the Servers response, nothing specific to the camera should be in here //

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

                // Process the contents of a response if it is not an image//
                if (!"image/jpeg".equalsIgnoreCase(contentType)) {
                    processResponseContent(content.content().toString(CharsetUtil.UTF_8));
                    content.content().release(); // stop memory leak?
                    bytesAlreadyRecieved = 0;
                    lastSnapshot = null;
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

                        // logger.debug("got {} bytes out of the total {}, so still waiting for {} more",
                        // bytesAlreadyRecieved, bytesToRecieve, bytesToRecieve - bytesAlreadyRecieved);

                        if (bytesAlreadyRecieved >= bytesToRecieve) {
                            if (bytesToRecieve != bytesAlreadyRecieved) {
                                logger.error(
                                        "We got too many packets back from the camera for some reason, please report this.");
                            }
                            // logger.debug("Updating the image channel now with {} Bytes.", bytesAlreadyRecieved);
                            updateState(CHANNEL_IMAGE, new RawType(lastSnapshot, "image/jpeg"));
                            bytesAlreadyRecieved = 0;
                            lastSnapshot = null;
                        }
                    }
                }
            }
        }

        @Override
        public void channelReadComplete(ChannelHandlerContext ctx) {
            // logger.debug("* readcompleted for connection ID:{}", ctx.channel().id().toString());
        }

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
        }

        @Override
        public void handlerRemoved(ChannelHandlerContext ctx) {
            ctx.close();
            authChecker = null;
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            logger.debug("!!! Camera may have closed the connection which can be normal. Cause reported is:{}", cause);
            ctx.close();
        }

    }

    public class FoscamHandler extends ChannelDuplexHandler {
        private int bytesToRecieve = 0;
        private int bytesAlreadyRecieved = 0;
        private byte[] lastSnapshot;
        private String contentType;

        private void processResponseContent(String content) {
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
                    sendHttpRequest(snapshotUri);
                    firstMotionAlarm = true;
                } else if (updateImageEvents.contains("4")) {
                    sendHttpRequest(snapshotUri);
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
                    sendHttpRequest(snapshotUri);
                    firstAudioAlarm = true;
                } else if (updateImageEvents.contains("5")) {
                    sendHttpRequest(snapshotUri);
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

        }

        // This method handles the Servers response, nothing specific to the camera should be in here //
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

                // Process the contents of a response if it is not an image//
                if (!"image/jpeg".equalsIgnoreCase(contentType)) {
                    processResponseContent(content.content().toString(CharsetUtil.UTF_8));
                    content.content().release(); // stop memory leak?
                    bytesAlreadyRecieved = 0;
                    lastSnapshot = null;
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

                        // logger.debug("got {} bytes out of the total {}, so still waiting for {} more",
                        // bytesAlreadyRecieved, bytesToRecieve, bytesToRecieve - bytesAlreadyRecieved);

                        if (bytesAlreadyRecieved >= bytesToRecieve) {
                            if (bytesToRecieve != bytesAlreadyRecieved) {
                                logger.error(
                                        "We got too many packets back from the camera for some reason, please report this.");
                            }
                            // logger.debug("Updating the image channel now with {} Bytes.", bytesAlreadyRecieved);
                            updateState(CHANNEL_IMAGE, new RawType(lastSnapshot, "image/jpeg"));
                            bytesAlreadyRecieved = 0;
                            lastSnapshot = null;
                        }
                    }
                }
            }
        }

        @Override
        public void channelReadComplete(ChannelHandlerContext ctx) {
            // logger.debug("* readcompleted for connection ID:{}", ctx.channel().id().toString());
        }

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
        }

        @Override
        public void handlerRemoved(ChannelHandlerContext ctx) {
            ctx.close();
            authChecker = null;
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            logger.debug("!!! Camera may have closed the connection which can be normal. Cause reported is:{}", cause);
            ctx.close();
        }
    }

    public IpCameraHandler(Thing thing) {
        super(thing);
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {

        if (command.toString() == "REFRESH") {

            switch (channelUID.getId()) {
                case CHANNEL_THRESHOLD_AUDIO_ALARM:
                    switch (thing.getThingTypeUID().getId()) {
                        case "FOSCAM":
                            sendHttpRequest("http://192.168.1.108/cgi-bin/CGIProxy.fcgi?cmd=getAudioAlarmConfig&usr="
                                    + username + "&pwd=" + password);
                            break;
                    }
                    break;
                case CHANNEL_ENABLE_AUDIO_ALARM:
                    switch (thing.getThingTypeUID().getId()) {
                        case "FOSCAM":
                            sendHttpRequest("http://192.168.1.108/cgi-bin/CGIProxy.fcgi?cmd=getAudioAlarmConfig&usr="
                                    + username + "&pwd=" + password);
                            break;
                    }
                    break;
                case CHANNEL_ENABLE_MOTION_ALARM:
                    switch (thing.getThingTypeUID().getId()) {

                        case "AMCREST":
                            sendHttpRequest(
                                    "http://192.168.1.108/cgi-bin/configManager.cgi?action=getConfig&name=MotionDetect");
                            break;

                        case "FOSCAM":
                            sendHttpRequest("http://192.168.1.108/cgi-bin/CGIProxy.fcgi?cmd=getDevState&usr=" + username
                                    + "&pwd=" + password);
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
            case CHANNEL_UPDATE_IMAGE_NOW:
                if (snapshotUri != null) {
                    sendHttpRequest(snapshotUri);
                }
                break;

            case CHANNEL_THRESHOLD_AUDIO_ALARM:

                switch (thing.getThingTypeUID().getId()) {

                    case "FOSCAM":
                        int value = Math.round(Float.valueOf(command.toString()));
                        if (value == 0) {
                            sendHttpRequest(
                                    "http://192.168.1.108/cgi-bin/CGIProxy.fcgi?cmd=setAudioAlarmConfig&isEnable=0&usr="
                                            + username + "&pwd=" + password);
                        } else if (value <= 33) {
                            sendHttpRequest(
                                    "http://192.168.1.108/cgi-bin/CGIProxy.fcgi?cmd=setAudioAlarmConfig&isEnable=1&sensitivity=0&usr="
                                            + username + "&pwd=" + password);
                        } else if (value <= 66) {
                            sendHttpRequest(
                                    "http://192.168.1.108/cgi-bin/CGIProxy.fcgi?cmd=setAudioAlarmConfig&isEnable=1&sensitivity=1&usr="
                                            + username + "&pwd=" + password);
                        } else {
                            sendHttpRequest(
                                    "http://192.168.1.108/cgi-bin/CGIProxy.fcgi?cmd=setAudioAlarmConfig&isEnable=1&sensitivity=2&usr="
                                            + username + "&pwd=" + password);
                        }

                        break;
                }

                break;

            case CHANNEL_ENABLE_AUDIO_ALARM:

                switch (thing.getThingTypeUID().getId()) {

                    case "FOSCAM":

                        if ("ON".equals(command.toString())) {
                            sendHttpRequest(
                                    "http://192.168.1.108/cgi-bin/CGIProxy.fcgi?cmd=setAudioAlarmConfig&isEnable=1&usr="
                                            + username + "&pwd=" + password);
                        } else {
                            sendHttpRequest(
                                    "http://192.168.1.108/cgi-bin/CGIProxy.fcgi?cmd=setAudioAlarmConfig&isEnable=0&usr="
                                            + username + "&pwd=" + password);
                        }
                        break;
                }

                break;
            case CHANNEL_ENABLE_MOTION_ALARM:

                switch (thing.getThingTypeUID().getId()) {
                    case "AMCREST":
                        if ("ON".equals(command.toString())) {
                            sendHttpRequest(
                                    "http://192.168.1.108/cgi-bin/configManager.cgi?action=setConfig&MotionDetect[0].Enable=true");
                        } else {
                            sendHttpRequest(
                                    "http://192.168.1.108/cgi-bin/configManager.cgi?action=setConfig&MotionDetect[0].Enable=false");
                        }
                        break;

                    case "FOSCAM":
                        if ("ON".equals(command.toString())) {
                            sendHttpRequest(
                                    "http://192.168.1.108/cgi-bin/CGIProxy.fcgi?cmd=setMotionDetectConfig&isEnable=1&usr="
                                            + username + "&pwd=" + password);
                        } else {
                            sendHttpRequest(
                                    "http://192.168.1.108/cgi-bin/CGIProxy.fcgi?cmd=setMotionDetectConfig&isEnable=0&usr="
                                            + username + "&pwd=" + password);
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

            if (thing.getThingTypeUID().getId().equals("NON_ONVIF")) {

                if (snapshotUri.toString() != null) {
                    logger.debug("Camera at {} has a snapshot address of:{}:", ipAddress, snapshotUri);
                    if (sendHttpRequest(snapshotUri)) {
                        updateStatus(ThingStatus.ONLINE);
                        cameraConnectionJob.cancel(true);
                        cameraConnectionJob = null;

                        fetchCameraOutputJob = fetchCameraOutput.scheduleAtFixedRate(pollingCamera, 2000,
                                Integer.parseInt(thing.getConfiguration().get(CONFIG_POLL_CAMERA_MS).toString()),
                                TimeUnit.MILLISECONDS);

                        updateState(CHANNEL_IMAGE_URL, new StringType(snapshotUri));
                    }
                } else {
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR,
                            "Can not find a valid url, check camera setup settings by clicking on the pencil icon in PaperUI.");
                    logger.error(" Camera at IP {} has no url entered in its camera setup.", ipAddress);
                }

                return;
            } /////////////// end of non onvif connection maker//

            if (onvifCamera == null && !thing.getThingTypeUID().getId().equals("NON_ONVIF")) {

                try {
                    logger.info("About to connect to IP Camera at IP:{}:{}", ipAddress,
                            config.get(CONFIG_ONVIF_PORT).toString());

                    if (username != null && password != null) {
                        onvifCamera = new OnvifDevice(ipAddress + ":" + config.get(CONFIG_ONVIF_PORT).toString(),
                                username, password);
                    } else {
                        onvifCamera = new OnvifDevice(ipAddress + ":" + config.get(CONFIG_ONVIF_PORT).toString());
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
                    videoStreamUri = onvifCamera.getMedia().getRTSPStreamUri(profileToken);

                    if (logger.isDebugEnabled()) {
                        logger.debug(
                                "This camera supports the following Video links. NOTE: The camera may report a link or error that does not match the header, this is the camera not a bug in the binding.");
                        logger.debug("HTTP Stream:{}", onvifCamera.getMedia().getHTTPStreamUri(profileToken));
                        logger.debug("TCP Stream:{}", onvifCamera.getMedia().getTCPStreamUri(profileToken));
                        logger.debug("RTSP Stream:{}", onvifCamera.getMedia().getRTSPStreamUri(profileToken));
                        logger.debug("UDP Stream:{}", onvifCamera.getMedia().getUDPStreamUri(profileToken));
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
                    cameraConnectionJob = cameraConnection.scheduleAtFixedRate(pollingCameraConnection, 30, 30,
                            TimeUnit.SECONDS);
                    updateStatus(ThingStatus.ONLINE);

                    if (snapshotUri != null) {
                        sendHttpRequest(snapshotUri);
                    }

                    fetchCameraOutputJob = fetchCameraOutput.scheduleAtFixedRate(pollingCamera, 5000,
                            Integer.parseInt(config.get(CONFIG_POLL_CAMERA_MS).toString()), TimeUnit.MILLISECONDS);

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
        }
    };

    Runnable pollingCamera = new Runnable() {
        @Override
        public void run() {

            if (snapshotUri != null && updateImageEvents.contains("1")) {
                sendHttpRequest(snapshotUri);
            }

            switch (thing.getThingTypeUID().getId()) {
                case "AMCREST":
                    channelCheckingNow = "motionAlarm";
                    sendHttpRequest(
                            "http://192.168.1.108/cgi-bin/eventManager.cgi?action=getEventIndexes&code=VideoMotion");
                    channelCheckingNow = "audioAlarm";
                    sendHttpRequest(
                            "http://192.168.1.108/cgi-bin/eventManager.cgi?action=getEventIndexes&code=AudioMutation");
                    break;

                case "FOSCAM":
                    sendHttpRequest("http://192.168.1.108/cgi-bin/CGIProxy.fcgi?cmd=getDevState&usr=" + username
                            + "&pwd=" + password);
                    break;
            }
        }
    };

    @Override
    public void initialize() {
        logger.debug("Getting configuration to initialize a new IP Camera at IP {}", ipAddress);
        updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_PENDING,
                "Making a new connection to the camera now.");

        config = thing.getConfiguration();
        ipAddress = config.get(CONFIG_IPADDRESS).toString();
        port = Integer.parseInt(config.get(CONFIG_PORT).toString());
        scheme = (config.get(CONFIG_USE_HTTPS).equals(true)) ? "https" : "http";
        username = (config.get(CONFIG_USERNAME) == null) ? null : config.get(CONFIG_USERNAME).toString();
        password = (config.get(CONFIG_PASSWORD) == null) ? null : config.get(CONFIG_PASSWORD).toString();
        snapshotUri = (config.get(CONFIG_SNAPSHOT_URL_OVERIDE) == null) ? null
                : config.get(CONFIG_SNAPSHOT_URL_OVERIDE).toString();
        selectedMediaProfile = (config.get(CONFIG_ONVIF_PROFILE_NUMBER) == null) ? 0
                : Integer.parseInt(config.get(CONFIG_ONVIF_PROFILE_NUMBER).toString());
        updateImageEvents = config.get(CONFIG_IMAGE_UPDATE_EVENTS).toString();

        cameraConnectionJob = cameraConnection.scheduleAtFixedRate(pollingCameraConnection, 0, 30, TimeUnit.SECONDS);

        /////////////////////////
        // when testing code it is handy to shut down the Jobs and go straight online//
        // snapshotUri = "http://192.168.1.108/cgi-bin/snapshot.cgi?channel=1";
        // updateStatus(ThingStatus.ONLINE);
        /////////////////////////
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
        mainBootstrap = null;
        secondBootstrap = null;
    }
}
