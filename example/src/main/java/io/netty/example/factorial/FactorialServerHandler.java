/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.example.factorial;

import io.netty.channel.ChannelInboundHandlerContext;
import io.netty.channel.ChannelInboundMessageHandlerAdapter;

import java.math.BigInteger;
import java.util.Formatter;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Handler for a server-side channel.  This handler maintains stateful
 * information which is specific to a certain channel using member variables.
 * Therefore, an instance of this handler can cover only one channel.  You have
 * to create a new handler instance whenever you create a new channel and insert
 * this handler  to avoid a race condition.
 */
public class FactorialServerHandler extends ChannelInboundMessageHandlerAdapter<BigInteger> {

    private static final Logger logger = Logger.getLogger(
            FactorialServerHandler.class.getName());

    private BigInteger lastMultiplier = new BigInteger("1");
    private BigInteger factorial = new BigInteger("1");

    @Override
    public void messageReceived(
            ChannelInboundHandlerContext<BigInteger> ctx, BigInteger msg) throws Exception {
        // Calculate the cumulative factorial and send it to the client.
        lastMultiplier = msg;
        factorial = factorial.multiply(msg);
        ctx.write(factorial);
    }

    @Override
    public void channelInactive(
            ChannelInboundHandlerContext<BigInteger> ctx) throws Exception {
        logger.info(new Formatter().format(
                "Factorial of %,d is: %,d", lastMultiplier, factorial).toString());
    }

    @Override
    public void exceptionCaught(
            ChannelInboundHandlerContext<BigInteger> ctx, Throwable cause) throws Exception {
        logger.log(
                Level.WARNING,
                "Unexpected exception from downstream.", cause);
        ctx.close();
    }
}
