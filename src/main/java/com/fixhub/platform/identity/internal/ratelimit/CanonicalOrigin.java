package com.fixhub.platform.identity.internal.ratelimit;

import java.util.Arrays;

/** The only origin representation that a future Slice 5B caller may receive. */
final class CanonicalOrigin {

    private final byte[] bytes;

    CanonicalOrigin(byte[] bytes) {
        if (bytes == null || !valid(bytes)) {
            throw new IllegalArgumentException("Invalid canonical origin");
        }
        this.bytes = bytes.clone();
    }

    static CanonicalOrigin fromAddress(byte[] address) {
        if (address == null || address.length != 4 && address.length != 16) {
            throw new IllegalArgumentException("Invalid canonical origin");
        }
        byte[] encoded = new byte[address.length + 1];
        encoded[0] = address.length == 4 ? (byte) 0x04 : (byte) 0x06;
        System.arraycopy(address, 0, encoded, 1, address.length);
        try {
            return new CanonicalOrigin(encoded);
        } finally {
            Arrays.fill(encoded, (byte) 0);
        }
    }

    private static boolean valid(byte[] bytes) {
        if (bytes.length == 5 && bytes[0] == 0x04) {
            return true;
        }
        if (bytes.length != 17 || bytes[0] != 0x06) {
            return false;
        }
        boolean mapped = true;
        for (int i = 1; i <= 10; i++) {
            mapped &= bytes[i] == 0;
        }
        mapped &= bytes[11] == (byte) 0xff && bytes[12] == (byte) 0xff;
        return !mapped;
    }

    /** The caller owns and may clear this fresh copy. */
    byte[] bytes() {
        return bytes.clone();
    }

    @Override
    public String toString() {
        return "CanonicalOrigin[REDACTED]";
    }
}
