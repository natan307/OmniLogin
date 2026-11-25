package dev.omnilogin.net;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Puts the {@link LoginChannelHandler} at the head of every new client connection of the server, without ProtocolLib
 * and without touching version specific server classes.
 *
 * <ol>
 *   <li>Paper, Folia, Purpur and other forks: the official ChannelInitializeListener API (found by reflection).</li>
 *   <li>Spigot / Bukkit: the server's listening channels are found by type (a List of ChannelFuture) and a small
 *       acceptor is added in front of them.</li>
 * </ol>
 */
public final class NettyInjector {

    private static final String HANDLER_NAME = "omnilogin_login";
    private static final String PAPER_KEY_NAMESPACE = "omnilogin";
    private static final String PAPER_KEY_VALUE = "login";

    private final Logger log;
    private final Supplier<ChannelHandler> factory;
    private final List<Channel> acceptors = new ArrayList<>();
    private Object paperKey;
    private Class<?> paperHolder;

    public NettyInjector(Logger log, Supplier<ChannelHandler> factory) {
        this.log = log;
        this.factory = factory;
    }

    /** Returns true when new connections will pass through OmniLogin. */
    public boolean inject() {
        try {
            if (injectPaper()) {
                log.info("Login handler installed (Paper channel API).");
                return true;
            }
        } catch (ReflectiveOperationException | RuntimeException ex) {
            log.warning("Paper channel API not usable (" + ex + "), trying the generic method.");
        }

        try {
            int count = injectGeneric();
            if (count > 0) {
                log.info("Login handler installed on " + count + " server channel(s).");
                return true;
            }
        } catch (ReflectiveOperationException | RuntimeException ex) {
            log.warning("Generic injection failed: " + ex);
        }

        return false;
    }

    public void uninject() {
        try {
            if (paperHolder != null && paperKey != null) {
                Class<?> keyType = Class.forName("net.kyori.adventure.key.Key");
                paperHolder.getMethod("removeListener", keyType).invoke(null, paperKey);
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // the server is shutting down anyway
        }

        for (Channel channel : acceptors) {
            try {
                channel.pipeline().remove(HANDLER_NAME + "_acceptor");
            } catch (RuntimeException ignored) {
                // already gone
            }
        }

        acceptors.clear();
    }

    // ------------------------------------------------------------------------------------------------- Paper

    private boolean injectPaper() throws ReflectiveOperationException {
        Class<?> holder = Class.forName("io.papermc.paper.network.ChannelInitializeListenerHolder");
        Class<?> listenerType = Class.forName("io.papermc.paper.network.ChannelInitializeListener");
        Class<?> keyType = Class.forName("net.kyori.adventure.key.Key");
        Object key = keyType.getMethod("key", String.class, String.class)
                .invoke(null, PAPER_KEY_NAMESPACE, PAPER_KEY_VALUE);

        Object listener = Proxy.newProxyInstance(listenerType.getClassLoader(), new Class<?>[] {listenerType},
                (proxy, method, args) -> {
                    if ("afterInitChannel".equals(method.getName()) && args != null && args.length == 1) {
                        ((Channel) args[0]).pipeline().addFirst(HANDLER_NAME, factory.get());
                    } else if ("toString".equals(method.getName())) {
                        return "OmniLogin channel listener";
                    } else if ("hashCode".equals(method.getName())) {
                        return System.identityHashCode(proxy);
                    } else if ("equals".equals(method.getName())) {
                        return proxy == args[0];
                    }

                    return null;
                });

        holder.getMethod("addListener", keyType, listenerType).invoke(null, key, listener);
        this.paperHolder = holder;
        this.paperKey = key;
        return true;
    }

    // ----------------------------------------------------------------------------------------------- generic

    private int injectGeneric() throws ReflectiveOperationException {
        Object server = serverObject();
        int count = 0;
        for (ChannelFuture future : findChannelFutures(server)) {
            Channel serverChannel = future.channel();
            serverChannel.pipeline().addFirst(HANDLER_NAME + "_acceptor", new ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                    if (msg instanceof Channel) {
                        ((Channel) msg).pipeline().addLast(new ChannelInitializer<Channel>() {
                            @Override
                            protected void initChannel(Channel child) {
                                child.pipeline().addFirst(HANDLER_NAME, factory.get());
                            }
                        });
                    }

                    ctx.fireChannelRead(msg);
                }
            });
            acceptors.add(serverChannel);
            count++;
        }

        return count;
    }

    private static Object serverObject() throws ReflectiveOperationException {
        Object craftServer = Class.forName("org.bukkit.Bukkit").getMethod("getServer").invoke(null);
        Method getServer = craftServer.getClass().getMethod("getServer");
        return getServer.invoke(craftServer);
    }

    /** Finds the server's listening channels by type, so no obfuscated field names are needed. */
    private static List<ChannelFuture> findChannelFutures(Object server) throws ReflectiveOperationException {
        for (Class<?> type = server.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
                    continue;
                }

                Object value = read(field, server);
                if (value == null || isJdkClass(value.getClass())) {
                    continue;
                }

                for (Class<?> inner = value.getClass(); inner != null && inner != Object.class;
                        inner = inner.getSuperclass()) {
                    for (Field innerField : inner.getDeclaredFields()) {
                        if (Modifier.isStatic(innerField.getModifiers())
                                || !List.class.isAssignableFrom(innerField.getType())) {
                            continue;
                        }

                        List<ChannelFuture> found = futuresIn(read(innerField, value));
                        if (!found.isEmpty()) {
                            return found;
                        }
                    }
                }
            }
        }

        throw new ClassNotFoundException("no listening channels found on " + server.getClass().getName());
    }

    private static Object read(Field field, Object owner) {
        try {
            field.setAccessible(true);
            return field.get(owner);
        } catch (ReflectiveOperationException | RuntimeException inaccessible) {
            return null;
        }
    }

    private static boolean isJdkClass(Class<?> type) {
        String name = type.getName();
        return type.isArray() || type.isEnum() || name.startsWith("java.") || name.startsWith("javax.")
                || name.startsWith("jdk.") || name.startsWith("sun.");
    }

    private static List<ChannelFuture> futuresIn(Object value) {
        List<ChannelFuture> result = new ArrayList<>();
        if (value instanceof List) {
            for (Object element : (List<?>) value) {
                if (element instanceof ChannelFuture) {
                    result.add((ChannelFuture) element);
                }
            }
        }

        return result;
    }
}
