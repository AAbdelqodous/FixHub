package com.fixhub.platform.identity.internal.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class IngressPolicyTest {

    @Test
    void directRejectsEveryProxiedOnlyField() {
        assertThat(new IngressPolicy(IngressPolicy.Mode.DIRECT, null, null).mode())
                .isEqualTo(IngressPolicy.Mode.DIRECT);
        invalid(null, null, null);
        invalid(IngressPolicy.Mode.DIRECT, IngressPolicy.ForwardingFamily.FORWARDED, null);
        invalid(IngressPolicy.Mode.DIRECT, null, List.of());
        invalid(
                IngressPolicy.Mode.DIRECT,
                IngressPolicy.ForwardingFamily.X_FORWARDED,
                List.of("192.0.2.0/24"));
    }

    @Test
    void proxiedRequiresFamilyAndOneThroughSixtyFourNetworks() {
        invalid(IngressPolicy.Mode.PROXIED, null, List.of("192.0.2.0/24"));
        invalid(IngressPolicy.Mode.PROXIED, IngressPolicy.ForwardingFamily.FORWARDED, null);
        invalid(IngressPolicy.Mode.PROXIED, IngressPolicy.ForwardingFamily.FORWARDED, List.of());
        assertThat(
                        new IngressPolicy(
                                        IngressPolicy.Mode.PROXIED,
                                        IngressPolicy.ForwardingFamily.FORWARDED,
                                        List.of("192.0.2.0/24"))
                                .family())
                .isEqualTo(IngressPolicy.ForwardingFamily.FORWARDED);
        assertThat(
                        new IngressPolicy(
                                        IngressPolicy.Mode.PROXIED,
                                        IngressPolicy.ForwardingFamily.X_FORWARDED,
                                        List.of("2001:db8::/32"))
                                .family())
                .isEqualTo(IngressPolicy.ForwardingFamily.X_FORWARDED);
        List<String> sixtyFour = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            sixtyFour.add("198.51." + i + ".0/24");
        }
        new IngressPolicy(
                IngressPolicy.Mode.PROXIED, IngressPolicy.ForwardingFamily.FORWARDED, sixtyFour);
        sixtyFour.add("203.0.113.0/24");
        invalid(IngressPolicy.Mode.PROXIED, IngressPolicy.ForwardingFamily.FORWARDED, sixtyFour);
    }

    @Test
    void cidrsRequireCanonicalNumericNonoverlappingNetworks() {
        for (String text :
                new String[] {
                    "192.0.2.1/24",
                    "192.0.2.0/33",
                    "192.0.2.0/",
                    "192.0.2.0",
                    "192.0.2.0/0",
                    "::/0",
                    "::/128",
                    "0.0.0.0/32",
                    "224.0.0.0/4",
                    "192.0.0.0/2",
                    "ff00::/8",
                    "fe00::/7",
                    "::ffff:192.0.2.0/120",
                    "example.test/24",
                    "192.0.2.0 /24",
                    "[2001:db8::]/32",
                    "2001:db8::%zone/32",
                    "192.0.2.0/-1",
                    "192.0.2.0/999",
                    "2001:db8::/129",
                    "2001:db8::1/32",
                    "192.0.2.0/24?x",
                    "a".repeat(65)
                }) {
            invalid(
                    IngressPolicy.Mode.PROXIED,
                    IngressPolicy.ForwardingFamily.FORWARDED,
                    List.of(text));
        }
        invalid(
                IngressPolicy.Mode.PROXIED,
                IngressPolicy.ForwardingFamily.FORWARDED,
                Arrays.asList((String) null));
        invalid(
                IngressPolicy.Mode.PROXIED,
                IngressPolicy.ForwardingFamily.FORWARDED,
                List.of("192.0.2.0/24", "192.0.2.0/25"));
        invalid(
                IngressPolicy.Mode.PROXIED,
                IngressPolicy.ForwardingFamily.FORWARDED,
                List.of("2001:db8::/32", "2001:0db8:0:0::/32"));
    }

    @Test
    void cidrMatchingIsBinaryFamilyAwareAndPolicyOwnsInput() {
        List<String> input = new ArrayList<>(List.of("10.0.0.0/8", "2001:db8::/32"));
        IngressPolicy policy =
                new IngressPolicy(
                        IngressPolicy.Mode.PROXIED,
                        IngressPolicy.ForwardingFamily.X_FORWARDED,
                        input);
        input.clear();
        byte[] ipv4 = NumericOriginAddress.parse("10.1.2.3");
        byte[] ipv6 = NumericOriginAddress.parse("2001:db8::1");
        byte[] other = NumericOriginAddress.parse("203.0.113.1");
        byte[] compatible = NumericOriginAddress.parse("::a01:203");
        try {
            assertThat(policy.trusts(ipv4)).isTrue();
            assertThat(policy.trusts(ipv6)).isTrue();
            assertThat(policy.trusts(other)).isFalse();
            assertThat(policy.trusts(compatible)).isFalse();
            assertThat(policy.toString()).isEqualTo("IngressPolicy[REDACTED]");
        } finally {
            Arrays.fill(ipv4, (byte) 0);
            Arrays.fill(ipv6, (byte) 0);
            Arrays.fill(other, (byte) 0);
            Arrays.fill(compatible, (byte) 0);
        }
        for (String allowed :
                new String[] {
                    "127.0.0.0/8", "169.254.0.0/16", "192.0.2.0/24", "8.8.8.0/24", "fc00::/7"
                }) {
            new IngressPolicy(
                    IngressPolicy.Mode.PROXIED,
                    IngressPolicy.ForwardingFamily.FORWARDED,
                    List.of(allowed));
        }
        new IngressPolicy(
                IngressPolicy.Mode.PROXIED,
                IngressPolicy.ForwardingFamily.FORWARDED,
                List.of("192.0.2.0/24", "2001:db8::/32"));
    }

    @Test
    void canonicalResultRejectsBadShapesAndDefensivelyOwnsBytes() {
        byte[] source = {4, (byte) 192, 0, 2, 1};
        CanonicalOrigin origin = new CanonicalOrigin(source);
        source[1] = 0;
        byte[] first = origin.bytes();
        byte[] expected = {4, (byte) 192, 0, 2, 1};
        try {
            assertThat(MessageDigest.isEqual(first, expected)).isTrue();
            first[1] = 0;
            byte[] second = origin.bytes();
            try {
                assertThat(MessageDigest.isEqual(second, expected)).isTrue();
            } finally {
                Arrays.fill(second, (byte) 0);
            }
        } finally {
            Arrays.fill(first, (byte) 0);
            Arrays.fill(expected, (byte) 0);
            Arrays.fill(source, (byte) 0);
        }
        assertThat(origin.toString()).isEqualTo("CanonicalOrigin[REDACTED]");
        for (byte[] invalid :
                new byte[][] {{}, {4}, {6, 1, 2, 3, 4}, {7, 1, 2, 3, 4}, new byte[17]}) {
            assertThatThrownBy(() -> new CanonicalOrigin(invalid))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Invalid canonical origin");
        }
        byte[] mapped = new byte[17];
        mapped[0] = 6;
        mapped[11] = (byte) 0xff;
        mapped[12] = (byte) 0xff;
        assertThatThrownBy(() -> new CanonicalOrigin(mapped))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid canonical origin");
        Arrays.fill(mapped, (byte) 0);
    }

    private static void invalid(
            IngressPolicy.Mode mode, IngressPolicy.ForwardingFamily family, List<String> cidrs) {
        assertThatThrownBy(() -> new IngressPolicy(mode, family, cidrs))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid ingress policy");
    }
}
