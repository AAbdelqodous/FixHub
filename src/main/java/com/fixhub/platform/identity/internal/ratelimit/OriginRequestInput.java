package com.fixhub.platform.identity.internal.ratelimit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Physical forwarding lines, kept separate so duplicate lines cannot be hidden by joining them. */
final class OriginRequestInput {

    private final String socketPeer;
    private final List<String> forwardedLines;
    private final List<String> xForwardedForLines;

    OriginRequestInput(
            String socketPeer, List<String> forwardedLines, List<String> xForwardedForLines) {
        this.socketPeer = socketPeer;
        this.forwardedLines = copyLines(forwardedLines);
        this.xForwardedForLines = copyLines(xForwardedForLines);
    }

    private static List<String> copyLines(List<String> lines) {
        if (lines == null) {
            throw new IllegalArgumentException("Invalid origin request input");
        }
        return Collections.unmodifiableList(new ArrayList<>(lines));
    }

    String socketPeer() {
        return socketPeer;
    }

    List<String> forwardedLines() {
        return forwardedLines;
    }

    List<String> xForwardedForLines() {
        return xForwardedForLines;
    }

    @Override
    public String toString() {
        return "OriginRequestInput[REDACTED]";
    }
}
