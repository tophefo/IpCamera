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
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

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
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
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
    private String globalUrl;
    private FullHttpRequest putRequestWithBody;
    private String nvrChannel;

    public LinkedList<String> listOfRequests = new LinkedList<String>();
    public LinkedList<Channel> listOfChannels = new LinkedList<Channel>();
    // private LinkedList<ChannelFuture> listOfFutures = new LinkedList<ChannelFuture>();
    private LinkedList<String> listOfReplies = new LinkedList<String>();

    private EventLoopGroup mainEventLoopGroup = new NioEventLoopGroup();
    private PTZVector ptzLocation;

    // basicAuth MUST remain private as it holds the password
    private String basicAuth = null;
    public boolean useDigestAuth = false;

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private final ScheduledExecutorService cameraConnection = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService fetchCameraOutput = Executors.newSingleThreadScheduledExecutor();
    private String snapshotUri = null;
    private String videoStreamUri = "ONVIF failed to report a RTSP stream link.";
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
    private Float currentPanCamValue = 0.0f;
    private Float currentTiltCamValue = 0.0f;
    private Float currentZoomCamValue = 0.0f;
    private Float zoomMin = 0.0f;
    private Float zoomMax = 0.0f;
    // These hold the PTZ values for updating Openhabs controls in 0-100 range
    private Float currentPanPercentage = 0.0f;
    private Float currentTiltPercentage = 0.0f;
    private Float currentZoomPercentage = 0.0f;

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

    private void cleanChannels() {

        for (byte index = 0; index < listOfRequests.size(); index++) {
            logger.debug("Channel {} is for URL:{}", index, listOfRequests.get(index));
            if (listOfRequests.get(index).contentEquals("closed")) {
                listOfRequests.remove(index);
                listOfChannels.remove(index);
            } else if (listOfRequests.get(index).contentEquals("closing")) {
                Channel chan = listOfChannels.get(index);
                chan.close();
                listOfRequests.set(index, "closed");
                logger.debug("Cleaned up channel {} by force closing it as it was already marked for closing", index);
            }
        }
    }

    public void hikChangeSetting(String httpRequestURL, String findOldValue, String newValue) {

        if (listOfReplies.size() == 0) {
            sendHttpGET(httpRequestURL);
            logger.error(
                    "Did not have a reply stored before hikChangeSetting was run, try again shortly as reply has just been requested.");
            return;
        }

        String body = (listOfReplies.size() == 0) ? "empty" : listOfReplies.element();
        body = body.replace(findOldValue, newValue);

        logger.debug("Reply from camera was {}", listOfReplies.element());
        logger.debug("body is {}", body);
        listOfReplies.clear();

        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, new HttpMethod("PUT"),
                httpRequestURL);
        request.headers().set(HttpHeaderNames.HOST, ipAddress);
        request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        request.headers().set(HttpHeaderNames.ACCEPT_ENCODING, HttpHeaderValues.GZIP);
        request.headers().add(HttpHeaderNames.CONTENT_TYPE, "application/x-www-form-urlencoded; charset=UTF-8");
        ByteBuf bbuf = Unpooled.copiedBuffer(body, StandardCharsets.UTF_8);
        request.headers().set(HttpHeaderNames.CONTENT_LENGTH, bbuf.readableBytes());
        request.content().clear().writeBytes(bbuf);
        sendHttpPUT(httpRequestURL, request);
    }

    public void sendHttpPUT(String httpRequestURL, FullHttpRequest request) {
        putRequestWithBody = request; // use Global so the authhandler can use it when resent with DIGEST.
        sendHttpRequest("PUT", httpRequestURL, null, false);
    }

    public void sendHttpGET(String httpRequestURL) {
        sendHttpRequest("GET", httpRequestURL, null, false);
    }

    // Always use this as sendHttpGET(GET/POST/PUT/DELETE, "/foo/bar",null,false)//
    // The authHandler will use this method with a digest string as needed.
    public boolean sendHttpRequest(String httpMethod, String httpRequestURL, String digestString,
            boolean useNewChannel) {

        Channel ch;
        ChannelFuture chFuture = null;
        CommonCameraHandler commonHandler;
        MyNettyAuthHandler authHandler;
        AmcrestHandler amcrestHandler;

        if (mainBootstrap == null) {
            mainBootstrap = new Bootstrap();
            mainBootstrap.group(mainEventLoopGroup);
            mainBootstrap.channel(NioSocketChannel.class);
            mainBootstrap.option(ChannelOption.SO_KEEPALIVE, true);
            mainBootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10000);
            mainBootstrap.option(ChannelOption.SO_SNDBUF, 1024 * 8);
            mainBootstrap.option(ChannelOption.SO_RCVBUF, 1024 * 1024);
            mainBootstrap.option(ChannelOption.TCP_NODELAY, true);
            mainBootstrap.handler(new ChannelInitializer<SocketChannel>() {

                @Override
                public void initChannel(SocketChannel socketChannel) throws Exception {
                    // RtspResponseDecoder //RtspRequestEncoder // try in the pipeline soon//
                    // IdleStateHandler(readerIdleTime,writerIdleTime,allIdleTime)
                    socketChannel.pipeline().addLast("idleStateHandler", new IdleStateHandler(15, 0, 0));
                    socketChannel.pipeline().addLast(new HttpClientCodec());
                    socketChannel.pipeline().addLast(new HttpContentDecompressor());
                    socketChannel.pipeline().addLast("authHandler",
                            new MyNettyAuthHandler(username, password, thing.getHandler()));
                    socketChannel.pipeline().addLast("commonHandler", new CommonCameraHandler(globalUrl));

                    switch (thing.getThingTypeUID().getId()) {
                        case "AMCREST":
                            socketChannel.pipeline().addLast("amcrestHandler", new AmcrestHandler());
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
                        default:
                            socketChannel.pipeline().addLast(new HikvisionHandler());
                            break;
                    }
                }
            });
        }

        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, new HttpMethod(httpMethod),
                httpRequestURL);
        request.headers().set(HttpHeaderNames.HOST, ipAddress);
        request.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
        request.headers().set(HttpHeaderNames.ACCEPT_ENCODING, HttpHeaderValues.GZIP);

        if (basicAuth != null) {
            if (useDigestAuth) {
                logger.warn("Camera at IP:{} had both Basic and Digest set to be used", ipAddress);
                setBasicAuth(false);
            } else {
                request.headers().set(HttpHeaderNames.AUTHORIZATION, "Basic " + basicAuth);
            }
        }

        if (useDigestAuth) {
            if (digestString != null) {
                if (httpMethod.contentEquals("PUT")) {
                    request = putRequestWithBody;
                    logger.debug("Request is for a PUT and is {}", request);
                }

                logger.debug("Resending using a fresh DIGEST \tURL:{}", httpRequestURL);
                request.headers().set(HttpHeaderNames.AUTHORIZATION, "Digest " + digestString);
            }
        }

        logger.debug("Sending camera at IP:{} the \tURL:{}", ipAddress, httpRequestURL);

        byte indexInLists = (byte) listOfRequests.indexOf(httpRequestURL);

        if (indexInLists >= 0) {
            if (!useNewChannel) {
                ch = listOfChannels.get(indexInLists);
                if (ch.isOpen()) {
                    logger.debug("****Using the already open channel:{} \t{}:{}", indexInLists, httpMethod,
                            httpRequestURL);

                    authHandler = (MyNettyAuthHandler) ch.pipeline().get("authHandler");
                    authHandler.setURL(httpMethod, httpRequestURL);
                    ch.writeAndFlush(request);
                    request = null;
                    return true;
                } else {
                    logger.debug("!!!!!!!!!!!!!! Channel at index {} was found closed.", indexInLists);
                }
            }
        }

        globalUrl = httpRequestURL;
        chFuture = mainBootstrap.connect(new InetSocketAddress(ipAddress, port));
        chFuture.awaitUninterruptibly(); // ChannelOption.CONNECT_TIMEOUT_MILLIS means this will not hang here forever.

        if (!chFuture.isSuccess()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "Connection Timeout: Check your IP is correct and the camera can be reached.");
            logger.error("Can not connect to the camera at {}:{} check your network for issues.", ipAddress, port);
            dispose();

            cameraConnectionJob = cameraConnection.scheduleAtFixedRate(pollingCameraConnection, 54, 60,
                    TimeUnit.SECONDS);
            return false;
        }

        ch = chFuture.channel();
        commonHandler = (CommonCameraHandler) ch.pipeline().get("commonHandler");
        authHandler = (MyNettyAuthHandler) ch.pipeline().get("authHandler");

        if ("AMCREST".contentEquals(thing.getThingTypeUID().getId())) {
            amcrestHandler = (AmcrestHandler) ch.pipeline().get("amcrestHandler");
            amcrestHandler.setURL(httpRequestURL);
        }
        commonHandler.setURL(httpRequestURL);
        authHandler.setURL(httpMethod, httpRequestURL);

        indexInLists = (byte) listOfRequests.indexOf("closed");
        if (indexInLists == -1) {
            listOfRequests.addLast(httpRequestURL);
            listOfChannels.addLast(ch);
            logger.debug("Have  opened  a  brand NEW channel:{} \t{}:{}", listOfRequests.size() - 1, httpMethod,
                    httpRequestURL);
        } else {
            listOfRequests.set(indexInLists, httpRequestURL);
            listOfChannels.set(indexInLists, ch);
            logger.debug("Have  opened  a  brand new channel:{} \t{}:{}", indexInLists, httpMethod, httpRequestURL);
        }

        chFuture = ch.writeAndFlush(request);
        chFuture.addListener(commonHandler.flushListener);
        chFuture = ch.closeFuture();
        chFuture.addListener(commonHandler.closeChannelListener);

        // Cleanup
        request = null;
        return true;
    }

    // These methods handle the response from all Camera brands, nothing specific to any brand should be in here //
    private class CommonCameraHandler extends ChannelDuplexHandler {
        private int bytesToRecieve = 0; // default to 0.5Mb for cameras that do not send a Content-Length
        private int bytesAlreadyRecieved = 0;
        private byte[] lastSnapshot;
        private String incomingMessage;
        private String contentType = "empty";
        private Object reply = null;
        private String requestUrl;
        private boolean statusCodeSuccess = false;
        private boolean closeConnection = true;

        CommonCameraHandler(String url) {
            requestUrl = url;
        }

        public void setURL(String url) {
            requestUrl = url;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {

            logger.debug(msg.toString());

            if (msg instanceof HttpResponse) {
                HttpResponse response = (HttpResponse) msg;
                if (response.status().code() != 200) {
                    logger.trace(msg.toString());
                } else {
                    statusCodeSuccess = true;
                    logger.trace(msg.toString());
                }
                if (!response.headers().isEmpty()) {
                    for (CharSequence name : response.headers().names()) {
                        for (CharSequence value : response.headers().getAll(name)) {
                            if (name.toString().equalsIgnoreCase("Content-Type")) {
                                contentType = value.toString();
                            } else if (name.toString().equalsIgnoreCase("Content-Length")) {
                                bytesToRecieve = Integer.parseInt(value.toString());
                            } else if (name.toString().equalsIgnoreCase("Connection")) {
                                if (value.toString().contains("keep-alive")) {
                                    closeConnection = false;
                                }
                            }
                        }
                    }
                    if (contentType.contains("multipart")) {
                        closeConnection = false; // HIKVISION needs this for the alertStream to stay open.
                    } else if (closeConnection) {
                        byte indexInLists = (byte) listOfChannels.indexOf(ctx.channel());
                        if (indexInLists >= 0) {
                            listOfRequests.set(indexInLists, "closing");
                            logger.debug("Channel marked as closing, channel:{} \tURL:{}", indexInLists, requestUrl);
                        } else {
                            logger.debug("!!!! Could not find the ch for a Connection: close URL:{}", requestUrl);
                        }
                    }
                }
            }

            if (msg instanceof HttpContent) {
                HttpContent content = (HttpContent) msg;

                if (content instanceof DefaultHttpContent) {
                    if (contentType.contains("image/jpeg")) {
                        if (bytesToRecieve == 0) {
                            bytesToRecieve = 512000; // 0.512Mbyte when no Content-Length is sent
                            logger.debug("Camera has no Content-Length header, we have to guess how much RAM.");
                        }
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
                        }
                    } else if (statusCodeSuccess) { // incomingMessage that is not an IMAGE nor a 401
                        if (incomingMessage == null) {
                            incomingMessage = content.content().toString(CharsetUtil.UTF_8);
                        } else {
                            incomingMessage += content.content().toString(CharsetUtil.UTF_8);
                        }
                        content.content().release();// must be here or a memory leak occurs.
                        bytesAlreadyRecieved = incomingMessage.length();
                    } else {
                        content.content().release();// must be here or a memory leak occurs.
                    }

                    if (content instanceof LastHttpContent) {
                        if (contentType.contains("image/jpeg") && bytesAlreadyRecieved != 0) {
                            updateState(CHANNEL_IMAGE, new RawType(lastSnapshot, "image/jpeg"));
                            lastSnapshot = null;
                            if (closeConnection) {
                                logger.debug(
                                        "Snapshot recieved: Binding will now close the channel as keep-alive was not found in the headers.");
                                ctx.close();
                            } else {
                                logger.debug("Snapshot recieved: Binding will now keep-alive the channel.");
                            }
                        }

                        // If it is not an image send it on to the next handler//
                        if (!contentType.contains("image/jpeg") && bytesAlreadyRecieved != 0) {
                            reply = incomingMessage;
                            incomingMessage = null;
                            bytesToRecieve = 0;
                            bytesAlreadyRecieved = 0;
                            super.channelRead(ctx, reply);
                        }
                    }

                    if (contentType.contains("multipart")) {
                        // HIKVISION alertStream never has a LastHttpContent as it always stays open//
                        if (!contentType.contains("image/jpeg") && bytesAlreadyRecieved != 0) {
                            reply = incomingMessage;
                            incomingMessage = null;
                            bytesToRecieve = 0;
                            bytesAlreadyRecieved = 0;
                            super.channelRead(ctx, reply);
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
            logger.debug("++++ CommonCameraHandler created. {} channels tracked (some may be closed). \tURL:{}",
                    listOfRequests.size(), requestUrl);
        }

        @Override
        public void handlerRemoved(ChannelHandlerContext ctx) {
            byte indexInLists = (byte) listOfChannels.indexOf(ctx.channel());
            if (indexInLists >= 0) {
                logger.debug("commonCameraHandler closed channel:{} \tURL:{}", indexInLists, requestUrl);
                listOfRequests.set(indexInLists, "closed");
            } else {
                logger.warn("Could not find the channel when removing the handler!");
            }
            lastSnapshot = null;
            bytesAlreadyRecieved = 0;
            contentType = null;
            reply = null;

            logger.debug("---- Closing CommonCameraHandler. \tURL:{}", requestUrl);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            byte indexInLists = (byte) listOfChannels.indexOf(ctx.channel());
            if (indexInLists >= 0) {
                listOfRequests.set(indexInLists, "closed");
            } else {
                logger.warn("!!!! exceptionCaught could not located the channel to close it down");
            }

            logger.debug("!!!! Camera has closed the channel \tURL:{} Cause reported is: {}", requestUrl, cause);
            ctx.close();
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            if (evt instanceof IdleStateEvent) {
                IdleStateEvent e = (IdleStateEvent) evt;
                // If camera does not use the channel for X time it will close.
                if (e.state() == IdleState.READER_IDLE) {
                    logger.debug("!!!! Channel was found idle for more than 15 seconds so closing it down");
                    byte indexInLists = (byte) listOfChannels.indexOf(ctx.channel());
                    if (indexInLists >= 0) {
                        listOfRequests.set(indexInLists, "closing");
                    } else {
                        logger.warn("!!!! Channel was found idle but could not located it to close it down");
                    }
                    ctx.close();
                } else if (e.state() == IdleState.WRITER_IDLE) {
                    // ctx.writeAndFlush("ping\r\n");
                }
            }
        }

        private final ChannelFutureListener flushListener = new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) throws Exception {
                if (!future.isSuccess()) {
                    logger.error("Error trying to flush channel. Cause reported is:{}", future.cause().toString());
                    future.channel().close();
                }
            }
        };

        private final ChannelFutureListener closeChannelListener = new ChannelFutureListener() {

            @Override
            public void operationComplete(ChannelFuture future) throws Exception {

                if (!future.isSuccess()) {
                    logger.error("Something is stoping the connection from closing, {}", future.cause().toString());
                    future.channel().close();
                } else {

                }
            }
        };

    }

    private class AmcrestHandler extends ChannelDuplexHandler {
        private String requestUrl = "Empty";

        public void setURL(String url) {
            requestUrl = url;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            String content = msg.toString();

            if (!content.isEmpty()) {
                logger.debug("HTTP Result back from camera is \t:{}:", content);
            }

            switch (content) {
                case "Error: No Events\r\n":
                    if ("/cgi-bin/eventManager.cgi?action=getEventIndexes&code=VideoMotion".equals(requestUrl)) {
                        updateState(CHANNEL_MOTION_ALARM, OnOffType.valueOf("OFF"));
                        firstMotionAlarm = false;
                    } else if ("/cgi-bin/eventManager.cgi?action=getEventIndexes&code=AudioMutation"
                            .equals(requestUrl)) {
                        updateState(CHANNEL_AUDIO_ALARM, OnOffType.valueOf("OFF"));
                        firstAudioAlarm = false;
                    }
                    break;

                case "channels[0]=0\r\n":
                    if ("/cgi-bin/eventManager.cgi?action=getEventIndexes&code=VideoMotion".equals(requestUrl)) {
                        motionDetected(CHANNEL_MOTION_ALARM);
                    } else if ("/cgi-bin/eventManager.cgi?action=getEventIndexes&code=AudioMutation"
                            .equals(requestUrl)) {
                        audioDetected();
                    }
                    break;
            }

            if (content.contains("table.MotionDetect[" + nvrChannel + "].Enable=false")) {
                updateState(CHANNEL_ENABLE_MOTION_ALARM, OnOffType.valueOf("OFF"));
            } else if (content.contains("table.MotionDetect[" + nvrChannel + "].Enable=true")) {
                updateState(CHANNEL_ENABLE_MOTION_ALARM, OnOffType.valueOf("ON"));
            }
            ctx.close();
        }
    }

    private class FoscamHandler extends ChannelDuplexHandler {

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            String content = msg.toString();
            if (!content.isEmpty()) {
                logger.debug("HTTP Result back from camera is \t:{}:", content);
            }

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
                    sendHttpGET(getCorrectUrlFormat(snapshotUri));
                    firstMotionAlarm = true;
                } else if (updateImageEvents.contains("4")) {
                    sendHttpGET(getCorrectUrlFormat(snapshotUri));
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
                    sendHttpGET(getCorrectUrlFormat(snapshotUri));
                    firstAudioAlarm = true;
                } else if (updateImageEvents.contains("5")) {
                    sendHttpGET(getCorrectUrlFormat(snapshotUri));
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
            content = null;
            ctx.close();
        }
    }

    private class InstarHandler extends ChannelDuplexHandler {
        String content;

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            content = msg.toString();

            if (!content.isEmpty()) {
                logger.debug("HTTP Result back from camera is \t:{}:", content);
                // Audio Alarm
                String aa_enable = searchString(content, "var aa_enable = \"");
                if ("1".equals(aa_enable)) {
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
                if ("1".equals(m1_enable)) {
                    updateState(CHANNEL_ENABLE_MOTION_ALARM, OnOffType.valueOf("ON"));
                } else {
                    updateState(CHANNEL_ENABLE_MOTION_ALARM, OnOffType.valueOf("OFF"));
                }
            }
        }

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
        }

        @Override
        public void handlerRemoved(ChannelHandlerContext ctx) {
            content = null;
        }
    }

    private class HikvisionHandler extends ChannelDuplexHandler {
        byte lineCount, vmdCount, leftCount, takenCount, faceCount = 0;

        void countDown() {
            if (lineCount > 1) {
                lineCount--;
            }
            if (vmdCount > 1) {
                vmdCount--;
            }
            if (leftCount > 1) {
                leftCount--;
            }
            if (takenCount > 1) {
                takenCount--;
            }
            if (faceCount > 1) {
                faceCount--;
            }
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            String content = msg.toString();
            if (!content.isEmpty()) {
                logger.debug("HTTP Result back from camera is \t:{}:", content);
            }

            // Alarm checking goes in here//
            if (content.contains("<EventNotificationAlert version=\"")) {
                if (content.contains("hannelID>" + nvrChannel + "</")) {// some camera use c or <dynChannelID>

                    if (content.contains("<eventType>linedetection</eventType>")) {
                        motionDetected(CHANNEL_LINE_CROSSING_ALARM);
                        lineCount = 5;
                        countDown();
                    }
                    if (content.contains("<eventType>VMD</eventType>")) {
                        motionDetected(CHANNEL_MOTION_ALARM);
                        vmdCount = 5;
                        if (vmdCount > 0) {
                            countDown();
                        }
                    }
                    if (content.contains("<eventType>facedetection</eventType>")) {
                        updateState(CHANNEL_FACE_DETECTED, OnOffType.valueOf("ON"));
                        faceCount = 5;
                        if (faceCount > 0) {
                            countDown();
                        }
                    }
                    if (content.contains("<eventType>unattendedBaggage</eventType>")) {
                        updateState(CHANNEL_ITEM_LEFT, OnOffType.valueOf("ON"));
                        leftCount = 5;
                        if (leftCount > 0) {
                            countDown();
                        }
                    }
                    if (content.contains("<eventType>attendedBaggage</eventType>")) {
                        updateState(CHANNEL_ITEM_TAKEN, OnOffType.valueOf("ON"));
                        takenCount = 5;
                        if (takenCount > 0) {
                            countDown();
                        }
                    }
                    if (content.contains("<eventType>videoloss</eventType>\r\n<eventState>inactive</eventState>")) {
                        firstMotionAlarm = false;
                        countDown();
                        countDown();
                        countDown();
                    }
                } else if (content.contains("<channelID>0</channelID>")) {// NVR uses channel 0 to say all channels
                    if (content.contains("<eventType>videoloss</eventType>\r\n<eventState>inactive</eventState>")) {
                        firstMotionAlarm = false;
                        countDown();
                        countDown();
                        countDown();
                    }
                }
                if (lineCount-- == 1) {
                    updateState(CHANNEL_LINE_CROSSING_ALARM, OnOffType.valueOf("OFF"));
                }
                if (vmdCount-- == 1) {
                    updateState(CHANNEL_MOTION_ALARM, OnOffType.valueOf("OFF"));
                }
                if (leftCount-- == 1) {
                    updateState(CHANNEL_ITEM_LEFT, OnOffType.valueOf("OFF"));
                }
                if (takenCount-- == 1) {
                    updateState(CHANNEL_ITEM_TAKEN, OnOffType.valueOf("OFF"));
                }
                if (faceCount-- == 1) {
                    updateState(CHANNEL_FACE_DETECTED, OnOffType.valueOf("OFF"));
                }
            }

            // determine if the motion detection is turned on or off.
            else if (content
                    .contains("<MotionDetection version=\"2.0\" xmlns=\"http://www.hikvision.com/ver20/XMLSchema\">")) {
                listOfReplies.addLast(content);

                if (content.contains("<enabled>true</enabled>")) {
                    updateState(CHANNEL_ENABLE_MOTION_ALARM, OnOffType.valueOf("ON"));
                } else if (content.contains("<enabled>false</enabled>")) {
                    updateState(CHANNEL_ENABLE_MOTION_ALARM, OnOffType.valueOf("OFF"));
                }
            }
            content = null;
        }
    }

    private class DahuaHandler extends ChannelDuplexHandler {

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            String content = msg.toString();
            if (!content.isEmpty()) {
                logger.debug("HTTP Result back from camera is \t:{}:", content);
            }

            // determine if the motion detection is turned on or off.
            if (content.contains("table.Alarm[0].Enable=true")) {
                updateState(CHANNEL_ENABLE_MOTION_ALARM, OnOffType.valueOf("ON"));
            } else if (content.contains("table.Alarm[0].Enable=false")) {
                updateState(CHANNEL_ENABLE_MOTION_ALARM, OnOffType.valueOf("OFF"));
            }

            // Handle motion alarm
            if (content.contains("Code=VideoMotion;action=Start;index=")) {
                motionDetected(CHANNEL_MOTION_ALARM);
            } else if (content.contains("Code=VideoMotion;action=Stop;index=")) {
                updateState(CHANNEL_MOTION_ALARM, OnOffType.valueOf("OFF"));
                firstMotionAlarm = false;
            }
            content = null;
        }
    }

    public IpCameraHandler(Thing thing) {
        super(thing);
    }

    private void motionDetected(String thisAlarmsChannel) {
        updateState(thisAlarmsChannel.toString(), OnOffType.valueOf("ON"));
        if (updateImageEvents.contains("2") && !firstMotionAlarm) {
            sendHttpGET(getCorrectUrlFormat(snapshotUri));
            firstMotionAlarm = true;
        } else if (updateImageEvents.contains("4")) {
            sendHttpGET(getCorrectUrlFormat(snapshotUri));
        }
    }

    private void audioDetected() {
        updateState(CHANNEL_AUDIO_ALARM, OnOffType.valueOf("ON"));
        if (updateImageEvents.contains("3") && !firstAudioAlarm) {
            sendHttpGET(getCorrectUrlFormat(snapshotUri));
            firstAudioAlarm = true;
        } else if (updateImageEvents.contains("5")) {
            sendHttpGET(getCorrectUrlFormat(snapshotUri));
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
        PTZVector pv;
        try {
            pv = ptzDevices.getPosition(profileToken);
            if (pv != null) {
                return pv;
            }
        } catch (NullPointerException e) {
            logger.error("NPE occured when trying to fetch the cameras PTZ position");
        }

        logger.warn(
                "Camera replied with null when asked what its position was, going to fake the position so PTZ still works.");
        pv = new PTZVector();
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
                            sendHttpGET("/cgi-bin/CGIProxy.fcgi?cmd=getAudioAlarmConfig&usr=" + username + "&pwd="
                                    + password);
                            break;
                    }
                    break;
                case CHANNEL_ENABLE_AUDIO_ALARM:
                    switch (thing.getThingTypeUID().getId()) {
                        case "FOSCAM":
                            sendHttpGET("/cgi-bin/CGIProxy.fcgi?cmd=getAudioAlarmConfig&usr=" + username + "&pwd="
                                    + password);
                            break;
                    }
                    break;
                case CHANNEL_ENABLE_MOTION_ALARM:
                    switch (thing.getThingTypeUID().getId()) {

                        case "AMCREST":
                            sendHttpGET("/cgi-bin/configManager.cgi?action=getConfig&name=MotionDetect[" + nvrChannel
                                    + "]");
                            break;

                        case "FOSCAM":
                            sendHttpGET("/cgi-bin/CGIProxy.fcgi?cmd=getDevState&usr=" + username + "&pwd=" + password);
                            break;
                        case "HIKVISION":
                            sendHttpGET("/ISAPI/System/Video/inputs/channels/" + nvrChannel + "01/motionDetection");
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
                        break;
                    case "HIKVISION":
                        // sendHttpGET( "/Event/notification/alertStream");
                        break;
                }
                break;

            case CHANNEL_UPDATE_IMAGE_NOW:
                if (snapshotUri != null) {
                    sendHttpGET(getCorrectUrlFormat(snapshotUri));
                }

                break;

            case CHANNEL_THRESHOLD_AUDIO_ALARM:

                switch (thing.getThingTypeUID().getId()) {

                    case "FOSCAM":
                        int value = Math.round(Float.valueOf(command.toString()));
                        if (value == 0) {
                            sendHttpGET("/cgi-bin/CGIProxy.fcgi?cmd=setAudioAlarmConfig&isEnable=0&usr=" + username
                                    + "&pwd=" + password);
                        } else if (value <= 33) {
                            sendHttpGET("/cgi-bin/CGIProxy.fcgi?cmd=setAudioAlarmConfig&isEnable=1&sensitivity=0&usr="
                                    + username + "&pwd=" + password);
                        } else if (value <= 66) {
                            sendHttpGET("/cgi-bin/CGIProxy.fcgi?cmd=setAudioAlarmConfig&isEnable=1&sensitivity=1&usr="
                                    + username + "&pwd=" + password);
                        } else {
                            sendHttpGET("/cgi-bin/CGIProxy.fcgi?cmd=setAudioAlarmConfig&isEnable=1&sensitivity=2&usr="
                                    + username + "&pwd=" + password);
                        }

                        break;

                    case "INSTAR":
                        value = Math.round(Float.valueOf(command.toString()));
                        if (value == 0) {
                            sendHttpGET("/cgi-bin/hi3510/param.cgi?cmd=setaudioalarmattr&-aa_enable=0");
                        } else {
                            sendHttpGET("/cgi-bin/hi3510/param.cgi?cmd=setaudioalarmattr&-aa_enable=1");
                            sendHttpGET("/cgi-bin/hi3510/param.cgi?cmd=setaudioalarmattr&-aa_enable=1&-aa_value="
                                    + command.toString());
                        }

                        break;
                }

                break;

            case CHANNEL_ENABLE_AUDIO_ALARM:

                switch (thing.getThingTypeUID().getId()) {

                    case "FOSCAM":

                        if ("ON".equals(command.toString())) {
                            sendHttpGET("/cgi-bin/CGIProxy.fcgi?cmd=setAudioAlarmConfig&isEnable=1&usr=" + username
                                    + "&pwd=" + password);
                        } else {
                            sendHttpGET("/cgi-bin/CGIProxy.fcgi?cmd=setAudioAlarmConfig&isEnable=0&usr=" + username
                                    + "&pwd=" + password);
                        }
                        break;

                    case "INSTAR":

                        if ("ON".equals(command.toString())) {
                            sendHttpGET("/cgi-bin/hi3510/param.cgi?cmd=setaudioalarmattr&-aa_enable=1");
                        } else {
                            sendHttpGET("/cgi-bin/hi3510/param.cgi?cmd=setaudioalarmattr&-aa_enable=0");
                        }
                        break;
                }

                break;
            case CHANNEL_ENABLE_MOTION_ALARM:

                switch (thing.getThingTypeUID().getId()) {
                    case "AMCREST":
                        if ("ON".equals(command.toString())) {
                            sendHttpGET("/cgi-bin/configManager.cgi?action=setConfig&MotionDetect[" + nvrChannel
                                    + "].Enable=true");
                        } else {
                            sendHttpGET("/cgi-bin/configManager.cgi?action=setConfig&MotionDetect[" + nvrChannel
                                    + "].Enable=false");
                        }
                        break;

                    case "FOSCAM":
                        if ("ON".equals(command.toString())) {
                            // example of how to setup a zone. Add to end of url
                            // &x1=3042&y1=1604&width1=5185&height1=6229&threshold1=0&sensitivity1=2&valid1=1
                            sendHttpGET("/cgi-bin/CGIProxy.fcgi?cmd=setMotionDetectConfig&isEnable=1&usr=" + username
                                    + "&pwd=" + password);
                            sendHttpGET("/cgi-bin/CGIProxy.fcgi?cmd=setMotionDetectConfig1&isEnable=1&usr=" + username
                                    + "&pwd=" + password);
                        } else {
                            sendHttpGET("/cgi-bin/CGIProxy.fcgi?cmd=setMotionDetectConfig&isEnable=0&usr=" + username
                                    + "&pwd=" + password);
                            sendHttpGET("/cgi-bin/CGIProxy.fcgi?cmd=setMotionDetectConfig1&isEnable=0&usr=" + username
                                    + "&pwd=" + password);
                        }
                        break;
                    case "HIKVISION":
                        if ("ON".equals(command.toString())) {
                            hikChangeSetting("/ISAPI/System/Video/inputs/channels/" + nvrChannel + "01/motionDetection",
                                    "<enabled>false</enabled>", "<enabled>true</enabled>");
                        } else {
                            hikChangeSetting("/ISAPI/System/Video/inputs/channels/" + nvrChannel + "01/motionDetection",
                                    "<enabled>true</enabled>", "<enabled>false</enabled>");
                        }
                        break;

                    case "INSTAR":
                        if ("ON".equals(command.toString())) {
                            sendHttpGET(
                                    "/cgi-bin/hi3510/param.cgi?cmd=setmdattr&-enable=1&-name=1&cmd=setmdattr&-enable=1&-name=2&cmd=setmdattr&-enable=1&-name=3&cmd=setmdattr&-enable=1&-name=4");
                        } else {
                            sendHttpGET(
                                    "/cgi-bin/hi3510/param.cgi?cmd=setmdattr&-enable=0&-name=1&cmd=setmdattr&-enable=0&-name=2&cmd=setmdattr&-enable=0&-name=3&cmd=setmdattr&-enable=0&-name=4");
                        }
                        break;

                    case "DAHUA":
                        if ("ON".equals(command.toString())) {
                            sendHttpGET("/cgi-bin/configManager.cgi?action=setConfig&MotionDetect[0].Enable=true");
                        } else {
                            sendHttpGET("/cgi-bin/configManager.cgi?action=setConfig&MotionDetect[0].Enable=false");
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
            logger.info("Pan is updating to:{} and the cam value is {}", Math.round(currentPanPercentage),
                    currentPanCamValue);
            updateState(CHANNEL_PAN, new PercentType(Math.round(currentPanPercentage)));
        }
    }

    void getAbsoluteTilt() {
        if (ptzDevices != null) {
            currentTiltPercentage = (((tiltRange.getMin() - ptzLocation.getPanTilt().getY()) * -1)
                    / ((tiltRange.getMin() - tiltRange.getMax()) * -1)) * 100;
            currentTiltCamValue = ((((tiltRange.getMin() - tiltRange.getMax()) * -1) / 100) * currentTiltPercentage
                    + tiltRange.getMin());
            logger.info("Tilt is updating to:{} and the cam value is {}", Math.round(currentTiltPercentage),
                    currentTiltCamValue);
            updateState(CHANNEL_TILT, new PercentType(Math.round(currentTiltPercentage)));
        }
    }

    void getAbsoluteZoom() {
        if (ptzDevices != null) {
            currentZoomPercentage = (((zoomMin - ptzLocation.getZoom().getX()) * -1) / ((zoomMin - zoomMax) * -1))
                    * 100;
            currentZoomCamValue = ((((zoomMin - zoomMax) * -1) / 100) * currentZoomPercentage + zoomMin);

            logger.info("Zoom is updating to:{} and the cam value is {}", Math.round(currentZoomPercentage),
                    currentZoomCamValue);
            updateState(CHANNEL_ZOOM, new PercentType(Math.round(currentZoomPercentage)));
        }
    }

    void setAbsolutePan(Float panValue) {

        if (ptzDevices != null) {

            if (onvifCamera != null && panRange != null && tiltRange != null) {
                try {
                    currentPanCamValue = ((((panRange.getMin() - panRange.getMax()) * -1) / 100) * panValue
                            + panRange.getMin());
                    logger.debug("Cameras Pan  has changed to:{}", currentPanCamValue);
                    ptzDevices.absoluteMove(profileToken, currentPanCamValue, currentTiltCamValue, currentZoomCamValue);
                } catch (SOAPException e) {
                    logger.error("SOAP exception occured");
                } catch (NullPointerException e) {
                    logger.error("NPE occured when trying to move the cameras Pan with ONVIF");
                }
            }
        }
    }

    void setAbsoluteTilt(Float tiltValue) {
        if (ptzDevices != null) {

            if (onvifCamera != null && panRange != null && tiltRange != null) {
                try {
                    currentTiltCamValue = ((((tiltRange.getMin() - tiltRange.getMax()) * -1) / 100) * tiltValue
                            + tiltRange.getMin());
                    logger.debug("Cameras Tilt has changed to:{}", currentTiltCamValue);
                    ptzDevices.absoluteMove(profileToken, currentPanCamValue, currentTiltCamValue, currentZoomCamValue);
                } catch (SOAPException e) {
                    logger.error("SOAP exception occured");
                } catch (NullPointerException e) {
                    logger.error("NPE occured when trying to move the cameras Tilt with ONVIF");
                }
            }
        }
    }

    void setAbsoluteZoom(Float zoomValue) {

        if (ptzDevices != null) {
            if (onvifCamera != null && panRange != null && tiltRange != null) {
                try {
                    currentZoomCamValue = ((((zoomMin - zoomMax) * -1) / 100) * zoomValue + zoomMin);
                    logger.info("Cameras Zoom has changed to:{}", currentZoomCamValue);
                    ptzDevices.absoluteMove(profileToken, currentPanCamValue, currentTiltCamValue, currentZoomCamValue);
                } catch (SOAPException e) {
                    logger.error("SOAP exception occured");
                } catch (NullPointerException e) {
                    logger.error("NPE occured when trying to move the cameras Zoom with ONVIF");
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
                    if (sendHttpRequest("GET", getCorrectUrlFormat(snapshotUri), null, false)) {
                        updateStatus(ThingStatus.ONLINE);
                        cameraConnectionJob.cancel(true);
                        cameraConnectionJob = null;

                        fetchCameraOutputJob = fetchCameraOutput.scheduleAtFixedRate(pollingCamera, 5000,
                                Integer.parseInt(config.get(CONFIG_POLL_CAMERA_MS).toString()), TimeUnit.MILLISECONDS);
                        sendHttpGET(getCorrectUrlFormat(snapshotUri));
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

                    logger.debug("Fetching the number of Media Profiles this camera supports.");
                    profiles = onvifCamera.getDevices().getProfiles();
                    if (profiles == null) {
                        logger.error("Camera replied with NULL when trying to get a list of the media profiles");
                    }
                    logger.debug("Checking the selected Media Profile is a valid number.");
                    if (selectedMediaProfile > profiles.size()) {
                        logger.warn(
                                "The selected Media Profile in the binding is higher than the max supported profiles. Changing to use Media Profile 0.");
                        selectedMediaProfile = 0;
                    }

                    logger.debug("Fetching a Token for the selected Media Profile.");
                    profileToken = profiles.get(selectedMediaProfile).getToken();
                    if (profileToken == null) {
                        logger.error("Camera replied with NULL when trying to get a media profile token.");
                    }

                    if (snapshotUri == null) {
                        logger.debug("Auto fetching the snapshot URL for the selected Media Profile.");
                        snapshotUri = onvifCamera.getMedia().getSnapshotUri(profileToken);
                    }

                    if (logger.isDebugEnabled()) {

                        logger.debug("About to fetch some information about the Media Profiles from the camera");
                        for (int x = 0; x < profiles.size(); x++) {
                            VideoEncoderConfiguration result = profiles.get(x).getVideoEncoderConfiguration();
                            logger.debug("*********** Media Profile {} details reported by camera at IP:{} ***********",
                                    x, ipAddress);
                            if (selectedMediaProfile == x) {
                                logger.debug(
                                        "Camera will use this Media Profile unless you change it in the bindings settings.");
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

                    logger.debug("About to interrogate the camera to see if it supports PTZ.");

                    ptzDevices = onvifCamera.getPtz();
                    if (ptzDevices != null) {

                        if (ptzDevices.isPtzOperationsSupported(profileToken)
                                && ptzDevices.isAbsoluteMoveSupported(profileToken)) {

                            logger.info(
                                    "Camera is reporting that it supports PTZ control with Absolute movement via ONVIF");

                            logger.debug("Checking Pan now.");
                            String temp = ptzDevices.getPanSpaces(profileToken).toString();
                            logger.debug("Pan came back with this string .{}.", temp);
                            temp = ptzDevices.getNode(profileToken).toString();
                            logger.debug("getNode came back with this string .{}.", temp);

                            panRange = ptzDevices.getPanSpaces(profileToken);
                            logger.debug("Checking Tilt now.");
                            tiltRange = ptzDevices.getTiltSpaces(profileToken);
                            logger.debug("Checking Zoom now.");
                            zoomMin = ptzDevices.getZoomSpaces(profileToken).getMin();
                            zoomMax = ptzDevices.getZoomSpaces(profileToken).getMax();

                            logger.debug("Camera has reported the range of movements it supports via PTZ.");
                            if (logger.isDebugEnabled()) {
                                logger.debug("The camera can Pan  from {} to {}", panRange.getMin(), panRange.getMax());
                                logger.debug("The camera can Tilt from {} to {}", tiltRange.getMin(),
                                        tiltRange.getMax());
                                logger.debug("The camera can Zoom from {} to {}", zoomMin, zoomMax);
                            }
                            logger.debug("Fetching the cameras current position.");
                            ptzLocation = getPtzPosition();

                        } else {
                            logger.info("Camera is reporting that it does NOT support Absolute PTZ controls via ONVIF");
                            // null will stop code from running on cameras that do not support PTZ features.
                            ptzDevices = null;
                        }
                    }
                    logger.info(
                            "Finished with PTZ with no errors, now fetching the Video URL for RTSP from the camera.");
                    videoStreamUri = onvifCamera.getMedia().getRTSPStreamUri(profileToken);

                } catch (ConnectException e) {
                    logger.error("Can not connect with ONVIF at IP:{}, it may be the wrong ONVIF_PORT. Fault was {}",
                            ipAddress, e.toString());
                } catch (SOAPException e) {
                    logger.error(
                            "SOAP error when trying to connect with ONVIF. This may indicate your camera does not fully support ONVIF, check for an updated firmware for your camera. Will try and connect with HTTP. Camera at IP:{}, fault was {}",
                            ipAddress, e.toString());
                } catch (NullPointerException e) {
                    logger.error("Following NPE occured when trying to connect to the camera with ONVIF.{}",
                            e.toString());
                    if (ptzDevices != null && tiltRange.equals(null)) {
                        logger.error("NPE occured when asking the camera about PTZ, PTZ controls will not work.");
                        ptzDevices = null;
                    }
                }

                if (snapshotUri != null) {
                    if (sendHttpRequest("GET", getCorrectUrlFormat(snapshotUri), null, false)) {

                        updateState(CHANNEL_IMAGE_URL, new StringType(snapshotUri));
                        if (videoStreamUri != null) {
                            updateState(CHANNEL_VIDEO_URL, new StringType(videoStreamUri));
                        }

                        cameraConnectionJob.cancel(false);
                        cameraConnectionJob = null;

                        fetchCameraOutputJob = fetchCameraOutput.scheduleAtFixedRate(pollingCamera, 1000,
                                Integer.parseInt(config.get(CONFIG_POLL_CAMERA_MS).toString()), TimeUnit.MILLISECONDS);

                        updateStatus(ThingStatus.ONLINE);
                    }
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

            if (listOfRequests.size() > 12) {
                logger.warn(
                        "!!!! There are {} channels being tracked, cleaning out any closed channels now to try and reduce this to below 12.",
                        listOfRequests.size());
                cleanChannels();
            }

            if (snapshotUri != null && updateImageEvents.contains("1")) {
                sendHttpGET(getCorrectUrlFormat(snapshotUri));
            }

            switch (thing.getThingTypeUID().getId()) {
                case "AMCREST":
                    sendHttpGET("/cgi-bin/eventManager.cgi?action=getEventIndexes&code=VideoMotion");
                    sendHttpGET("/cgi-bin/eventManager.cgi?action=getEventIndexes&code=AudioMutation");
                    break;

                case "FOSCAM":
                    sendHttpGET("/cgi-bin/CGIProxy.fcgi?cmd=getDevState&usr=" + username + "&pwd=" + password);
                    break;
                case "HIKVISION":
                    byte indexInLists = (byte) listOfRequests.indexOf("/ISAPI/Event/notification/alertStream");
                    if (indexInLists < 0) {
                        logger.warn(
                                "!!!! Alertstream was not running, Cleaning channels and then going to re-start it now.");
                        cleanChannels();
                        sendHttpGET("/ISAPI/Event/notification/alertStream");
                    }
                    if (listOfReplies.size() < 1) {
                        logger.debug("Fetching the latest state of the motion detection");
                        sendHttpGET("/ISAPI/System/Video/inputs/channels/" + nvrChannel + "01/motionDetection");
                    }
                    break;
                case "INSTAR":
                    // Poll the audio alarm on/off/threshold/...
                    sendHttpGET("/cgi-bin/hi3510/param.cgi?cmd=getaudioalarmattr");
                    // Poll the motion alarm on/off/settings/...
                    sendHttpGET("/cgi-bin/hi3510/param.cgi?cmd=getmdattr");
                    break;
                case "DAHUA":
                    // Poll the alarm configs ie on/off/...
                    sendHttpGET("/cgi-bin/configManager.cgi?action=getConfig&name=Alarm");
                    // Check for alarms, channel is for a NVR and not a single cam.
                    sendHttpGET(
                            "/cgi-bin/eventManager.cgi?action=attach&codes=[VideoMotion,MDResult,VideoBlind,VideoLoss,CrossLineDetection]&channel=["
                                    + nvrChannel + "]");
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
        snapshotUri = (config.get(CONFIG_SNAPSHOT_URL_OVERIDE) == null) ? null
                : config.get(CONFIG_SNAPSHOT_URL_OVERIDE).toString();

        nvrChannel = (config.get(CONFIG_NVR_CHANNEL) == null) ? null : config.get(CONFIG_NVR_CHANNEL).toString();

        if (snapshotUri == null) {
            switch (thing.getThingTypeUID().getId()) {
                case "AMCREST":
                    snapshotUri = "http://" + ipAddress + "/cgi-bin/snapshot.cgi?channel=" + nvrChannel;
                    break;
                case "HIKVISION":
                    snapshotUri = "http://" + ipAddress + "/ISAPI/Streaming/channels/" + nvrChannel + "01/picture";
                    break;
                case "DAHUA":
                    snapshotUri = "http://" + ipAddress + "/cgi-bin/snapshot.cgi?channel=" + nvrChannel;
                    break;
            }
        }

        selectedMediaProfile = (config.get(CONFIG_ONVIF_PROFILE_NUMBER) == null) ? 0
                : Integer.parseInt(config.get(CONFIG_ONVIF_PROFILE_NUMBER).toString());
        updateImageEvents = config.get(CONFIG_IMAGE_UPDATE_EVENTS).toString();

        cameraConnectionJob = cameraConnection.scheduleAtFixedRate(pollingCameraConnection, 0, 64, TimeUnit.SECONDS);
    }

    @Override
    public void dispose() {
        onvifCamera = null;
        basicAuth = null; // clear out stored hash
        useDigestAuth = false;
        cleanChannels();
        listOfRequests.clear();
        listOfChannels.clear();
        listOfReplies.clear();

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
