package com.fixhub.platform.identity.internal.ratelimit;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Stateless, unwired origin resolution. */
final class OriginResolver {

    CanonicalOrigin resolve(IngressPolicy policy, OriginRequestInput input) {
        if (policy == null || input == null) {
            throw new IllegalArgumentException("Invalid origin resolution input");
        }
        byte[] peer = parseAddress(input.socketPeer(), OriginFailure.Category.MALFORMED_PEER);
        try {
            if (policy.mode() == IngressPolicy.Mode.DIRECT || !policy.trusts(peer)) {
                return CanonicalOrigin.fromAddress(peer);
            }
            List<String> selected;
            List<String> other;
            if (policy.family() == IngressPolicy.ForwardingFamily.FORWARDED) {
                selected = input.forwardedLines();
                other = input.xForwardedForLines();
            } else {
                selected = input.xForwardedForLines();
                other = input.forwardedLines();
            }
            if (!other.isEmpty()) {
                throw new OriginFailure(OriginFailure.Category.CONFLICTING_HEADER_FAMILY);
            }
            if (selected.isEmpty()) {
                throw new OriginFailure(OriginFailure.Category.MISSING_SELECTED_HEADER);
            }
            if (selected.size() != 1) {
                throw new OriginFailure(OriginFailure.Category.DUPLICATE_SELECTED_HEADER);
            }
            List<byte[]> hops = parseLine(selected.get(0), policy.family());
            try {
                for (int i = hops.size() - 1; i >= 0; i--) {
                    if (!policy.trusts(hops.get(i))) {
                        return CanonicalOrigin.fromAddress(hops.get(i));
                    }
                }
                throw new OriginFailure(OriginFailure.Category.ALL_HOPS_TRUSTED);
            } finally {
                for (byte[] hop : hops) {
                    Arrays.fill(hop, (byte) 0);
                }
            }
        } finally {
            Arrays.fill(peer, (byte) 0);
        }
    }

    private static byte[] parseAddress(String text, OriginFailure.Category category) {
        try {
            return NumericOriginAddress.parse(text);
        } catch (IllegalArgumentException failure) {
            throw new OriginFailure(category);
        }
    }

    private static List<byte[]> parseLine(String line, IngressPolicy.ForwardingFamily family) {
        if (line == null || line.isEmpty()) {
            throw new OriginFailure(OriginFailure.Category.MALFORMED_FORWARDING_CHAIN);
        }
        if (line.length() > 2048) {
            throw new OriginFailure(OriginFailure.Category.OVERSIZED_HEADER);
        }
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c > 0x7f || c == 0x7f || c < 0x20 && c != '\t') {
                throw new OriginFailure(OriginFailure.Category.MALFORMED_FORWARDING_CHAIN);
            }
        }
        List<byte[]> hops = new ArrayList<>(Math.min(16, line.length()));
        try {
            int start = 0;
            for (int i = 0; i <= line.length(); i++) {
                if (i < line.length() && line.charAt(i) != ',') {
                    continue;
                }
                if (hops.size() == 16) {
                    throw new OriginFailure(OriginFailure.Category.TOO_MANY_HOPS);
                }
                if (i - start > 128) {
                    throw new OriginFailure(OriginFailure.Category.OVERSIZED_HEADER);
                }
                int begin = start;
                int end = i;
                while (begin < end && isOws(line.charAt(begin))) {
                    begin++;
                }
                while (end > begin && isOws(line.charAt(end - 1))) {
                    end--;
                }
                if (begin == end) {
                    throw new OriginFailure(OriginFailure.Category.MALFORMED_FORWARDING_CHAIN);
                }
                String element = line.substring(begin, end);
                hops.add(
                        family == IngressPolicy.ForwardingFamily.X_FORWARDED
                                ? parseAddress(
                                        element, OriginFailure.Category.MALFORMED_FORWARDING_CHAIN)
                                : parseForwarded(element));
                start = i + 1;
            }
            return hops;
        } catch (RuntimeException failure) {
            for (byte[] hop : hops) {
                Arrays.fill(hop, (byte) 0);
            }
            throw failure;
        }
    }

    private static byte[] parseForwarded(String element) {
        if (element.length() < 5
                || !element.regionMatches(true, 0, "for=", 0, 4)
                || element.indexOf(';') >= 0) {
            throw new OriginFailure(OriginFailure.Category.MALFORMED_FORWARDING_CHAIN);
        }
        String node = element.substring(4);
        if (node.startsWith("\"")) {
            if (node.length() < 5
                    || !node.startsWith("\"[")
                    || !node.endsWith("]\"")
                    || node.indexOf('\\') >= 0) {
                throw new OriginFailure(OriginFailure.Category.MALFORMED_FORWARDING_CHAIN);
            }
            String addressText = node.substring(2, node.length() - 2);
            if (addressText.indexOf(':') < 0) {
                throw new OriginFailure(OriginFailure.Category.MALFORMED_FORWARDING_CHAIN);
            }
            return parseAddress(addressText, OriginFailure.Category.MALFORMED_FORWARDING_CHAIN);
        }
        if (node.indexOf(':') >= 0) {
            throw new OriginFailure(OriginFailure.Category.MALFORMED_FORWARDING_CHAIN);
        }
        byte[] address = parseAddress(node, OriginFailure.Category.MALFORMED_FORWARDING_CHAIN);
        if (address.length != 4) {
            Arrays.fill(address, (byte) 0);
            throw new OriginFailure(OriginFailure.Category.MALFORMED_FORWARDING_CHAIN);
        }
        return address;
    }

    private static boolean isOws(char c) {
        return c == ' ' || c == '\t';
    }

    @Override
    public String toString() {
        return "OriginResolver[REDACTED]";
    }
}

final class OriginFailure extends RuntimeException {

    enum Category {
        MALFORMED_PEER,
        MISSING_SELECTED_HEADER,
        DUPLICATE_SELECTED_HEADER,
        CONFLICTING_HEADER_FAMILY,
        OVERSIZED_HEADER,
        TOO_MANY_HOPS,
        MALFORMED_FORWARDING_CHAIN,
        ALL_HOPS_TRUSTED
    }

    private final Category category;

    OriginFailure(Category category) {
        super("Trusted-origin resolution failed");
        this.category = category;
    }

    Category category() {
        return category;
    }
}
