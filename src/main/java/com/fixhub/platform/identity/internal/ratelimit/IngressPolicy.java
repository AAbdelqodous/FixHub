package com.fixhub.platform.identity.internal.ratelimit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Immutable, unwired trusted-ingress policy. */
final class IngressPolicy {

    enum Mode {
        DIRECT,
        PROXIED
    }

    enum ForwardingFamily {
        FORWARDED,
        X_FORWARDED
    }

    private static final String INVALID = "Invalid ingress policy";

    private final Mode mode;
    private final ForwardingFamily family;
    private final List<TrustedCidr> trustedCidrs;

    IngressPolicy(Mode mode, ForwardingFamily family, List<String> cidrTexts) {
        if (mode == null) {
            throw new IllegalArgumentException(INVALID);
        }
        this.mode = mode;
        if (mode == Mode.DIRECT) {
            if (family != null || cidrTexts != null) {
                throw new IllegalArgumentException(INVALID);
            }
            this.family = null;
            this.trustedCidrs = List.of();
            return;
        }
        if (family == null || cidrTexts == null || cidrTexts.isEmpty() || cidrTexts.size() > 64) {
            throw new IllegalArgumentException(INVALID);
        }
        List<TrustedCidr> parsed = new ArrayList<>(cidrTexts.size());
        for (String text : cidrTexts) {
            TrustedCidr next = TrustedCidr.parse(text);
            for (TrustedCidr prior : parsed) {
                if (next.overlaps(prior)) {
                    throw new IllegalArgumentException(INVALID);
                }
            }
            parsed.add(next);
        }
        this.family = family;
        this.trustedCidrs = List.copyOf(parsed);
    }

    Mode mode() {
        return mode;
    }

    ForwardingFamily family() {
        return family;
    }

    boolean trusts(byte[] address) {
        for (TrustedCidr cidr : trustedCidrs) {
            if (cidr.contains(address)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return "IngressPolicy[REDACTED]";
    }

    private static final class TrustedCidr {

        private final byte[] network;
        private final int prefix;

        private TrustedCidr(byte[] network, int prefix) {
            this.network = network.clone();
            this.prefix = prefix;
        }

        static TrustedCidr parse(String text) {
            if (text == null || text.isEmpty() || text.length() > 64) {
                throw new IllegalArgumentException(INVALID);
            }
            int slash = text.indexOf('/');
            if (slash < 1 || slash != text.lastIndexOf('/') || slash == text.length() - 1) {
                throw new IllegalArgumentException(INVALID);
            }
            byte[] address;
            try {
                address = NumericOriginAddress.parse(text.substring(0, slash));
            } catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException(INVALID);
            }
            try {
                if (address.length == 4 && text.substring(0, slash).indexOf(':') >= 0) {
                    throw new IllegalArgumentException(INVALID);
                }
                int prefix = parsePrefix(text, slash + 1, address.length * 8);
                if (prefix == 0
                        || !hostBitsClear(address, prefix)
                        || invalidNetwork(address, prefix)) {
                    throw new IllegalArgumentException(INVALID);
                }
                return new TrustedCidr(address, prefix);
            } finally {
                Arrays.fill(address, (byte) 0);
            }
        }

        private static int parsePrefix(String text, int start, int maximum) {
            int value = 0;
            for (int i = start; i < text.length(); i++) {
                char digit = text.charAt(i);
                if (digit < '0' || digit > '9') {
                    throw new IllegalArgumentException(INVALID);
                }
                value = value * 10 + digit - '0';
                if (value > maximum) {
                    throw new IllegalArgumentException(INVALID);
                }
            }
            return value;
        }

        private static boolean hostBitsClear(byte[] address, int prefix) {
            for (int bit = prefix; bit < address.length * 8; bit++) {
                if ((address[bit / 8] & (0x80 >>> (bit % 8))) != 0) {
                    return false;
                }
            }
            return true;
        }

        private static boolean invalidNetwork(byte[] address, int prefix) {
            boolean unspecified = true;
            for (byte octet : address) {
                unspecified &= octet == 0;
            }
            if (unspecified) {
                return true;
            }
            if (address.length == 4) {
                byte[] multicast = {(byte) 224, 0, 0, 0};
                return prefixEquals(address, multicast, Math.min(prefix, 4));
            }
            byte[] multicast = new byte[16];
            multicast[0] = (byte) 0xff;
            return prefixEquals(address, multicast, Math.min(prefix, 8));
        }

        boolean overlaps(TrustedCidr other) {
            return network.length == other.network.length
                    && prefixEquals(network, other.network, Math.min(prefix, other.prefix));
        }

        boolean contains(byte[] address) {
            return address.length == network.length && prefixEquals(network, address, prefix);
        }

        private static boolean prefixEquals(byte[] left, byte[] right, int bits) {
            for (int bit = 0; bit < bits; bit++) {
                int mask = 0x80 >>> (bit % 8);
                if ((left[bit / 8] & mask) != (right[bit / 8] & mask)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public String toString() {
            return "TrustedCidr[REDACTED]";
        }
    }
}
