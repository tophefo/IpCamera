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
package org.openhab.binding.ipcamera.internal;

import java.security.MessageDigest;
import java.util.Random;

import org.eclipse.smarthome.core.thing.binding.ThingHandler;
import org.openhab.binding.ipcamera.handler.IpCameraHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpResponse;

/**
 * The {@link MyNettyAuthHandler} is responsible for handling the basic and digest auths
 *
 *
 * @author Matthew Skinner - Initial contribution
 */

public class MyNettyAuthHandler extends ChannelDuplexHandler {

    private final Logger logger = LoggerFactory.getLogger(getClass());
    private IpCameraHandler myHandler;
    private String username, password;
    private String httpMethod, httpURL;

    public MyNettyAuthHandler(String user, String pass, ThingHandler handle) {
        myHandler = (IpCameraHandler) handle;
        username = user;
        password = pass;
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
            logger.error("NoSuchAlgorithmException error when calculating MD5 hash");
        }
        return null;
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

    // Method can be used a few ways. processAuth(null, string,string, false) to return the digest on demand, and
    // processAuth(challString, string,string, true) to auto send new packet
    // First run it should not have rawstring as null
    // nonce is reused if rawstring is null so the NC needs to increment to allow this//
    public String processAuth(String authenticate, String httpMethod, String requestURI, boolean reSend) {

        if (authenticate != null) {

            myHandler.realm = searchString(authenticate, "Basic realm=\"");
            if (myHandler.realm != null) {
                if (myHandler.useDigestAuth == true) {
                    logger.error(
                            "Camera appears to be requesting Basic after Digest Auth has already been used, this could be a hacker so not going to reply.");
                    return "Error:Downgrade authenticate attack detected";
                }
                logger.debug("Setting up the camera to use Basic Auth and resending last request with correct auth.");
                myHandler.setBasicAuth();
                myHandler.sendHttpRequest(httpMethod, requestURI, false);
                return "Using Basic";
            }

            ///////////// Digest Authenticate method follows as Basic is already handled and returned ////////////////
            myHandler.realm = searchString(authenticate, "Digest realm=\"");
            if (myHandler.realm == null) {
                logger.debug("Could not find a valid WWW-Authenticate reponse in :{}", authenticate);
                return "Error";
            }
            myHandler.nonce = searchString(authenticate, "nonce=\"");
            myHandler.opaque = searchString(authenticate, "opaque=\"");
            myHandler.qop = searchString(authenticate, "qop=\"");
        }

        if (!myHandler.qop.isEmpty() && !myHandler.realm.isEmpty()) {
            myHandler.useDigestAuth = true;
        } else {
            logger.warn("Something is missing? opaque:{}, qop:{}, realm:{}", myHandler.opaque, myHandler.qop,
                    myHandler.realm);
        }

        String stale = searchString(authenticate, "stale=\"");
        if ("false".equals(stale)) {
            logger.debug("Camera reported stale=false which normally means an issue with the username or password.");
        } else if ("true".equals(stale)) {
            logger.debug("Camera reported stale=true which normally means the NONCE has expired.");
        }

        // create the MD5 hashes
        String ha1 = username + ":" + myHandler.realm + ":" + password;
        ha1 = calcMD5Hash(ha1);
        Random random = new Random();
        String cnonce = Integer.toHexString(random.nextInt());
        myHandler.ncCounter = (myHandler.ncCounter > 999999999) ? 1 : ++myHandler.ncCounter;
        String nc = String.format("%08X", myHandler.ncCounter); // 8 digit hex number
        // int nc = myHandler.ncCounter;
        // int nc = 1;
        String ha2 = httpMethod + ":" + requestURI;
        ha2 = calcMD5Hash(ha2);

        String response = ha1 + ":" + myHandler.nonce + ":" + nc + ":" + cnonce + ":" + myHandler.qop + ":" + ha2;
        response = calcMD5Hash(response);

        String digestString = "username=\"" + username + "\", realm=\"" + myHandler.realm + "\", nonce=\""
                + myHandler.nonce + "\", uri=\"" + requestURI + "\", qop=\"" + myHandler.qop + "\", nc=\"" + nc
                + "\", cnonce=\"" + cnonce + "\", response=\"" + response + "\", opaque=\"" + myHandler.opaque + "\"";

        if (reSend) {
            myHandler.digestString = digestString;
            myHandler.sendHttpRequest(httpMethod, requestURI, true);
            // myHandler.sendHttpRequest(requestURI, digestString); // for testing second channel//
        }
        return digestString;
    }

    // This method handles the Servers response
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {

        if (msg instanceof HttpResponse) {
            HttpResponse response = (HttpResponse) msg;
            if (response.status().code() == 401) {
                logger.debug("401: This reply from the camera is normal if it needs Basic or Digest auth details.");
                // Find WWW-Authenticate then process it //
                if (!response.headers().isEmpty()) {
                    for (CharSequence name : response.headers().names()) {
                        for (CharSequence value : response.headers().getAll(name)) {
                            if (name.toString().equals("WWW-Authenticate")) {
                                // logger.debug("Camera gave this string:{}", value.toString());
                                processAuth(value.toString(), httpMethod, httpURL, true);
                                ctx.close();
                            }
                        }
                    }
                }
            }
        }
        // Pass the Netty Message back to the pipeline for the next handler to process//
        super.channelRead(ctx, msg);
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        this.httpURL = myHandler.correctedRequestURL;
        this.httpMethod = myHandler.httpMethod;
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.debug("!!! Camera may have closed the connection which can be normal. Cause reported is:{}", cause);
        ctx.close();
    }
}
