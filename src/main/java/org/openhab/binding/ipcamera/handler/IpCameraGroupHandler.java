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

package org.openhab.binding.ipcamera.handler;

import static org.openhab.binding.ipcamera.IpCameraBindingConstants.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.config.core.Configuration;
import org.eclipse.smarthome.core.library.types.StringType;
import org.eclipse.smarthome.core.thing.ChannelUID;
import org.eclipse.smarthome.core.thing.Thing;
import org.eclipse.smarthome.core.thing.ThingStatus;
import org.eclipse.smarthome.core.thing.ThingTypeUID;
import org.eclipse.smarthome.core.thing.binding.BaseThingHandler;
import org.eclipse.smarthome.core.types.Command;
import org.openhab.binding.ipcamera.internal.IpCameraDiscoveryService;
import org.openhab.binding.ipcamera.internal.StreamServerGroupHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.handler.timeout.IdleStateHandler;

/**
 * The {@link IpCameraGroupHandler} is responsible for finding cameras that are part of this group and displaying a
 * group picture.
 *
 * @author Matthew Skinner - Initial contribution
 */

public class IpCameraGroupHandler extends BaseThingHandler {
    private final Logger logger = LoggerFactory.getLogger(IpCameraDiscoveryService.class);
    public static final Set<ThingTypeUID> SUPPORTED_THING_TYPES = new HashSet<ThingTypeUID>(
            Arrays.asList(THING_TYPE_GROUPDISPLAY));
    private Configuration config;
    ArrayList<IpCameraHandler> cameraOrder = new ArrayList<IpCameraHandler>(1);
    private EventLoopGroup serversLoopGroup = new NioEventLoopGroup();
    private final ScheduledExecutorService pollCameraGroup = Executors.newSingleThreadScheduledExecutor();
    private @Nullable ScheduledFuture<?> pollCameraGroupJob = null;
    private @Nullable ServerBootstrap serverBootstrap;
    private @Nullable ChannelFuture serverFuture = null;
    public String hostIp = "0.0.0.0";
    public int serverPort = 0;
    String playList = "";
    int cameraIndex = 0;
    int mediaSequence = 1;

    public IpCameraGroupHandler(Thing thing) {
        super(thing);
    }

    public String getWhiteList() {
        return config.get(CONFIG_IP_WHITELIST).toString();
    }

    public String getPlayList() {
        return playList;
    }

    public String getOutputFolder(int index) {
        IpCameraHandler handle = cameraOrder.get(index);
        return (String) handle.config.get(CONFIG_FFMPEG_OUTPUT);
    }

    private String parseLastFile(int cameraIndex) {
        IpCameraHandler handle = cameraOrder.get(cameraIndex);
        try {
            String file = handle.config.get(CONFIG_FFMPEG_OUTPUT).toString() + "ipcamera.m3u8";
            playList = new String(Files.readAllBytes(Paths.get(file)));
        } catch (IOException e) {
        }
        String temp = playList.substring(playList.lastIndexOf("#EXTINF:"), playList.length());
        temp = temp.replace("ipcamera", cameraIndex + "/ipcamera");
        return temp;
    }

    public void setPlayList() {
        String playNow = parseLastFile(cameraIndex);
        String playNext = "";
        if (cameraIndex + 1 >= cameraOrder.size()) {
            playNext = parseLastFile(0);
        } else {
            playNext = parseLastFile(cameraIndex + 1);
        }
        playList = "#EXTM3U\n" + "#EXT-X-TARGETDURATION:2\n" + "#EXT-X-VERSION:3\n" + "#EXT-X-MEDIA-SEQUENCE:"
                + mediaSequence++ + "\n" + playNow + playNext;
    }

    private IpCameraGroupHandler getHandle() {
        return this;
    }

    public String getLocalIpAddress() {
        String ipAddress = "";
        try {
            for (Enumeration<NetworkInterface> enumNetworks = NetworkInterface.getNetworkInterfaces(); enumNetworks
                    .hasMoreElements();) {
                NetworkInterface networkInterface = enumNetworks.nextElement();
                for (Enumeration<InetAddress> enumIpAddr = networkInterface.getInetAddresses(); enumIpAddr
                        .hasMoreElements();) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress() && inetAddress.getHostAddress().toString().length() < 18
                            && inetAddress.isSiteLocalAddress()) {
                        ipAddress = inetAddress.getHostAddress().toString();
                        logger.debug("Possible NIC/IP match found:{}", ipAddress);
                    }
                }
            }
        } catch (SocketException ex) {
        }
        return ipAddress;
    }

    public void startStreamServer(boolean start) {

        if (!start) {
            serversLoopGroup.shutdownGracefully(8, 8, TimeUnit.SECONDS);
            serverBootstrap = null;
        } else {
            if (serverBootstrap == null) {
                hostIp = getLocalIpAddress();
                try {
                    serversLoopGroup = new NioEventLoopGroup();
                    serverBootstrap = new ServerBootstrap();
                    serverBootstrap.group(serversLoopGroup);
                    serverBootstrap.channel(NioServerSocketChannel.class);
                    // IP "0.0.0.0" will bind the server to all network connections//
                    serverBootstrap.localAddress(new InetSocketAddress("0.0.0.0", serverPort));
                    serverBootstrap.childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel socketChannel) throws Exception {
                            socketChannel.pipeline().addLast("idleStateHandler", new IdleStateHandler(0, 25, 0));
                            socketChannel.pipeline().addLast("HttpServerCodec", new HttpServerCodec());
                            socketChannel.pipeline().addLast("ChunkedWriteHandler", new ChunkedWriteHandler());
                            socketChannel.pipeline().addLast("streamServerHandler",
                                    new StreamServerGroupHandler(getHandle()));
                        }
                    });
                    serverFuture = serverBootstrap.bind().sync();
                    serverFuture.await(4000);
                    logger.info("IpCamera file server for a group of cameras has started on port {} for all NIC's.",
                            serverPort);
                    updateState(CHANNEL_STREAM_URL,
                            new StringType("http://" + hostIp + ":" + serverPort + "/ipcamera.mjpeg"));
                    updateState(CHANNEL_HLS_URL,
                            new StringType("http://" + hostIp + ":" + serverPort + "/ipcamera.m3u8"));
                    updateState(CHANNEL_IMAGE_URL,
                            new StringType("http://" + hostIp + ":" + serverPort + "/ipcamera.jpg"));
                } catch (Exception e) {
                    logger.error(
                            "Exception occured when starting the streaming server. Try changing the SERVER_PORT to another number: {}",
                            e);
                }
            }
        }
    }

    void addOnlineCamera(String UniqueID) {
        for (IpCameraHandler handler : IpCameraHandler.listOfCameras) {
            if (handler.getThing().getUID().getId().equals(UniqueID)) {
                this.cameraOrder.add(handler);
            }
        }
    }

    void createCameraList() {
        addOnlineCamera(config.get(CONFIG_FIRST_CAM).toString());
        addOnlineCamera(config.get(CONFIG_SECOND_CAM).toString());
        if (config.get(CONFIG_THIRD_CAM) != null) {
            addOnlineCamera(config.get(CONFIG_THIRD_CAM).toString());
        }
        if (config.get(CONFIG_FORTH_CAM) != null) {
            addOnlineCamera(config.get(CONFIG_FORTH_CAM).toString());
        }
    }

    int checkForMotion(int nextCamerasIndex) {
        int checked = 0;
        for (int index = nextCamerasIndex; checked >= cameraOrder.size(); checked++) {
            if (cameraOrder.get(index).motionDetected) {
                return index;
            }
            if (++index >= cameraOrder.size()) {
                index = 0;
            }
        }
        return nextCamerasIndex;
    }

    Runnable pollingCameraGroup = new Runnable() {
        @Override
        public void run() {
            if (cameraOrder.isEmpty()) {
                createCameraList();
                if (!cameraOrder.isEmpty()) {
                    updateStatus(ThingStatus.ONLINE);
                    if (!"-1".contentEquals(config.get(CONFIG_SERVER_PORT).toString())) {
                        startStreamServer(true);
                    }
                }
            } else if (++cameraIndex >= cameraOrder.size()) {
                cameraIndex = 0;
                if (mediaSequence > 500000) {
                    mediaSequence = 0;
                }
            }
            if ((boolean) config.get(CONFIG_MOTION_CHANGES_ORDER)) {
                cameraIndex = checkForMotion(cameraIndex);
            }
        }
    };

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
    }

    @Override
    public void initialize() {
        logger.info("initialize() called for a group thing. NOT IMPLEMENTED YET !!!! ");
        config = thing.getConfiguration();
        serverPort = Integer.parseInt(config.get(CONFIG_SERVER_PORT).toString());
        if (serverPort == -1) {
            logger.warn("The SERVER_PORT = -1 which disables a lot of features. See readme for more info.");
        } else if (serverPort < 1025) {
            logger.warn("The SERVER_PORT is <= 1024 and may cause permission errors under Linux, try a higher port.");
        }
        pollCameraGroupJob = pollCameraGroup.scheduleAtFixedRate(pollingCameraGroup, 5000,
                Integer.parseInt(config.get(CONFIG_POLL_CAMERA_MS).toString()), TimeUnit.MILLISECONDS);
    }

    @Override
    public void dispose() {
        logger.debug("dispose() called for a group thing.");
        startStreamServer(false);
        if (pollCameraGroupJob != null) {
            pollCameraGroupJob.cancel(true);
            pollCameraGroupJob = null;
        }
    }
}
