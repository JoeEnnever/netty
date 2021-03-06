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
package io.netty.channel.local;

import io.netty.channel.AbstractChannel;
import io.netty.channel.Channel;
import io.netty.channel.ChannelBufferHolder;
import io.netty.channel.ChannelBufferHolders;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelException;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelType;
import io.netty.channel.DefaultChannelConfig;
import io.netty.channel.EventLoop;
import io.netty.channel.SingleThreadEventLoop;

import java.net.SocketAddress;
import java.nio.channels.AlreadyConnectedException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ConnectionPendingException;
import java.nio.channels.NotYetConnectedException;
import java.util.Queue;

/**
 * A {@link Channel} for the local transport.
 */
public class LocalChannel extends AbstractChannel {

    private final ChannelConfig config = new DefaultChannelConfig();
    private final Runnable shutdownHook = new Runnable() {
        @Override
        public void run() {
            unsafe().close(unsafe().voidFuture());
        }
    };

    private volatile int state; // 0 - open, 1 - bound, 2 - connected, 3 - closed
    private volatile LocalChannel peer;
    private volatile LocalAddress localAddress;
    private volatile LocalAddress remoteAddress;
    private volatile ChannelFuture connectFuture;

    public LocalChannel() {
        this(null);
    }

    public LocalChannel(Integer id) {
        super(null, id, ChannelBufferHolders.messageBuffer());
    }

    LocalChannel(LocalServerChannel parent, LocalChannel peer) {
        super(parent, null, ChannelBufferHolders.messageBuffer());
        this.peer = peer;
        localAddress = parent.localAddress();
        remoteAddress = peer.localAddress();
    }

    @Override
    public ChannelType type() {
        return ChannelType.MESSAGE;
    }

    @Override
    public ChannelConfig config() {
        return config;
    }

    @Override
    public LocalServerChannel parent() {
        return (LocalServerChannel) super.parent();
    }

    @Override
    public LocalAddress localAddress() {
        return (LocalAddress) super.localAddress();
    }

    @Override
    public LocalAddress remoteAddress() {
        return (LocalAddress) super.remoteAddress();
    }

    @Override
    public boolean isOpen() {
        return state < 3;
    }

    @Override
    public boolean isActive() {
        return state == 2;
    }

    @Override
    protected Unsafe newUnsafe() {
        return new LocalUnsafe();
    }

    @Override
    protected boolean isCompatible(EventLoop loop) {
        return loop instanceof SingleThreadEventLoop;
    }

    @Override
    protected SocketAddress localAddress0() {
        return localAddress;
    }

    @Override
    protected SocketAddress remoteAddress0() {
        return remoteAddress;
    }

    @Override
    protected Runnable doRegister() throws Exception {
        final LocalChannel peer = this.peer;
        Runnable postRegisterTask;

        if (peer != null) {
            state = 2;

            peer.remoteAddress = parent().localAddress();
            peer.state = 2;

            // Ensure the peer's channelActive event is triggered *after* this channel's
            // channelRegistered event is triggered, so that this channel's pipeline is fully
            // initialized by ChannelInitializer.
            final EventLoop peerEventLoop = peer.eventLoop();
            postRegisterTask = new Runnable() {
                @Override
                public void run() {
                    peerEventLoop.execute(new Runnable() {
                        @Override
                        public void run() {
                            peer.connectFuture.setSuccess();
                            peer.pipeline().fireChannelActive();
                        }
                    });
                }
            };
        } else {
            postRegisterTask = null;
        }

        ((SingleThreadEventLoop) eventLoop()).addShutdownHook(shutdownHook);

        return postRegisterTask;
    }

    @Override
    protected void doBind(SocketAddress localAddress) throws Exception {
        this.localAddress =
                LocalChannelRegistry.register(this, this.localAddress,
                        localAddress);
        state = 1;
    }

    @Override
    protected void doDisconnect() throws Exception {
        doClose();
    }

    @Override
    protected void doClose() throws Exception {
        if (state > 2) {
            // Closed already
            return;
        }

        if (parent() == null) {
            LocalChannelRegistry.unregister(localAddress);
        }
        localAddress = null;
        state = 3;
        if (peer.isActive()) {
            peer.unsafe().close(peer.unsafe().voidFuture());
            peer = null;
        }
    }

    @Override
    protected void doDeregister() throws Exception {
        if (isOpen()) {
            unsafe().close(unsafe().voidFuture());
        }
        ((SingleThreadEventLoop) eventLoop()).removeShutdownHook(shutdownHook);
    }

    @Override
    protected void doFlush(ChannelBufferHolder<Object> buf) throws Exception {
        if (state < 2) {
            throw new NotYetConnectedException();
        }
        if (state > 2) {
            throw new ClosedChannelException();
        }

        final LocalChannel peer = this.peer;
        assert peer != null;

        Queue<Object> in = buf.messageBuffer();
        Queue<Object> out = peer.pipeline().inboundMessageBuffer();
        for (;;) {
            Object msg = in.poll();
            if (msg == null) {
                break;
            }
            out.add(msg);
        }

        peer.eventLoop().execute(new Runnable() {
            @Override
            public void run() {
                peer.pipeline().fireInboundBufferUpdated();
            }
        });
    }

    @Override
    protected boolean isFlushPending() {
        return false;
    }

    private class LocalUnsafe extends AbstractUnsafe {

        @Override
        public void connect(final SocketAddress remoteAddress,
                SocketAddress localAddress, final ChannelFuture future) {
            if (eventLoop().inEventLoop()) {
                if (!ensureOpen(future)) {
                    return;
                }

                if (state == 2) {
                    Exception cause = new AlreadyConnectedException();
                    future.setFailure(cause);
                    pipeline().fireExceptionCaught(cause);
                    return;
                }

                if (connectFuture != null) {
                    throw new ConnectionPendingException();
                }

                connectFuture = future;

                if (state != 1) {
                    // Not bound yet and no localAddress specified - get one.
                    if (localAddress == null) {
                        localAddress = new LocalAddress(LocalChannel.this);
                    }
                }

                if (localAddress != null) {
                    try {
                        doBind(localAddress);
                    } catch (Throwable t) {
                        future.setFailure(t);
                        pipeline().fireExceptionCaught(t);
                        close(voidFuture());
                        return;
                    }
                }

                Channel boundChannel = LocalChannelRegistry.get(remoteAddress);
                if (!(boundChannel instanceof LocalServerChannel)) {
                    Exception cause =
                            new ChannelException("connection refused");
                    future.setFailure(cause);
                    pipeline().fireExceptionCaught(cause);
                    close(voidFuture());
                    return;
                }

                LocalServerChannel serverChannel = (LocalServerChannel) boundChannel;
                peer = serverChannel.serve(LocalChannel.this);
            } else {
                final SocketAddress localAddress0 = localAddress;
                eventLoop().execute(new Runnable() {
                    @Override
                    public void run() {
                        connect(remoteAddress, localAddress0, future);
                    }
                });
            }
        }
    }
}
