package dev.omnilogin.net;

import dev.omnilogin.identity.Status;
import dev.omnilogin.identity.Verification;
import dev.omnilogin.net.PacketIo.Reader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * The login state machine. It sits between the client and the real server and decides, per connection and before
 * anything is encrypted, how the player gets in:
 * <ul>
 *   <li>name registered on Microsoft / Ely.by: runs the encryption handshake itself and verifies the session,</li>
 *   <li>free name: lets the player in as cracked,</li>
 *   <li>anything else: kicks the player with a custom message.</li>
 * </ul>
 * The real server only ever sees a BungeeCord-style (legacy forwarding) handshake written by this class, with the
 * UUID, skin and IP that were decided here. Anything the client wrote in that field itself is thrown away.
 *
 * <p>This class is transport independent and single threaded: every method is called on the connection's event
 * loop. Blocking work is handed to {@link Sink#async} and its result comes back through {@link Sink#sync}.
 */
public final class LoginInterceptor {

    /** Connection side effects. */
    public interface Sink {
        /** Bytes for the client, exactly as they go on the wire (already encrypted when needed). */
        void toClient(byte[] data);

        /** Bytes for the real server's own pipeline. */
        void toServer(byte[] data);

        /** Flush pending writes, then close the connection. */
        void close();

        /** Run blocking work off the event loop. */
        void async(Runnable task);

        /** Run on the connection's event loop. */
        void sync(Runnable task);

        String remoteIp();

        /** Runs a task on the event loop after a delay. */
        void schedule(Runnable task, long delayMillis);

        /** True for in-process connections (Geyser's direct connection), which come from the server itself. */
        boolean isLocal();
    }

    /** What to do with a name. */
    public enum Decision {
        FREE, REGISTERED, DENY_CRACKED_DISABLED, DENY_LOOKUP_FAILED
    }

    /** Services the interceptor needs (blocking methods are called off the event loop). */
    public interface Services {
        Decision decide(String name);

        /** Checks the client's session with Microsoft, then Ely.by. Returns null when nobody confirms it. */
        Verification verify(String name, String serverHash);

        /** Tells the rest of the plugin who was let in, before the real server sees the connection. */
        void register(UUID id, String name, Status status);

        boolean usePremiumUuid();

        /** True when the real server accepts a forwarded identity (spigot.yml settings.bungeecord). */
        boolean forwardIdentity();

        KeyPair keyPair();

        String denyMessage(Decision decision);

        String reservedMessage();

        String invalidNameMessage();

        String oldClientMessage();

        long stepTimeoutMillis();

        void debug(String message);

        boolean debugEnabled();

        void error(String message, Throwable failure);
    }

    private enum State { HANDSHAKE, LOGIN_START, DECIDING, ENCRYPTION, VERIFYING, PASSTHROUGH, CLOSED }

    private static final Pattern NAME_OK = Pattern.compile("^[A-Za-z0-9_.\\-]{1,16}$");
    private static final int MAX_BUFFER = 8192;
    private static final int MIN_ENCRYPTION_PROTOCOL = 761;
    private static final int SHOULD_AUTHENTICATE_PROTOCOL = 766;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Services services;
    private final Sink sink;
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private final ByteArrayOutputStream held = new ByteArrayOutputStream();

    private State state = State.HANDSHAKE;
    private int protocol;
    private String address = "";
    private int port;
    private int intent;
    private String name = "";
    private byte[] loginRest = new byte[0];
    private byte[] token = new byte[0];
    private byte[] rawHandshake = new byte[0];
    private Cipher cipherIn;
    private Cipher cipherOut;

    public LoginInterceptor(Services services, Sink sink) {
        this.services = services;
        this.sink = sink;
    }

    /** True once the login was handed to the real server. */
    public boolean isForwarding() {
        return state == State.PASSTHROUGH;
    }

    /** For debug logs. */
    public String describe() {
        return state + (cipherOut != null ? ", encrypted" : ", not encrypted");
    }

    /** True once the connection is a plain pass-through, so the transport can skip copying. */
    public boolean isPlainPassthrough() {
        return state == State.PASSTHROUGH && cipherIn == null;
    }

    public boolean isEncrypting() {
        return cipherOut != null;
    }

    /** Bytes the real server wrote, on their way to the client. */
    public byte[] onOutbound(byte[] data) {
        return crypt(cipherOut, data);
    }

    /** Bytes the client wrote. */
    public void onInbound(byte[] data) {
        if (state == State.CLOSED || data.length == 0) {
            return;
        }

        try {
            if (state == State.PASSTHROUGH) {
                sink.toServer(crypt(cipherIn, data));
            } else if (state == State.DECIDING) {
                held.writeBytes(data);
            } else if (state == State.VERIFYING) {
                held.writeBytes(crypt(cipherIn, data));
            } else {
                buffer.writeBytes(data);
                process();
            }
        } catch (IOException | GeneralSecurityException malformed) {
            services.debug("[" + sink.remoteIp() + "] malformed login data: " + malformed);
            abort();
        } catch (RuntimeException failure) {
            services.error("Unexpected error while handling a login from " + sink.remoteIp(), failure);
            abort();
        }
    }

    // ---------------------------------------------------------------------------------------------- framing

    private void process() throws IOException, GeneralSecurityException {
        while (state == State.HANDSHAKE || state == State.LOGIN_START || state == State.ENCRYPTION) {
            byte[] all = buffer.toByteArray();
            if (state == State.HANDSHAKE && all.length > 0 && (all[0] & 0xFF) == 0xFE) {
                enterPassthrough(new byte[0]);            // legacy (pre-1.7) ping
                return;
            }

            int[] header = PacketIo.readVarInt(all, 0);
            if (header == null) {
                limit(all.length);
                return;
            }

            int length = header[0];
            int total = header[1] + length;
            if (length <= 0 || length > MAX_BUFFER) {
                abort();
                return;
            }

            if (all.length < total) {
                limit(all.length);
                return;
            }

            byte[] payload = Arrays.copyOfRange(all, header[1], total);
            byte[] raw = Arrays.copyOfRange(all, 0, total);
            buffer.reset();
            buffer.writeBytes(Arrays.copyOfRange(all, total, all.length));

            switch (state) {
                case HANDSHAKE -> onHandshake(payload, raw);
                case LOGIN_START -> onLoginStart(payload);
                default -> onEncryptionResponse(payload);
            }
        }
    }

    private void limit(int size) {
        if (size > MAX_BUFFER) {
            abort();
        }
    }

    // -------------------------------------------------------------------------------------------- the steps

    private void onHandshake(byte[] payload, byte[] raw) throws IOException {
        Reader r = new Reader(payload);
        if (r.varInt() != 0) {
            abort();
            return;
        }

        protocol = r.varInt();
        String host = r.string();
        port = r.unsignedShort();
        intent = r.varInt();

        services.debug("[" + sink.remoteIp() + "] handshake: protocol " + protocol + ", intent " + intent);
        if (intent == 1) {                                 // status ping: nothing to protect
            enterPassthrough(raw);
            return;
        }

        if (intent != 2 && intent != 3) {
            abort();
            return;
        }

        rawHandshake = raw;
        int zero = host.indexOf('\0');                     // drop anything the client smuggled into the host
        address = zero < 0 ? host : host.substring(0, zero);
        state = State.LOGIN_START;
    }

    private void onLoginStart(byte[] payload) throws IOException {
        Reader r = new Reader(payload);
        if (r.varInt() != 0) {
            abort();
            return;
        }

        name = r.string();
        loginRest = r.remaining();
        if (sink.isLocal()) {
            // an in-process connection (Geyser): it cannot come from the internet, so there is nothing to protect
            held.writeBytes(buffer.toByteArray());
            buffer.reset();
            forward(offlineUuid(name), "[]", name, Status.CRACKED);
            return;
        }

        if (!NAME_OK.matcher(name).matches()) {
            disconnect(services.invalidNameMessage());
            return;
        }

        held.writeBytes(buffer.toByteArray());
        buffer.reset();
        state = State.DECIDING;
        services.debug("[" + sink.remoteIp() + "] login start: " + name + " (checking the name)");
        watchdog(State.DECIDING);
        sink.async(() -> {
            Decision decision;
            try {
                decision = services.decide(name);
            } catch (Throwable failure) {
                services.error("Name check failed for " + name, failure);
                decision = Decision.DENY_LOOKUP_FAILED;
            }

            Decision result = decision;
            sink.sync(() -> guarded(() -> onDecision(result)));
        });
    }

    /** Kicks with a clear message when a step takes too long, instead of leaving the client to time out. */
    private void watchdog(State waiting) {
        sink.schedule(() -> {
            if (state == waiting) {
                services.debug("[" + sink.remoteIp() + "] " + name + ": timed out in " + waiting);
                disconnect(services.denyMessage(Decision.DENY_LOOKUP_FAILED));
            }
        }, services.stepTimeoutMillis());
    }

    private void guarded(Runnable step) {
        try {
            step.run();
        } catch (Throwable failure) {
            services.error("Login step failed for " + name, failure);
            abort();
        }
    }

    private void onDecision(Decision decision) {
        if (state != State.DECIDING) {
            return;
        }

        services.debug("[" + sink.remoteIp() + "] " + name + ": decision " + decision);
        switch (decision) {
            case FREE -> forward(offlineUuid(name), "[]", name, Status.CRACKED);
            case REGISTERED -> {
                if (protocol < MIN_ENCRYPTION_PROTOCOL) {
                    disconnect(services.oldClientMessage());
                } else {
                    beginEncryption();
                }
            }
            default -> disconnect(services.denyMessage(decision));
        }
    }

    private void beginEncryption() {
        token = new byte[4];
        RANDOM.nextBytes(token);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PacketIo.writeVarInt(out, 1);
        PacketIo.writeString(out, "");
        PacketIo.writeBytes(out, services.keyPair().getPublic().getEncoded());
        PacketIo.writeBytes(out, token);
        if (protocol >= SHOULD_AUTHENTICATE_PROTOCOL) {
            out.write(1);
        }

        state = State.ENCRYPTION;
        send(PacketIo.frame(out.toByteArray()));
        services.debug("[" + sink.remoteIp() + "] " + name + ": encryption request sent (protocol " + protocol + ")");
        watchdog(State.ENCRYPTION);
    }

    private void onEncryptionResponse(byte[] payload) throws IOException, GeneralSecurityException {
        Reader r = new Reader(payload);
        if (r.varInt() != 1) {
            abort();
            return;
        }

        byte[] encryptedSecret = r.byteArray();
        byte[] encryptedToken = r.byteArray();

        Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        rsa.init(Cipher.DECRYPT_MODE, services.keyPair().getPrivate());
        if (!Arrays.equals(token, rsa.doFinal(encryptedToken))) {
            abort();
            return;
        }

        byte[] secret = rsa.doFinal(encryptedSecret);
        if (secret.length != 16) {
            abort();
            return;
        }

        cipherIn = Cipher.getInstance("AES/CFB8/NoPadding");
        cipherIn.init(Cipher.DECRYPT_MODE, new SecretKeySpec(secret, "AES"), new IvParameterSpec(secret));
        cipherOut = Cipher.getInstance("AES/CFB8/NoPadding");
        cipherOut.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(secret, "AES"), new IvParameterSpec(secret));

        held.writeBytes(crypt(cipherIn, buffer.toByteArray()));   // anything after the response is encrypted
        buffer.reset();
        state = State.VERIFYING;

        String hash = serverHash(secret, services.keyPair().getPublic().getEncoded());
        services.debug("[" + sink.remoteIp() + "] " + name + ": encryption response ok, verifying the session");
        watchdog(State.VERIFYING);
        sink.async(() -> {
            Verification verified;
            try {
                verified = services.verify(name, hash);
            } catch (Throwable failure) {
                services.error("Session check failed for " + name, failure);
                verified = null;
            }

            Verification result = verified;
            sink.sync(() -> guarded(() -> onVerified(result)));
        });
    }

    private void onVerified(Verification verified) {
        if (state != State.VERIFYING) {
            return;
        }

        services.debug("[" + sink.remoteIp() + "] " + name + ": session "
                + (verified == null ? "NOT confirmed by Microsoft or Ely.by" : "confirmed by " + verified.status()));
        if (verified == null || !verified.name().equalsIgnoreCase(name)) {
            disconnect(services.reservedMessage());
            return;
        }

        boolean realUuid = services.forwardIdentity() && services.usePremiumUuid();
        UUID id = realUuid ? verified.id() : offlineUuid(verified.name());
        forward(id, verified.properties(), verified.name(), verified.status());
    }

    /** Writes the forwarding handshake and the login start to the real server, then steps aside. */
    private void forward(UUID id, String properties, String playerName, Status status) {
        services.register(id, playerName, status);

        ByteArrayOutputStream all = new ByteArrayOutputStream();
        if (services.forwardIdentity()) {
            // the server runs with spigot.yml settings.bungeecord: pass on the UUID, skin and IP decided here
            ByteArrayOutputStream handshake = new ByteArrayOutputStream();
            PacketIo.writeVarInt(handshake, 0);
            PacketIo.writeVarInt(handshake, protocol);
            PacketIo.writeString(handshake, address + "\0" + sink.remoteIp() + "\0"
                    + id.toString().replace("-", "") + "\0" + properties);
            handshake.write(port >> 8);
            handshake.write(port & 0xFF);
            PacketIo.writeVarInt(handshake, 2);
            all.writeBytes(PacketIo.frame(handshake.toByteArray()));
        } else {
            // plain mode: the server derives the offline UUID from the name itself and sees the real IP directly
            all.writeBytes(rawHandshake);
        }

        ByteArrayOutputStream login = new ByteArrayOutputStream();
        PacketIo.writeVarInt(login, 0);
        PacketIo.writeString(login, playerName);
        login.writeBytes(loginRest);

        all.writeBytes(PacketIo.frame(login.toByteArray()));
        all.writeBytes(held.toByteArray());
        held.reset();

        state = State.PASSTHROUGH;
        services.debug("[" + sink.remoteIp() + "] " + playerName + ": let in as " + status + " (uuid " + id
                + (services.forwardIdentity() ? ", identity forwarded)" : ", plain mode)"));
        sink.toServer(all.toByteArray());
    }

    private void enterPassthrough(byte[] first) {
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        all.writeBytes(first);
        all.writeBytes(buffer.toByteArray());
        buffer.reset();
        state = State.PASSTHROUGH;
        sink.toServer(all.toByteArray());
    }

    // ------------------------------------------------------------------------------------------- helpers

    private void disconnect(String message) {
        String text = message.replace('&', '\u00a7');
        StringBuilder json = new StringBuilder("{\"text\":\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\n' -> json.append("\\n");
                default -> json.append(c);
            }
        }

        json.append("\"}");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PacketIo.writeVarInt(out, 0);
        PacketIo.writeString(out, json.toString());
        send(PacketIo.frame(out.toByteArray()));
        state = State.CLOSED;
        sink.close();
    }

    private void abort() {
        state = State.CLOSED;
        sink.close();
    }

    private void send(byte[] plain) {
        sink.toClient(crypt(cipherOut, plain));
    }

    private static byte[] crypt(Cipher cipher, byte[] data) {
        if (cipher == null || data.length == 0) {
            return data;
        }

        return cipher.update(data);
    }

    public static UUID offlineUuid(String playerName) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + playerName).getBytes(StandardCharsets.UTF_8));
    }

    /** Minecraft's signed-hex SHA-1 over the (empty) server id, the shared secret and the public key. */
    public static String serverHash(byte[] secret, byte[] publicKey) throws GeneralSecurityException {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        digest.update(secret);
        digest.update(publicKey);
        return new BigInteger(digest.digest()).toString(16);
    }
}
