package dev.experimental.rmgu.d2;

import java.util.Arrays;

/** The measured DMC layout. Unknown bytes are preserved and never logged. */
public final class VaultRecord {
    public static final int SIZE = 32;
    private final byte[] bytes;

    private VaultRecord(byte[] bytes) { this.bytes = bytes.clone(); }

    public static VaultRecord parse(byte[] bytes) {
        if (bytes == null || bytes.length != SIZE) {
            throw new IllegalArgumentException("Unrecognized DMC record length");
        }
        for (int i = 0; i < 3; i++) {
            if (bytes[i] != 0 && bytes[i] != 1) {
                throw new IllegalArgumentException("Unrecognized DMC flag value");
            }
        }
        return new VaultRecord(bytes);
    }

    public int lock() { return bytes[0]; }
    public int maintenance() { return bytes[1]; }
    public int at() { return bytes[2]; }
    public boolean odinAllowed() { return lock() == 0 || maintenance() == 1 || at() == 1; }
    public byte[] copy() { return bytes.clone(); }
    public byte[] withAtFlag() { byte[] copy = copy(); copy[2] = 1; return copy; }
    public boolean same(byte[] other) { return Arrays.equals(bytes, other); }
}
