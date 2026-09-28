package com.fixhub.platform.identity.internal.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Modifier;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class OriginResolverTest {

    private final OriginResolver resolver = new OriginResolver();
    private final IngressPolicy direct = new IngressPolicy(IngressPolicy.Mode.DIRECT, null, null);
    private final IngressPolicy forwarded = proxied(IngressPolicy.ForwardingFamily.FORWARDED);
    private final IngressPolicy xForwarded = proxied(IngressPolicy.ForwardingFamily.X_FORWARDED);

    @Test
    void directUsesPeerAndDoesNotInspectAnyForwardingLine() {
        sameOrigin(direct, input("203.0.113.9", List.of(), List.of()), "203.0.113.9");
        List<String> malformed = Arrays.asList(null, "\r\n", "é".repeat(3000), "bad,bad");
        sameOrigin(direct, input("203.0.113.9", malformed, malformed), "203.0.113.9");
        failure(
                OriginFailure.Category.MALFORMED_PEER,
                direct,
                input("bad-peer", malformed, malformed));
    }

    @Test
    void untrustedPeerIgnoresBothFamiliesWithoutParsing() {
        List<String> malformed = Arrays.asList(null, "\r\n", "é".repeat(3000));
        sameOrigin(forwarded, input("203.0.113.9", malformed, malformed), "203.0.113.9");
        sameOrigin(xForwarded, input("203.0.113.9", malformed, malformed), "203.0.113.9");
    }

    @Test
    void strictPeerParserAcceptsEquivalentIpv6AndMappedIpv4() {
        byte[] exactIpv4 = {(byte) 0x04, (byte) 192, 0, 2, 1};
        byte[] exactIpv6 = {
            (byte) 0x06, 0x20, 0x01, 0x0d, (byte) 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x01
        };
        byte[] actualIpv4 =
                resolver.resolve(direct, input("192.0.2.1", List.of(), List.of())).bytes();
        byte[] actualIpv6 =
                resolver.resolve(direct, input("2001:db8::1", List.of(), List.of())).bytes();
        try {
            assertThat(MessageDigest.isEqual(actualIpv4, exactIpv4)).isTrue();
            assertThat(MessageDigest.isEqual(actualIpv6, exactIpv6)).isTrue();
        } finally {
            Arrays.fill(exactIpv4, (byte) 0);
            Arrays.fill(exactIpv6, (byte) 0);
            Arrays.fill(actualIpv4, (byte) 0);
            Arrays.fill(actualIpv6, (byte) 0);
        }
        sameOrigin(direct, input("192.0.2.1", List.of(), List.of()), "192.0.2.1");
        sameOrigin(direct, input("2001:db8::1", List.of(), List.of()), "2001:db8::1");
        sameOrigin(
                direct,
                input("2001:0DB8:0000:0000:0000:0000:0000:0001", List.of(), List.of()),
                "2001:db8::1");
        sameOrigin(direct, input("::ffff:192.0.2.1", List.of(), List.of()), "192.0.2.1");
        sameOrigin(direct, input("0:0:0:0:0:ffff:c000:201", List.of(), List.of()), "192.0.2.1");
        sameOrigin(direct, input("::c000:201", List.of(), List.of()), "::c000:201");
    }

    @Test
    void peerRejectsEveryNonNumericOrAmbiguousShape() {
        for (String value :
                new String[] {
                    "",
                    " ",
                    " 192.0.2.1",
                    "192.0.2.1 ",
                    "192.0. 2.1",
                    "192.0.2",
                    "192.0.2.1.5",
                    "192.0.2.256",
                    "192.0.2.01",
                    "+192.0.2.1",
                    "0xc0.0.2.1",
                    "example.test",
                    "[2001:db8::1]",
                    "[2001:db8::1]:80",
                    "2001:db8::1%eth0",
                    "2001:db8::1/64",
                    "2001:::1",
                    "2001::1::2",
                    "1:2:3:4:5:6:7",
                    "1:2:3:4:5:6:7:8:9",
                    "2001:db8::192.0.2.1",
                    "::ffff:192.0.2.01",
                    "::ffff:192.0.2.256",
                    "192.0.2.1\n",
                    "é",
                    "1".repeat(65)
                }) {
            failure(
                    OriginFailure.Category.MALFORMED_PEER,
                    direct,
                    input(value, List.of(), List.of()));
        }
        failure(OriginFailure.Category.MALFORMED_PEER, direct, input(null, List.of(), List.of()));
    }

    @Test
    void trustedPeerRequiresOneSelectedPhysicalLineAndNoOtherFamily() {
        failure(
                OriginFailure.Category.MISSING_SELECTED_HEADER,
                forwarded,
                input("10.0.0.1", List.of(), List.of()));
        failure(
                OriginFailure.Category.DUPLICATE_SELECTED_HEADER,
                forwarded,
                input("10.0.0.1", List.of("for=203.0.113.1", "for=203.0.113.2"), List.of()));
        failure(
                OriginFailure.Category.CONFLICTING_HEADER_FAMILY,
                forwarded,
                input("10.0.0.1", List.of("for=203.0.113.1"), Arrays.asList((String) null)));
        failure(
                OriginFailure.Category.CONFLICTING_HEADER_FAMILY,
                xForwarded,
                input("10.0.0.1", List.of(""), List.of("203.0.113.1")));
        sameOrigin(
                forwarded, input("10.0.0.1", List.of("for=203.0.113.1"), List.of()), "203.0.113.1");
        sameOrigin(xForwarded, input("10.0.0.1", List.of(), List.of("203.0.113.1")), "203.0.113.1");
    }

    @Test
    void xForwardedGrammarAndBounds() {
        sameOrigin(
                xForwarded,
                input("10.0.0.1", List.of(), List.of("\t203.0.113.9 \t, 10.0.0.2\t")),
                "203.0.113.9");
        sameOrigin(
                xForwarded,
                input("10.0.0.1", List.of(), List.of("2001:db8::1, 10.0.0.2")),
                "2001:db8::1");
        sameOrigin(
                xForwarded,
                input("10.0.0.1", List.of(), List.of("::ffff:203.0.113.9")),
                "203.0.113.9");
        List<String> sixteen = new ArrayList<>();
        sixteen.add("203.0.113.9");
        for (int i = 0; i < 15; i++) {
            sixteen.add("10.0.0.2");
        }
        sameOrigin(
                xForwarded,
                input("10.0.0.1", List.of(), List.of(String.join(",", sixteen))),
                "203.0.113.9");
        sixteen.add("10.0.0.2");
        failure(
                OriginFailure.Category.TOO_MANY_HOPS,
                xForwarded,
                input("10.0.0.1", List.of(), List.of(String.join(",", sixteen))));
        for (String bad :
                new String[] {
                    "",
                    ",203.0.113.9",
                    "203.0.113.9,",
                    "203.0.113.9,,10.0.0.2",
                    "\"203.0.113.9\"",
                    "[2001:db8::1]",
                    "203.0.113.9:80",
                    "203.0.113.9/24",
                    "2001:db8::1%zone",
                    "unknown",
                    "_obfuscated",
                    "example.test",
                    "203.0.113.9;for=x",
                    "203.0.113.9\n",
                    "é"
                }) {
            failure(
                    OriginFailure.Category.MALFORMED_FORWARDING_CHAIN,
                    xForwarded,
                    input("10.0.0.1", List.of(), List.of(bad)));
        }
        failure(
                OriginFailure.Category.OVERSIZED_HEADER,
                xForwarded,
                input("10.0.0.1", List.of(), List.of("x".repeat(129))));
        sameOrigin(
                xForwarded,
                input(
                        "10.0.0.1",
                        List.of(),
                        List.of(" ".repeat(64) + "203.0.113.9" + " ".repeat(53))),
                "203.0.113.9");
        List<String> fullLineElements = new ArrayList<>();
        fullLineElements.add("203.0.113.9" + " ".repeat(117));
        for (int i = 0; i < 15; i++) {
            fullLineElements.add("10.0.0.2" + " ".repeat(119));
        }
        String fullLine = String.join(",", fullLineElements);
        assertThat(fullLine.length()).isEqualTo(2048);
        sameOrigin(xForwarded, input("10.0.0.1", List.of(), List.of(fullLine)), "203.0.113.9");
        failure(
                OriginFailure.Category.OVERSIZED_HEADER,
                xForwarded,
                input("10.0.0.1", List.of(), List.of("x".repeat(2049))));
    }

    @Test
    void forwardedStrictSubsetAndMappedNormalization() {
        sameOrigin(
                forwarded,
                input("10.0.0.1", List.of("FoR=\"[2001:db8::1]\",for=10.0.0.2"), List.of()),
                "2001:db8::1");
        sameOrigin(
                forwarded,
                input("10.0.0.1", List.of("for=\"[::ffff:203.0.113.9]\""), List.of()),
                "203.0.113.9");
        for (String bad :
                new String[] {
                    "203.0.113.9",
                    "by=203.0.113.9",
                    "for=203.0.113.9;proto=https",
                    "for=203.0.113.9;for=203.0.113.8",
                    "for =203.0.113.9",
                    "for= 203.0.113.9",
                    "for=\"203.0.113.9\"",
                    "for=2001:db8::1",
                    "for=\"2001:db8::1\"",
                    "for=\"[2001:db8::1]:443\"",
                    "for=\"[2001:db8::1]\":443",
                    "for=\"[2001:db8::1%zone]\"",
                    "for=\"[2001:db8::1/64]\"",
                    "for=\"[2001:db8::1]\\\"",
                    "for=unknown",
                    "for=_obfuscated",
                    "for=example.test",
                    "for=",
                    "for=\"[192.0.2.1]\""
                }) {
            failure(
                    OriginFailure.Category.MALFORMED_FORWARDING_CHAIN,
                    forwarded,
                    input("10.0.0.1", List.of(bad), List.of()));
        }
    }

    @Test
    void traversalSelectsFirstUntrustedFromApplicationSide() {
        sameOrigin(
                xForwarded,
                input("10.0.0.1", List.of(), List.of("203.0.113.9,10.0.0.2")),
                "203.0.113.9");
        sameOrigin(
                xForwarded,
                input("10.0.0.1", List.of(), List.of("192.0.2.99,203.0.113.9,10.0.0.2")),
                "203.0.113.9");
        sameOrigin(
                xForwarded,
                input("10.0.0.1", List.of(), List.of("2001:db8::9,10.0.0.2")),
                "2001:db8::9");
        failure(
                OriginFailure.Category.ALL_HOPS_TRUSTED,
                xForwarded,
                input("10.0.0.1", List.of(), List.of("10.0.0.2,10.0.0.3")));
        sameOrigin(
                xForwarded,
                input("::ffff:10.0.0.1", List.of(), List.of("203.0.113.9,::ffff:10.0.0.2")),
                "203.0.113.9");
    }

    @Test
    void inputCollectionsAreCopiedAndDiagnosticsAreRedacted() {
        List<String> mutable = new ArrayList<>(List.of("203.0.113.9"));
        OriginRequestInput input = input("10.0.0.1", List.of(), mutable);
        mutable.clear();
        sameOrigin(xForwarded, input, "203.0.113.9");
        assertThat(input.toString()).isEqualTo("OriginRequestInput[REDACTED]");
        assertThat(resolver.toString()).isEqualTo("OriginResolver[REDACTED]");
        assertThatThrownBy(() -> input.xForwardedForLines().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
        OriginFailure error =
                org.assertj.core.api.Assertions.catchThrowableOfType(
                        () -> resolver.resolve(forwarded, input), OriginFailure.class);
        assertThat(error.category()).isEqualTo(OriginFailure.Category.CONFLICTING_HEADER_FAMILY);
        assertThat(error.getMessage()).isEqualTo("Trusted-origin resolution failed");
        assertThat(Modifier.isFinal(CanonicalOrigin.class.getModifiers())).isTrue();
        assertThat(CanonicalOrigin.class.isRecord()).isFalse();
        assertThat(java.io.Serializable.class.isAssignableFrom(CanonicalOrigin.class)).isFalse();
        for (var method : CanonicalOrigin.class.getDeclaredMethods()) {
            assertThat(
                            method.getName().startsWith("get")
                                    || method.getName().equals("equals")
                                    || method.getName().equals("hashCode"))
                    .isFalse();
        }
    }

    @Test
    void immutablePolicySupportsBoundedConcurrentIndependentResolutions() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Future<Boolean>> futures = new ArrayList<>();
        try {
            Callable<Boolean> task =
                    () -> {
                        CanonicalOrigin origin =
                                resolver.resolve(
                                        xForwarded,
                                        input("10.0.0.1", List.of(), List.of("203.0.113.9")));
                        byte[] actual = origin.bytes();
                        byte[] expected = expected("203.0.113.9");
                        try {
                            return MessageDigest.isEqual(actual, expected);
                        } finally {
                            Arrays.fill(actual, (byte) 0);
                            Arrays.fill(expected, (byte) 0);
                        }
                    };
            futures.add(executor.submit(task));
            futures.add(executor.submit(task));
            for (Future<Boolean> future : futures) {
                assertThat(future.get(5, TimeUnit.SECONDS)).isTrue();
            }
        } finally {
            for (Future<Boolean> future : futures) {
                future.cancel(true);
            }
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private static IngressPolicy proxied(IngressPolicy.ForwardingFamily family) {
        return new IngressPolicy(
                IngressPolicy.Mode.PROXIED, family, List.of("10.0.0.0/8", "2001:db8:ffff::/48"));
    }

    private static OriginRequestInput input(
            String peer, List<String> forwarded, List<String> xForwarded) {
        return new OriginRequestInput(peer, forwarded, xForwarded);
    }

    private static byte[] expected(String address) {
        byte[] parsed = NumericOriginAddress.parse(address);
        try {
            byte[] expected = new byte[parsed.length + 1];
            expected[0] = parsed.length == 4 ? (byte) 4 : (byte) 6;
            System.arraycopy(parsed, 0, expected, 1, parsed.length);
            return expected;
        } finally {
            Arrays.fill(parsed, (byte) 0);
        }
    }

    private void sameOrigin(IngressPolicy policy, OriginRequestInput input, String address) {
        byte[] actual = resolver.resolve(policy, input).bytes();
        byte[] expected = expected(address);
        try {
            assertThat(MessageDigest.isEqual(actual, expected)).isTrue();
        } finally {
            Arrays.fill(actual, (byte) 0);
            Arrays.fill(expected, (byte) 0);
        }
    }

    private void failure(
            OriginFailure.Category category, IngressPolicy policy, OriginRequestInput input) {
        OriginFailure error =
                org.assertj.core.api.Assertions.catchThrowableOfType(
                        () -> resolver.resolve(policy, input), OriginFailure.class);
        assertThat(error).isNotNull();
        assertThat(error.category()).isEqualTo(category);
        assertThat(error.getMessage()).isEqualTo("Trusted-origin resolution failed");
    }
}
