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
    private String username;
    private String password;

    public MyNettyAuthHandler(String user, String pass, ThingHandler handle) {
        myHandler = (IpCameraHandler) handle;
        username = user;
        password = pass;
        return;
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

    // Method can be used a few ways. processAuth(null, string, false) to return the digest on demand, and
    // processAuth(challString, string, true) to auto send new packet
    // First run it should not have rawstring as null
    // nonce is reused if rawstring is null
    public String processAuth(String rawString, String requestURI, boolean reSend) {
        String digestString;

        if (rawString != null) {

            myHandler.realm = searchString(rawString, "Basic realm=\"");
            if (myHandler.realm != null) {
                if (myHandler.useDigestAuth == true) {
                    logger.error(
                            "Camera appears to be requesting Basic after Digest Auth has already been used, this could be a hacker so not going to reply.");
                    return "Error";
                }
                myHandler.useBasicAuth = true;
                logger.debug("Using Basic Auth for this request");
                return "Using Basic";
            }

            myHandler.realm = searchString(rawString, "Digest realm=\"");
            if (myHandler.realm == null) {
                logger.debug("Could not find a valid WWW-Authenticate reponse in :{}", rawString);
                return "Error";
            }
            myHandler.nonce = searchString(rawString, "nonce=\"");
            myHandler.opaque = searchString(rawString, "opaque=\"");
            myHandler.qop = searchString(rawString, "qop=\"");
        }

        if (myHandler.opaque != null && myHandler.qop != null && myHandler.realm != null) {
            myHandler.useDigestAuth = true;
        }

        // create the MD5 hashes
        String ha1 = username + ":" + myHandler.realm + ":" + password;
        ha1 = calcMD5Hash(ha1);

        Random random = new Random();
        String cnonce = Integer.toHexString(random.nextInt());
        myHandler.ncCounter = (myHandler.ncCounter > 99999) ? 1 : ++myHandler.ncCounter;
        String ha2 = "GET:" + requestURI;
        ha2 = calcMD5Hash(ha2);
        String request = ha1 + ":" + myHandler.nonce + ":" + myHandler.ncCounter + ":" + cnonce + ":" + myHandler.qop
                + ":" + ha2;
        request = calcMD5Hash(request);

        digestString = "username=\"" + username + "\", realm=\"" + myHandler.realm + "\", nonce=\"" + myHandler.nonce
                + "\", uri=\"" + requestURI + "\", qop=" + myHandler.qop + ", nc=" + myHandler.ncCounter + ", cnonce=\""
                + cnonce + "\", response=\"" + request + "\", opaque=\"" + myHandler.opaque + "\"";

        if (reSend) {
            myHandler.digestString = digestString;

            if (!requestURI.contains(myHandler.fullRequestPath.toString())) {
                logger.debug(
                        "!!!!!!!!!!! we failed to get a match another request must have beaten us !!!!!!!!!!!!!!!!!");
            }
            myHandler.sendHttpRequest(requestURI); // first channel//
            // myHandler.sendHttpRequest(requestURI, digestString); // second channel//
        }
        return digestString;
    }

    // This method handles the Servers response
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {

        if (msg instanceof HttpResponse) {
            HttpResponse response = (HttpResponse) msg;
            if (response.status().code() == 401) {
                logger.debug("!!! We got a 401 which is normal if camera needs auth details !!!");
                // Find WWW-Authenticate then process it //
                if (!response.headers().isEmpty()) {
                    for (CharSequence name : response.headers().names()) {
                        for (CharSequence value : response.headers().getAll(name)) {
                            if (name.toString().equals("WWW-Authenticate")) {

                                // NONCE is very fresh so will be good, requestpath may be already replaced//
                                processAuth(value.toString(), myHandler.fullRequestPath, true);
                            }
                        }
                    }
                }
            }
        }
        // Pass the Netty Message back to the pipeline for the next handler to process//
        super.channelRead(ctx, msg);
    }
}
