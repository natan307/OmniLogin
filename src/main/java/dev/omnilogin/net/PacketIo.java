package dev.omnilogin.net;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Minecraft packet framing helpers (VarInt, strings, byte arrays). */
final class PacketIo {

    private PacketIo() {
    }

    /** Returns {value, bytesUsed}, or null when the VarInt is not complete yet. */
    static int[] readVarInt(byte[] data, int offset) throws IOException {
        int value = 0;
        for (int i = 0; i < 5; i++) {
            if (offset + i >= data.length) {
                return null;
            }
            int b = data[offset + i];
            value |= (b & 0x7F) << (7 * i);
            if ((b & 0x80) == 0) {
                return new int[] {value, i + 1};
            }
        }
        throw new IOException("VarInt too long");
    }

    static void writeVarInt(ByteArrayOutputStream out, int value) {
        int v = value;
        while ((v & ~0x7F) != 0) {
            out.write((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.write(v);
    }

    static void writeString(ByteArrayOutputStream out, String s) {
        byte[] data = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, data.length);
        out.writeBytes(data);
    }

    static void writeBytes(ByteArrayOutputStream out, byte[] data) {
        writeVarInt(out, data.length);
        out.writeBytes(data);
    }

    /** Prefixes the payload with its length. */
    static byte[] frame(byte[] payload) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(payload.length + 5);
        writeVarInt(out, payload.length);
        out.writeBytes(payload);
        return out.toByteArray();
    }

    /** Sequential reader over one packet payload. */
    static final class Reader {

        private final byte[] data;
        private int pos;

        Reader(byte[] data) {
            this.data = data;
        }

        int varInt() throws IOException {
            int[] r = readVarInt(data, pos);
            if (r == null) {
                throw new EOFException();
            }
            pos += r[1];
            return r[0];
        }

        String string() throws IOException {
            int n = varInt();
            if (n < 0 || pos + n > data.length) {
                throw new EOFException();
            }
            String s = new String(data, pos, n, StandardCharsets.UTF_8);
            pos += n;
            return s;
        }

        byte[] byteArray() throws IOException {
            int n = varInt();
            if (n < 0 || pos + n > data.length) {
                throw new EOFException();
            }
            byte[] r = Arrays.copyOfRange(data, pos, pos + n);
            pos += n;
            return r;
        }

        int unsignedShort() throws IOException {
            if (pos + 2 > data.length) {
                throw new EOFException();
            }
            int v = ((data[pos] & 0xFF) << 8) | (data[pos + 1] & 0xFF);
            pos += 2;
            return v;
        }

        byte[] remaining() {
            return Arrays.copyOfRange(data, pos, data.length);
        }
    }
}
