package com.fixhub.platform.identity.internal.ratelimit;

import java.util.Arrays;

/** Strict local numeric parsing; this class never asks the network or name service. */
final class NumericOriginAddress {

    private static final String INVALID = "Invalid numeric origin address";

    private NumericOriginAddress() {}

    static byte[] parse(String text) {
        if (text == null || text.isEmpty() || text.length() > 64) {
            throw new IllegalArgumentException(INVALID);
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c > 0x7f || !(isHex(c) || c == ':' || c == '.')) {
                throw new IllegalArgumentException(INVALID);
            }
        }
        if (text.indexOf(':') < 0) {
            return parseIpv4(text);
        }
        return parseIpv6(text);
    }

    private static byte[] parseIpv4(String text) {
        byte[] address = new byte[4];
        try {
            int part = 0;
            int start = 0;
            for (int i = 0; i <= text.length(); i++) {
                if (i < text.length() && text.charAt(i) != '.') {
                    continue;
                }
                if (part == 4
                        || i == start
                        || i - start > 3
                        || (i - start > 1 && text.charAt(start) == '0')) {
                    throw new IllegalArgumentException(INVALID);
                }
                int value = 0;
                for (int j = start; j < i; j++) {
                    char digit = text.charAt(j);
                    if (digit < '0' || digit > '9') {
                        throw new IllegalArgumentException(INVALID);
                    }
                    value = value * 10 + digit - '0';
                }
                if (value > 255) {
                    throw new IllegalArgumentException(INVALID);
                }
                address[part++] = (byte) value;
                start = i + 1;
            }
            if (part != 4) {
                throw new IllegalArgumentException(INVALID);
            }
            return address;
        } catch (RuntimeException failure) {
            Arrays.fill(address, (byte) 0);
            throw failure;
        }
    }

    private static byte[] parseIpv6(String text) {
        int doubleColon = text.indexOf("::");
        if (doubleColon >= 0 && text.indexOf("::", doubleColon + 2) >= 0) {
            throw new IllegalArgumentException(INVALID);
        }
        String left = doubleColon < 0 ? text : text.substring(0, doubleColon);
        String right = doubleColon < 0 ? "" : text.substring(doubleColon + 2);
        if (left.startsWith(":")
                || left.endsWith(":")
                || right.startsWith(":")
                || right.endsWith(":")) {
            throw new IllegalArgumentException(INVALID);
        }
        int[] groups = new int[8];
        int[] rightGroups = new int[8];
        try {
            int leftCount = parseGroups(left, groups, 0, doubleColon < 0);
            int rightCount = doubleColon < 0 ? 0 : parseGroups(right, rightGroups, 0, true);
            if (doubleColon < 0 && leftCount != 8
                    || doubleColon >= 0 && leftCount + rightCount >= 8) {
                throw new IllegalArgumentException(INVALID);
            }
            System.arraycopy(rightGroups, 0, groups, 8 - rightCount, rightCount);
            byte[] address = new byte[16];
            for (int i = 0; i < 8; i++) {
                address[i * 2] = (byte) (groups[i] >>> 8);
                address[i * 2 + 1] = (byte) groups[i];
            }
            boolean mapped = isMapped(address);
            if (text.indexOf('.') >= 0 && !mapped) {
                Arrays.fill(address, (byte) 0);
                throw new IllegalArgumentException(INVALID);
            }
            if (mapped) {
                byte[] ipv4 = Arrays.copyOfRange(address, 12, 16);
                Arrays.fill(address, (byte) 0);
                return ipv4;
            }
            return address;
        } finally {
            Arrays.fill(groups, 0);
            Arrays.fill(rightGroups, 0);
        }
    }

    private static int parseGroups(String text, int[] groups, int start, boolean allowDottedTail) {
        if (text.isEmpty()) {
            return 0;
        }
        int count = 0;
        int begin = 0;
        for (int i = 0; i <= text.length(); i++) {
            if (i < text.length() && text.charAt(i) != ':') {
                continue;
            }
            if (i == begin || count + start >= 8) {
                throw new IllegalArgumentException(INVALID);
            }
            String group = text.substring(begin, i);
            if (group.indexOf('.') >= 0) {
                if (!allowDottedTail || i != text.length() || count + start > 6) {
                    throw new IllegalArgumentException(INVALID);
                }
                byte[] ipv4 = parseIpv4(group);
                try {
                    groups[start + count++] = ((ipv4[0] & 0xff) << 8) | (ipv4[1] & 0xff);
                    groups[start + count++] = ((ipv4[2] & 0xff) << 8) | (ipv4[3] & 0xff);
                } finally {
                    Arrays.fill(ipv4, (byte) 0);
                }
            } else {
                if (group.length() > 4) {
                    throw new IllegalArgumentException(INVALID);
                }
                int value = 0;
                for (int j = 0; j < group.length(); j++) {
                    char c = group.charAt(j);
                    if (!isHex(c)) {
                        throw new IllegalArgumentException(INVALID);
                    }
                    value = (value << 4) | Character.digit(c, 16);
                }
                groups[start + count++] = value;
            }
            begin = i + 1;
        }
        return count;
    }

    private static boolean isHex(char c) {
        return c >= '0' && c <= '9' || c >= 'a' && c <= 'f' || c >= 'A' && c <= 'F';
    }

    private static boolean isMapped(byte[] address) {
        for (int i = 0; i < 10; i++) {
            if (address[i] != 0) {
                return false;
            }
        }
        return address[10] == (byte) 0xff && address[11] == (byte) 0xff;
    }
}
