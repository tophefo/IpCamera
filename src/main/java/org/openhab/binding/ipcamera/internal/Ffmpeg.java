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

import static org.openhab.binding.ipcamera.IpCameraBindingConstants.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.smarthome.core.library.types.OnOffType;
import org.openhab.binding.ipcamera.handler.IpCameraHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link Ffmpeg} is responsible for handling the ffmpeg conversions
 *
 *
 * @author Matthew Skinner - Initial contribution
 */

public class Ffmpeg {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private IpCameraHandler ipCameraHandler;
    private @Nullable Process process = null;
    private String ffmpegCommand = "", format = "";
    private String[] commandArray;
    private StreamRunning streamRunning = new StreamRunning();
    private int keepAlive = 0;

    public void setKeepAlive() {
        // reset to Keep alive ffmpeg for another 60 seconds
        keepAlive = 60 / (Integer.parseInt(ipCameraHandler.config.get(CONFIG_POLL_CAMERA_MS).toString()) / 1000);
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public int getKeepAlive() {
        if (keepAlive < 0) {
            this.stopConverting();
        }
        return --keepAlive;
    }

    public Ffmpeg(IpCameraHandler handle, String location, String inArguments, String input, String outArguments,
            String output, String username, String password) {
        ipCameraHandler = handle;
        String altInput = input;

        if (!password.equals("") && !input.contains("@")) {
            String credentials = username + ":" + password + "@";
            // will not work for https: but currently binding does not use https
            altInput = input.substring(0, 7) + credentials + input.substring(7);
        }
        ffmpegCommand = location + " " + inArguments + " -i " + altInput + " " + outArguments + " " + output;
        commandArray = ffmpegCommand.trim().split("\\s+");
    }

    private class StreamRunning extends Thread {

        @Override
        public void run() {
            try {
                process = Runtime.getRuntime().exec(commandArray);
                @SuppressWarnings("null")
                InputStream errorStream = process.getErrorStream();
                InputStreamReader errorStreamReader = new InputStreamReader(errorStream);
                BufferedReader bufferedReader = new BufferedReader(errorStreamReader);
                String line = null;
                while ((line = bufferedReader.readLine()) != null) {
                    logger.debug("{}", line);
                }
            } catch (IOException e) {
                logger.error("{}", e.toString());
            } finally {
                if ("GIF".contentEquals(format)) {
                    logger.debug("Animated GIF has been created and is ready for use.");
                    try {
                        // Without a small delay, Pushover sends no file 10% of time.
                        Thread.sleep(500);
                    } catch (InterruptedException e) {

                    }
                    ipCameraHandler.setChannelState(CHANNEL_UPDATE_GIF, OnOffType.valueOf("OFF"));
                }
            }
        }
    }

    public void startConverting() {
        if (!streamRunning.isAlive()) {
            streamRunning = new StreamRunning();
            logger.debug("Starting ffmpeg with this command now:{}", ffmpegCommand);
            setKeepAlive();
            streamRunning.start();
            try {
                Thread.sleep(4500);
            } catch (InterruptedException e) {
            }
        }
    }

    @SuppressWarnings("null")
    public void stopConverting() {
        if (streamRunning.isAlive()) {
            logger.debug("Stopping ffmpeg now");
            if (process != null) {
                process.destroy();
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
            }
        }
    }
}
