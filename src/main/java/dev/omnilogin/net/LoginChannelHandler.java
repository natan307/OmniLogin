package dev.omnilogin.net;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.local.LocalAddress;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.HexFormat;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/**
 * Netty glue for {@link LoginInterceptor}. It is the first handler of every new connection, so it sees the raw bytes
 * of the client before the server does, and it encrypts what the server sends back when the connection was
 * verified.
 */
public final class LoginChannelHandler extends ChannelDuplexHandler {

    private final LoginInterceptor.Services services;
    private final Executor blocking;
    private static final int LOGGED_PACKETS = 8;
    private static final int HEX_BYTES = 12;
    private static final int FORCED_FLUSHES = 8;

    private LoginInterceptor interceptor;
    private int loggedIn;
    private int loggedOut;
    private int completed;
    private int forcedFlushes;

    public LoginChannelHandler(LoginInterceptor.Services services, Executor blocking) {
        this.services = services;
        this.blocking = blocking;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        interceptor = new LoginInterceptor(services, new LoginInterceptor.Sink() {
            @Override
            public void toClient(byte[] data) {
                ctx.writeAndFlush(Unpooled.wrappedBuffer(data));
            }

            @Override
            public void toServer(byte[] data) {
                ctx.fireChannelRead(Unpooled.wrappedBuffer(data));
                // We often forward later (after a lookup), outside the read that delivered the data. Handlers further
                // down the pipeline expect "read complete" after reads and may hold back flushes until they get it.
                ctx.fireChannelReadComplete();
            }

            @Override
            public void close() {
                ctx.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
            }

            @Override
            public void async(Runnable task) {
                blocking.execute(task);
            }

            @Override
            public void sync(Runnable task) {
                ctx.executor().execute(task);
            }

            @Override
            public String remoteIp() {
                SocketAddress remote = ctx.channel().remoteAddress();
                if (remote instanceof InetSocketAddress) {
                    String ip = ((InetSocketAddress) remote).getAddress().getHostAddress();
                    int zone = ip.indexOf('%');
                    return zone < 0 ? ip : ip.substring(0, zone);
                }

                return "127.0.0.1";
            }

            @Override
            public void schedule(Runnable task, long delayMillis) {
                ctx.executor().schedule(task, delayMillis, TimeUnit.MILLISECONDS);
            }

            @Override
            public boolean isLocal() {
                return ctx.channel().remoteAddress() instanceof LocalAddress;
            }
        });
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof ByteBuf && interceptor.isForwarding() && loggedIn < LOGGED_PACKETS
                && services.debugEnabled()) {
            loggedIn++;
            services.debug("[" + ip(ctx) + "] client -> server: " + ((ByteBuf) msg).readableBytes() + " bytes"
                    + (interceptor.isEncrypting() ? " (encrypted)" : " (plain)")
                    + ", autoRead=" + ctx.channel().config().isAutoRead());
        }

        if (interceptor.isPlainPassthrough() || !(msg instanceof ByteBuf)) {
            ctx.fireChannelRead(msg);
            return;
        }

        ByteBuf buf = (ByteBuf) msg;
        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        buf.release();
        interceptor.onInbound(data);
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) throws Exception {
        if (interceptor.isForwarding() && completed < LOGGED_PACKETS && services.debugEnabled()) {
            completed++;
            services.debug("[" + ip(ctx) + "] read complete, autoRead=" + ctx.channel().config().isAutoRead());
        }

        super.channelReadComplete(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        services.debug("[" + ip(ctx) + "] connection closed (" + interceptor.describe() + ")");
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        services.debug("[" + ip(ctx) + "] network error: " + cause);
        super.exceptionCaught(ctx, cause);
    }

    private static String ip(ChannelHandlerContext ctx) {
        SocketAddress remote = ctx.channel().remoteAddress();
        if (remote instanceof InetSocketAddress) {
            return ((InetSocketAddress) remote).getAddress().getHostAddress();
        }

        return String.valueOf(remote);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (!(msg instanceof ByteBuf)) {
            ctx.write(msg, promise);
            return;
        }

        ByteBuf buf = (ByteBuf) msg;
        boolean encrypting = interceptor.isEncrypting();
        if (loggedOut < LOGGED_PACKETS && services.debugEnabled()) {
            loggedOut++;
            byte[] head = new byte[Math.min(HEX_BYTES, buf.readableBytes())];
            buf.getBytes(buf.readerIndex(), head);
            services.debug("[" + ip(ctx) + "] server -> client: " + buf.readableBytes() + " bytes, starts with "
                    + HexFormat.of().formatHex(head) + (encrypting ? " (encrypting)" : " (plain)"));
        }

        if (loggedOut <= LOGGED_PACKETS && services.debugEnabled()) {
            int number = loggedOut;
            promise.addListener((ChannelFutureListener) done -> services.debug("[" + ip(ctx) + "] write #" + number
                    + (done.isSuccess() ? " reached the socket" : " FAILED: " + done.cause())));
        }

        if (!encrypting) {
            ctx.write(msg, promise);
            return;
        }

        byte[] data = new byte[buf.readableBytes()];
        buf.readBytes(data);
        buf.release();
        ctx.write(Unpooled.wrappedBuffer(interceptor.onOutbound(data)), promise);
        if (forcedFlushes < FORCED_FLUSHES) {
            forcedFlushes++;                 // the login packets must not sit in a buffer
            ctx.flush();
        }
    }
}
