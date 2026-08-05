package com.launcher.minecraft;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads Minecraft's {@code servers.dat} file — the binary NBT file each game
 * directory keeps with the player's saved "Direct Connect" / multiplayer
 * server list — so the launcher can show those servers directly instead of
 * making the user retype IPs by hand.
 * <p>
 * {@code servers.dat} is a small, uncompressed NBT file shaped like:
 * <pre>
 * TAG_Compound ""
 *   TAG_List "servers" (TAG_Compound)
 *     TAG_Compound
 *       TAG_String "name"
 *       TAG_String "ip"
 *       TAG_Byte   "acceptTextures"   (optional)
 *       TAG_String "icon"             (optional, base64 PNG — not read here)
 * </pre>
 * This is a tiny, purpose-built NBT reader — it only extracts what the
 * launcher needs (server name + address) and safely skips every other tag
 * type it encounters so it won't choke on fields added by newer game
 * versions or mods.
 */
public class ServerListReader {

    /** One entry from a servers.dat file. */
    public static final class ServerEntry {
        public final String name;
        public final String ip;
        public ServerEntry(String name, String ip) {
            this.name = name;
            this.ip = ip;
        }
        @Override public String toString() { return name + " (" + ip + ")"; }
    }

    private static final int TAG_END        = 0;
    private static final int TAG_BYTE       = 1;
    private static final int TAG_SHORT      = 2;
    private static final int TAG_INT        = 3;
    private static final int TAG_LONG       = 4;
    private static final int TAG_FLOAT      = 5;
    private static final int TAG_DOUBLE     = 6;
    private static final int TAG_BYTE_ARRAY = 7;
    private static final int TAG_STRING     = 8;
    private static final int TAG_LIST       = 9;
    private static final int TAG_COMPOUND   = 10;
    private static final int TAG_INT_ARRAY  = 11;
    private static final int TAG_LONG_ARRAY = 12;

    /**
     * Reads the servers.dat file for the given game directory.
     * Returns an empty list (never null) if the file doesn't exist or can't be parsed —
     * this is a best-effort convenience feature, not something that should ever crash the UI.
     */
    public static List<ServerEntry> readServers(Path gameDir) {
        List<ServerEntry> out = new ArrayList<>();
        if (gameDir == null) return out;
        Path file = gameDir.resolve("servers.dat");
        if (!Files.exists(file)) return out;

        try (InputStream raw = Files.newInputStream(file);
             DataInputStream in = new DataInputStream(raw)) {

            int rootType = in.readUnsignedByte();
            if (rootType != TAG_COMPOUND) return out; // not a valid servers.dat
            readUTF(in); // root compound name (usually empty)
            readCompoundBody(in, out);

        } catch (Exception ignored) {
            // Corrupt / unreadable / unexpected format — just show no servers for this instance.
        }
        return out;
    }

    /** Reads the body of a compound (tags until TAG_End), collecting server entries when found. */
    private static void readCompoundBody(DataInputStream in, List<ServerEntry> out) throws IOException {
        while (true) {
            int type = in.readUnsignedByte();
            if (type == TAG_END) return;
            String name = readUTF(in);
            if (type == TAG_LIST && "servers".equals(name)) {
                readServersList(in, out);
            } else {
                skipTag(in, type);
            }
        }
    }

    /** Reads a TAG_List known to be the "servers" list, extracting name/ip from each compound entry. */
    private static void readServersList(DataInputStream in, List<ServerEntry> out) throws IOException {
        int elementType = in.readUnsignedByte();
        int length = in.readInt();
        for (int i = 0; i < length; i++) {
            if (elementType == TAG_COMPOUND) {
                ServerEntry entry = readServerCompound(in);
                if (entry != null) out.add(entry);
            } else {
                skipTagBody(in, elementType);
            }
        }
    }

    /** Reads one server entry's compound, pulling out "name" and "ip" and skipping everything else. */
    private static ServerEntry readServerCompound(DataInputStream in) throws IOException {
        String name = null, ip = null;
        while (true) {
            int type = in.readUnsignedByte();
            if (type == TAG_END) break;
            String tagName = readUTF(in);
            if (type == TAG_STRING && "name".equals(tagName)) {
                name = readUTF(in);
            } else if (type == TAG_STRING && "ip".equals(tagName)) {
                ip = readUTF(in);
            } else {
                skipTag(in, type);
            }
        }
        if (ip == null) return null;
        return new ServerEntry(name != null && !name.isBlank() ? name : ip, ip);
    }

    /** Reads a TAG_Compound's name-prefixed body then discards it (used when skipping nested compounds). */
    private static void skipTag(DataInputStream in, int type) throws IOException {
        skipTagBody(in, type);
    }

    /** Skips the *value* of a tag of the given type (name, if any, must already be consumed). */
    private static void skipTagBody(DataInputStream in, int type) throws IOException {
        switch (type) {
            case TAG_END -> { }
            case TAG_BYTE -> in.skipBytes(1);
            case TAG_SHORT -> in.skipBytes(2);
            case TAG_INT, TAG_FLOAT -> in.skipBytes(4);
            case TAG_LONG, TAG_DOUBLE -> in.skipBytes(8);
            case TAG_BYTE_ARRAY -> {
                int len = in.readInt();
                in.skipBytes(len);
            }
            case TAG_STRING -> readUTF(in);
            case TAG_LIST -> {
                int elementType = in.readUnsignedByte();
                int len = in.readInt();
                for (int i = 0; i < len; i++) skipTagBody(in, elementType);
            }
            case TAG_COMPOUND -> {
                while (true) {
                    int t = in.readUnsignedByte();
                    if (t == TAG_END) break;
                    readUTF(in); // nested tag name
                    skipTagBody(in, t);
                }
            }
            case TAG_INT_ARRAY -> {
                int len = in.readInt();
                in.skipBytes(len * 4L > Integer.MAX_VALUE ? Integer.MAX_VALUE : len * 4);
            }
            case TAG_LONG_ARRAY -> {
                int len = in.readInt();
                long bytes = len * 8L;
                in.skipBytes(bytes > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) bytes);
            }
            default -> throw new IOException("Unknown NBT tag type: " + type);
        }
    }

    /** NBT strings are length-prefixed UTF-8 (modified UTF-8, same as DataInput#readUTF). */
    private static String readUTF(DataInputStream in) throws IOException {
        return in.readUTF();
    }
}
