package com.fixhub.platform.identity.internal.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class RateLimitPolicySettingsTest {

    private static final Duration DAY = Duration.ofDays(1);
    private static final Duration RETENTION = Duration.ofDays(2);
    private static final String INVALID = "Invalid rate-limit policy settings";

    @Test
    void preservesExplicitDocumentedExamplesForEveryPolicy() {
        RateLimitPolicySettings snapshot = new RateLimitPolicySettings(examples(), RETENTION);

        assertSetting(snapshot, RateLimitPolicy.REGISTRATION_EMAIL, 5, Duration.ofHours(1), null);
        assertSetting(
                snapshot, RateLimitPolicy.REGISTRATION_ORIGIN, 100, Duration.ofHours(1), null);
        assertSetting(snapshot, RateLimitPolicy.RESEND_EMAIL, 5, DAY, Duration.ofMinutes(5));
        assertSetting(snapshot, RateLimitPolicy.RESEND_ORIGIN, 60, Duration.ofHours(1), null);
        assertSetting(
                snapshot, RateLimitPolicy.VERIFICATION_ORIGIN, 60, Duration.ofMinutes(15), null);
        assertSetting(snapshot, RateLimitPolicy.FH011_GLOBAL, 300, Duration.ofMinutes(1), null);
        assertThat(snapshot.retention()).isEqualTo(RETENTION);
    }

    @Test
    void rejectsNullMissingAndPartialSnapshotsWithoutDefaults() {
        rejects(null, RETENTION);
        for (RateLimitPolicy policy : RateLimitPolicy.values()) {
            Map<RateLimitPolicy, RateLimitPolicySettings.Setting> missing = examples();
            missing.remove(policy);
            rejects(missing, RETENTION);

            Map<RateLimitPolicy, RateLimitPolicySettings.Setting> nullValue =
                    new HashMap<>(examples());
            nullValue.put(policy, null);
            rejects(nullValue, RETENTION);
        }
        Map<RateLimitPolicy, RateLimitPolicySettings.Setting> nullKey = new HashMap<>(examples());
        nullKey.put(null, setting(1, Duration.ofSeconds(1), null));
        rejects(nullKey, RETENTION);
        rejects(Map.of(), RETENTION);
    }

    @Test
    void validatesPositiveLimitsWithoutInventingAMaximum() {
        for (long invalid : new long[] {0, -1, Long.MIN_VALUE}) {
            for (RateLimitPolicy policy : RateLimitPolicy.values()) {
                Map<RateLimitPolicy, RateLimitPolicySettings.Setting> values = examples();
                RateLimitPolicySettings.Setting old = values.get(policy);
                values.put(policy, setting(invalid, old.window(), old.cooldown()));
                rejects(values, RETENTION);
            }
        }
        Map<RateLimitPolicy, RateLimitPolicySettings.Setting> values = examples();
        values.put(
                RateLimitPolicy.FH011_GLOBAL, setting(Long.MAX_VALUE, Duration.ofSeconds(1), null));
        assertThat(
                        new RateLimitPolicySettings(values, RETENTION)
                                .forPolicy(RateLimitPolicy.FH011_GLOBAL)
                                .limit())
                .isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void rejectsMissingNonPositiveAndFractionalWindowsForEveryPolicy() {
        for (RateLimitPolicy policy : RateLimitPolicy.values()) {
            for (Duration invalid :
                    new Duration[] {
                        null,
                        Duration.ZERO,
                        Duration.ofSeconds(-1),
                        Duration.ofNanos(1),
                        Duration.ofSeconds(1).plusNanos(1)
                    }) {
                Map<RateLimitPolicy, RateLimitPolicySettings.Setting> values = examples();
                RateLimitPolicySettings.Setting old = values.get(policy);
                values.put(policy, setting(old.limit(), invalid, old.cooldown()));
                rejects(values, RETENTION);
            }
        }
    }

    @Test
    void acceptsLargeWholeSecondWindowWithoutRoundingOrMaximum() {
        Duration large = Duration.ofSeconds(Long.MAX_VALUE);
        Map<RateLimitPolicy, RateLimitPolicySettings.Setting> values = examples();
        values.put(RateLimitPolicy.REGISTRATION_EMAIL, setting(1, large, null));
        assertThat(
                        new RateLimitPolicySettings(values, RETENTION)
                                .forPolicy(RateLimitPolicy.REGISTRATION_EMAIL)
                                .window())
                .isEqualTo(large);
    }

    @Test
    void acceptsZeroFractionalAndExactWindowResendCooldownWithoutChangingValue() {
        for (Duration valid :
                new Duration[] {Duration.ZERO, Duration.ofNanos(1), DAY.minusNanos(1), DAY}) {
            Map<RateLimitPolicy, RateLimitPolicySettings.Setting> values = examples();
            values.put(RateLimitPolicy.RESEND_EMAIL, setting(5, DAY, valid));
            assertThat(
                            new RateLimitPolicySettings(values, RETENTION)
                                    .forPolicy(RateLimitPolicy.RESEND_EMAIL)
                                    .cooldown())
                    .isEqualTo(valid);
        }
    }

    @Test
    void rejectsMissingNegativeAndExcessResendCooldown() {
        for (Duration invalid : new Duration[] {null, Duration.ofNanos(-1), DAY.plusNanos(1)}) {
            Map<RateLimitPolicy, RateLimitPolicySettings.Setting> values = examples();
            values.put(RateLimitPolicy.RESEND_EMAIL, setting(5, DAY, invalid));
            rejects(values, RETENTION);
        }
    }

    @Test
    void rejectsAnyCooldownOnEveryOtherPolicyIncludingZero() {
        for (RateLimitPolicy policy : RateLimitPolicy.values()) {
            if (policy == RateLimitPolicy.RESEND_EMAIL) {
                continue;
            }
            for (Duration invalid : new Duration[] {Duration.ZERO, Duration.ofNanos(1)}) {
                Map<RateLimitPolicy, RateLimitPolicySettings.Setting> values = examples();
                RateLimitPolicySettings.Setting old = values.get(policy);
                values.put(policy, setting(old.limit(), old.window(), invalid));
                rejects(values, RETENTION);
            }
        }
    }

    @Test
    void retentionIsAnExactFractionalCapableOffsetWithFixedTwentyFourHourMinimum() {
        for (Duration valid :
                new Duration[] {
                    DAY, DAY.plusNanos(1), RETENTION, Duration.ofSeconds(Long.MAX_VALUE)
                }) {
            assertThat(new RateLimitPolicySettings(examples(), valid).retention()).isEqualTo(valid);
        }
        for (Duration invalid :
                new Duration[] {null, Duration.ZERO, Duration.ofSeconds(-1), DAY.minusNanos(1)}) {
            rejects(examples(), invalid);
        }
    }

    @Test
    void copiesTheCallerMapAndExposesOnlyImmutableValues() {
        Map<RateLimitPolicy, RateLimitPolicySettings.Setting> values = examples();
        RateLimitPolicySettings snapshot = new RateLimitPolicySettings(values, RETENTION);
        values.clear();
        assertThat(snapshot.forPolicy(RateLimitPolicy.RESEND_EMAIL).cooldown())
                .isEqualTo(Duration.ofMinutes(5));
        rejectsLookup(snapshot, null);
        assertThat(snapshot.toString()).isEqualTo("RateLimitPolicySettings[REDACTED]");
        assertThat(snapshot.forPolicy(RateLimitPolicy.RESEND_EMAIL).toString())
                .isEqualTo("Setting[REDACTED]");
    }

    @Test
    void isPackagePrivateFinalUnwiredAndSafeForConcurrentReads() {
        assertThat(Modifier.isPublic(RateLimitPolicySettings.class.getModifiers())).isFalse();
        assertThat(Modifier.isFinal(RateLimitPolicySettings.class.getModifiers())).isTrue();
        assertThat(RateLimitPolicySettings.class.isRecord()).isFalse();
        assertThat(RateLimitPolicySettings.class.getAnnotations()).isEmpty();
        assertThat(RateLimitPolicySettings.class.getDeclaredMethods())
                .noneMatch(
                        method ->
                                Modifier.isPublic(method.getModifiers())
                                        && !method.getName().equals("toString"))
                .noneMatch(method -> method.getName().startsWith("get"))
                .noneMatch(method -> method.getName().startsWith("set"));
        RateLimitPolicySettings snapshot = new RateLimitPolicySettings(examples(), RETENTION);
        assertThat(
                        IntStream.range(0, 1000)
                                .parallel()
                                .allMatch(
                                        ignored ->
                                                snapshot.forPolicy(RateLimitPolicy.RESEND_EMAIL)
                                                                        .limit()
                                                                == 5
                                                        && snapshot.retention().equals(RETENTION)))
                .isTrue();
    }

    @Test
    void failureMessagesAndRenderedStacksContainNoCallerSettings() {
        Map<RateLimitPolicy, RateLimitPolicySettings.Setting> values = examples();
        values.put(RateLimitPolicy.RESEND_EMAIL, setting(-7, DAY, Duration.ofNanos(91)));
        assertThatThrownBy(() -> new RateLimitPolicySettings(values, RETENTION))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(INVALID)
                .hasNoCause()
                .satisfies(
                        failure -> {
                            assertThat(failure.getLocalizedMessage()).isEqualTo(INVALID);
                            assertThat(failure.toString())
                                    .doesNotContain("-7", "91", "RESEND_EMAIL");
                            assertThat(stack(failure)).doesNotContain("-7", "91", "RESEND_EMAIL");
                        });
    }

    private static void assertSetting(
            RateLimitPolicySettings snapshot,
            RateLimitPolicy policy,
            long limit,
            Duration window,
            Duration cooldown) {
        RateLimitPolicySettings.Setting actual = snapshot.forPolicy(policy);
        assertThat(actual.limit()).isEqualTo(limit);
        assertThat(actual.window()).isEqualTo(window);
        assertThat(actual.cooldown()).isEqualTo(cooldown);
    }

    private static Map<RateLimitPolicy, RateLimitPolicySettings.Setting> examples() {
        Map<RateLimitPolicy, RateLimitPolicySettings.Setting> values =
                new EnumMap<>(RateLimitPolicy.class);
        values.put(RateLimitPolicy.REGISTRATION_EMAIL, setting(5, Duration.ofHours(1), null));
        values.put(RateLimitPolicy.REGISTRATION_ORIGIN, setting(100, Duration.ofHours(1), null));
        values.put(RateLimitPolicy.RESEND_EMAIL, setting(5, DAY, Duration.ofMinutes(5)));
        values.put(RateLimitPolicy.RESEND_ORIGIN, setting(60, Duration.ofHours(1), null));
        values.put(RateLimitPolicy.VERIFICATION_ORIGIN, setting(60, Duration.ofMinutes(15), null));
        values.put(RateLimitPolicy.FH011_GLOBAL, setting(300, Duration.ofMinutes(1), null));
        return values;
    }

    private static RateLimitPolicySettings.Setting setting(
            long limit, Duration window, Duration cooldown) {
        return new RateLimitPolicySettings.Setting(limit, window, cooldown);
    }

    private static void rejects(
            Map<RateLimitPolicy, RateLimitPolicySettings.Setting> values, Duration retention) {
        assertThatThrownBy(() -> new RateLimitPolicySettings(values, retention))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(INVALID)
                .hasNoCause();
    }

    private static void rejectsLookup(RateLimitPolicySettings snapshot, RateLimitPolicy policy) {
        assertThatThrownBy(() -> snapshot.forPolicy(policy))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(INVALID);
    }

    private static String stack(Throwable failure) {
        java.io.StringWriter buffer = new java.io.StringWriter();
        failure.printStackTrace(new java.io.PrintWriter(buffer));
        return buffer.toString();
    }
}
