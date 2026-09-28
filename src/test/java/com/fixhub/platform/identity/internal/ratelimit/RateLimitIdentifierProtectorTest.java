package com.fixhub.platform.identity.internal.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;

class RateLimitIdentifierProtectorTest {

    private static final Instant DECISION = Instant.parse("2030-02-01T00:00:00Z");
    private static final byte[] SYNTHETIC_KEY = key(0x0b);
    private static final String EMAIL = "synthetic@example.invalid";
    private static final byte[] IPV4 = {(byte) 0x04, (byte) 192, 0, 2, 1};
    private static final String EMAIL_DIGEST =
            "8fe801f7c2a26728bea85b9b5e56c43cde5c279a9e96299bc02fecbdf09df4fa";
    private static final String ORIGIN_DIGEST =
            "1edfc39e332a049965afc40ef2868dbd586eccb315d671aa26a12ad816ead16f";
    private static final String GLOBAL_DIGEST =
            "788b6b52a5129833deef8be9b04254b13abd701bdb81e5c19b5e3692506ad1b1";

    @Test
    void jcaMatchesOfficialHmacSha256Vector() throws Exception {
        byte[] longSyntheticKey = new byte[131];
        Arrays.fill(longSyntheticKey, (byte) 0xaa);
        byte[] message =
                "Test Using Larger Than Block-Size Key - Hash Key First"
                        .getBytes(StandardCharsets.US_ASCII);
        byte[] actualDigest = null;
        byte[] expectedDigest = null;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(longSyntheticKey, "HmacSHA256"));
            actualDigest = mac.doFinal(message);
            expectedDigest =
                    HexFormat.of()
                            .parseHex(
                                    "60e431591ee0b67f0d8a26aacbf5b77f8e0bc6213728c5140546040f0ee37f54");
            assertThat(MessageDigest.isEqual(actualDigest, expectedDigest)).isTrue();
        } finally {
            clear(longSyntheticKey, message, actualDigest, expectedDigest);
        }
    }

    @Test
    void completeLengthPrefixedEmailFrameMatchesIndependentExpectedDigest() throws Exception {
        byte[] first = null;
        byte[] second = null;
        byte[] expectedDigest = null;
        byte[] shaInput = null;
        byte[] shaDigest = null;
        try (RateLimitHmacKeySnapshot snapshot = steady()) {
            RateLimitIdentifierProtector protector = new RateLimitIdentifierProtector(snapshot);
            first =
                    protector
                            .protectEmail(RateLimitPolicy.REGISTRATION_EMAIL, EMAIL, DECISION)
                            .getFirst()
                            .digest();
            second =
                    protector
                            .protectEmail(RateLimitPolicy.REGISTRATION_EMAIL, EMAIL, DECISION)
                            .getFirst()
                            .digest();
            expectedDigest = HexFormat.of().parseHex(EMAIL_DIGEST);
            shaInput = EMAIL.getBytes(StandardCharsets.UTF_8);
            shaDigest = MessageDigest.getInstance("SHA-256").digest(shaInput);
            assertThat(first.length).isEqualTo(32);
            assertThat(MessageDigest.isEqual(first, expectedDigest)).isTrue();
            assertThat(MessageDigest.isEqual(first, second)).isTrue();
            assertThat(MessageDigest.isEqual(first, shaDigest)).isFalse();
        } finally {
            clear(first, second, expectedDigest, shaInput, shaDigest);
        }
    }

    @Test
    void fixedDimensionBytesSeparateTheSamePolicyAndIdentifier() throws Exception {
        byte[] alternateFrame =
                HexFormat.of()
                        .parseHex(
                                "46483031312d524154452d5631"
                                        + "00000012524547495354524154494f4e5f454d41494c"
                                        + "000000064f524947494e"
                                        + "0000001973796e746865746963406578616d706c652e696e76616c6964");
        byte[] alternateDigest = null;
        byte[] emailDigest = null;
        byte[] expectedEmailDigest = null;
        try (RateLimitHmacKeySnapshot snapshot = steady()) {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SYNTHETIC_KEY, "HmacSHA256"));
            alternateDigest = mac.doFinal(alternateFrame);
            emailDigest =
                    new RateLimitIdentifierProtector(snapshot)
                            .protectEmail(RateLimitPolicy.REGISTRATION_EMAIL, EMAIL, DECISION)
                            .getFirst()
                            .digest();
            expectedEmailDigest = HexFormat.of().parseHex(EMAIL_DIGEST);
            assertThat(MessageDigest.isEqual(emailDigest, alternateDigest)).isFalse();
            assertThat(MessageDigest.isEqual(emailDigest, expectedEmailDigest)).isTrue();
        } finally {
            clear(alternateFrame, alternateDigest, emailDigest, expectedEmailDigest);
        }
    }

    @Test
    void exactOriginAndGlobalFramesMatchIndependentExpectedDigests() {
        byte[] origin = null;
        byte[] global = null;
        byte[] expectedOrigin = null;
        byte[] expectedGlobal = null;
        try (RateLimitHmacKeySnapshot snapshot = steady()) {
            RateLimitIdentifierProtector protector = new RateLimitIdentifierProtector(snapshot);
            origin =
                    protector
                            .protectOrigin(RateLimitPolicy.REGISTRATION_ORIGIN, IPV4, DECISION)
                            .getFirst()
                            .digest();
            global =
                    protector
                            .protectGlobal(RateLimitPolicy.FH011_GLOBAL, DECISION)
                            .getFirst()
                            .digest();
            expectedOrigin = HexFormat.of().parseHex(ORIGIN_DIGEST);
            expectedGlobal = HexFormat.of().parseHex(GLOBAL_DIGEST);
            assertThat(MessageDigest.isEqual(origin, expectedOrigin)).isTrue();
            assertThat(MessageDigest.isEqual(global, expectedGlobal)).isTrue();
        } finally {
            clear(origin, global, expectedOrigin, expectedGlobal);
        }
    }

    @Test
    void allApprovedPolicyDimensionPairsWorkAndMismatchesFailSafely() {
        try (RateLimitHmacKeySnapshot snapshot = steady()) {
            RateLimitIdentifierProtector protector = new RateLimitIdentifierProtector(snapshot);
            for (RateLimitPolicy policy : RateLimitPolicy.values()) {
                if (policy == RateLimitPolicy.REGISTRATION_EMAIL
                        || policy == RateLimitPolicy.RESEND_EMAIL) {
                    assertThat(protector.protectEmail(policy, EMAIL, DECISION)).hasSize(1);
                    assertInvalid(() -> protector.protectOrigin(policy, IPV4, DECISION));
                    assertInvalid(() -> protector.protectGlobal(policy, DECISION));
                } else if (policy == RateLimitPolicy.FH011_GLOBAL) {
                    assertThat(protector.protectGlobal(policy, DECISION)).hasSize(1);
                    assertInvalid(() -> protector.protectEmail(policy, EMAIL, DECISION));
                    assertInvalid(() -> protector.protectOrigin(policy, IPV4, DECISION));
                } else {
                    assertThat(protector.protectOrigin(policy, IPV4, DECISION)).hasSize(1);
                    assertInvalid(() -> protector.protectEmail(policy, EMAIL, DECISION));
                    assertInvalid(() -> protector.protectGlobal(policy, DECISION));
                }
            }
            assertInvalid(() -> protector.protectEmail(null, EMAIL, DECISION));
            assertInvalid(() -> protector.protectOrigin(null, IPV4, DECISION));
            assertInvalid(() -> protector.protectGlobal(null, DECISION));
        }
    }

    @Test
    void emailUsesFh010ValidationWithoutSilentNormalization() {
        try (RateLimitHmacKeySnapshot snapshot = steady()) {
            RateLimitIdentifierProtector protector = new RateLimitIdentifierProtector(snapshot);
            assertThat(protector.protectEmail(RateLimitPolicy.RESEND_EMAIL, EMAIL, DECISION))
                    .hasSize(1);
            for (String invalid :
                    List.of(
                            " synthetic@example.invalid",
                            "synthetic@example.invalid ",
                            "Synthetic@example.invalid",
                            "synthetic@Example.invalid",
                            "synthetic@éxample.invalid",
                            "synthetic@invalid",
                            "synthetic..name@example.invalid")) {
                assertInvalid(
                        () ->
                                protector.protectEmail(
                                        RateLimitPolicy.REGISTRATION_EMAIL, invalid, DECISION));
            }
            assertInvalid(
                    () ->
                            protector.protectEmail(
                                    RateLimitPolicy.REGISTRATION_EMAIL, null, DECISION));
        }
    }

    @Test
    void canonicalOriginShapeAndMappedIpv6AreEnforcedWithoutChangingCallerArray() {
        byte[] ipv6 = new byte[17];
        ipv6[0] = 0x06;
        ipv6[1] = 0x20;
        ipv6[2] = 0x01;
        byte[] mapped = new byte[17];
        mapped[0] = 0x06;
        mapped[11] = (byte) 0xff;
        mapped[12] = (byte) 0xff;
        mapped[13] = (byte) 192;
        mapped[15] = 2;
        mapped[16] = 1;
        byte[] caller = IPV4.clone();
        List<byte[]> invalidShapes =
                List.of(
                        new byte[0],
                        new byte[] {0x04},
                        new byte[] {0x06},
                        new byte[] {0x05, 1, 2, 3, 4},
                        new byte[] {0x04, 1, 2, 3},
                        new byte[] {0x04, 1, 2, 3, 4, 5},
                        new byte[16],
                        mapped);
        try (RateLimitHmacKeySnapshot snapshot = steady()) {
            RateLimitIdentifierProtector protector = new RateLimitIdentifierProtector(snapshot);
            assertThat(protector.protectOrigin(RateLimitPolicy.RESEND_ORIGIN, caller, DECISION))
                    .hasSize(1);
            assertThat(Arrays.equals(caller, IPV4)).isTrue();
            assertThat(protector.protectOrigin(RateLimitPolicy.VERIFICATION_ORIGIN, ipv6, DECISION))
                    .hasSize(1);
            for (byte[] invalid : invalidShapes) {
                assertInvalid(
                        () ->
                                protector.protectOrigin(
                                        RateLimitPolicy.REGISTRATION_ORIGIN, invalid, DECISION));
            }
            assertInvalid(
                    () ->
                            protector.protectOrigin(
                                    RateLimitPolicy.REGISTRATION_ORIGIN, null, DECISION));
            assertThat(mapped[11]).isEqualTo((byte) 0xff);
        } finally {
            clear(ipv6, caller);
            for (byte[] invalid : invalidShapes) {
                clear(invalid);
            }
        }
    }

    @Test
    void changingPolicyIdentifierAndKeyChangesProtectedDigest() {
        byte[] baseline = null;
        byte[] otherPolicy = null;
        byte[] otherIdentifier = null;
        byte[] otherKey = null;
        try (RateLimitHmacKeySnapshot snapshot = steady();
                RateLimitHmacKeySnapshot other =
                        RateLimitHmacKeySnapshot.create(
                                ignored -> key(0x33),
                                3,
                                "synthetic-other",
                                null,
                                null,
                                null,
                                null)) {
            RateLimitIdentifierProtector protector = new RateLimitIdentifierProtector(snapshot);
            baseline =
                    protector
                            .protectEmail(RateLimitPolicy.REGISTRATION_EMAIL, EMAIL, DECISION)
                            .getFirst()
                            .digest();
            otherPolicy =
                    protector
                            .protectEmail(RateLimitPolicy.RESEND_EMAIL, EMAIL, DECISION)
                            .getFirst()
                            .digest();
            otherIdentifier =
                    protector
                            .protectEmail(
                                    RateLimitPolicy.REGISTRATION_EMAIL,
                                    "another@example.invalid",
                                    DECISION)
                            .getFirst()
                            .digest();
            otherKey =
                    new RateLimitIdentifierProtector(other)
                            .protectEmail(RateLimitPolicy.REGISTRATION_EMAIL, EMAIL, DECISION)
                            .getFirst()
                            .digest();
            assertThat(MessageDigest.isEqual(baseline, otherPolicy)).isFalse();
            assertThat(MessageDigest.isEqual(baseline, otherIdentifier)).isFalse();
            assertThat(MessageDigest.isEqual(baseline, otherKey)).isFalse();
        } finally {
            clear(baseline, otherPolicy, otherIdentifier, otherKey);
        }
    }

    @Test
    void protectedOutputCopiesDigestAndHasNoGeneratedSecretSurface() {
        byte[] candidate = new byte[32];
        Arrays.fill(candidate, (byte) 0x44);
        byte[] first = null;
        byte[] second = null;
        try {
            RateLimitProtectedKey output = new RateLimitProtectedKey(2, candidate);
            Arrays.fill(candidate, (byte) 0);
            first = output.digest();
            assertThat(first[0]).isEqualTo((byte) 0x44);
            Arrays.fill(first, (byte) 0);
            second = output.digest();
            assertThat(second[0]).isEqualTo((byte) 0x44);
            assertThat(output.version()).isEqualTo(2);
            assertThat(output.toString()).isEqualTo("RateLimitProtectedKey[REDACTED]");
            for (Method method : RateLimitProtectedKey.class.getDeclaredMethods()) {
                assertThat(method.getName())
                        .isNotIn("equals", "hashCode", "getDigest", "getVersion");
            }
            assertThat(Modifier.isPublic(RateLimitProtectedKey.class.getModifiers())).isFalse();
            assertThat(RateLimitProtectedKey.class.isRecord()).isFalse();
            assertThat(RateLimitIdentifierProtector.class.isAnnotationPresent(Component.class))
                    .isFalse();
            assertThat(RateLimitHmacKeySnapshot.class.isAnnotationPresent(Component.class))
                    .isFalse();
        } finally {
            clear(candidate, first, second);
        }
    }

    private static RateLimitHmacKeySnapshot steady() {
        return RateLimitHmacKeySnapshot.create(
                ignored -> SYNTHETIC_KEY.clone(), 1, "synthetic-current", null, null, null, null);
    }

    private static byte[] key(int fill) {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) fill);
        return key;
    }

    private static void assertInvalid(Runnable operation) {
        assertThatThrownBy(operation::run)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid rate-limit HMAC input");
    }

    private static void clear(byte[]... ownedArrays) {
        for (byte[] owned : ownedArrays) {
            if (owned != null) {
                Arrays.fill(owned, (byte) 0);
            }
        }
    }
}
