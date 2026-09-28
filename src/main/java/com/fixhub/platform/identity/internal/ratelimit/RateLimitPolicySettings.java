package com.fixhub.platform.identity.internal.ratelimit;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;

/** Explicit, validated rate-limit policy values. This snapshot has no runtime wiring. */
final class RateLimitPolicySettings {

    private static final Duration MINIMUM_RETENTION = Duration.ofHours(24);
    private static final String INVALID = "Invalid rate-limit policy settings";

    private final Map<RateLimitPolicy, Setting> settings;
    private final Duration retention;

    RateLimitPolicySettings(Map<RateLimitPolicy, Setting> supplied, Duration retention) {
        if (supplied == null || supplied.size() != RateLimitPolicy.values().length) {
            throw invalid();
        }
        if (retention == null || retention.compareTo(MINIMUM_RETENTION) < 0) {
            throw invalid();
        }

        EnumMap<RateLimitPolicy, Setting> copied = new EnumMap<>(RateLimitPolicy.class);
        for (RateLimitPolicy policy : RateLimitPolicy.values()) {
            Setting setting = supplied.get(policy);
            if (setting == null) {
                throw invalid();
            }
            validate(policy, setting);
            copied.put(policy, setting);
        }
        if (copied.size() != supplied.size()) {
            throw invalid();
        }
        this.settings = Map.copyOf(copied);
        this.retention = retention;
    }

    Setting forPolicy(RateLimitPolicy policy) {
        if (policy == null) {
            throw invalid();
        }
        Setting setting = settings.get(policy);
        if (setting == null) {
            throw invalid();
        }
        return setting;
    }

    Duration retention() {
        return retention;
    }

    private static void validate(RateLimitPolicy policy, Setting setting) {
        if (setting.limit <= 0
                || setting.window == null
                || setting.window.isNegative()
                || setting.window.isZero()
                || setting.window.getNano() != 0) {
            throw invalid();
        }
        if (policy == RateLimitPolicy.RESEND_EMAIL) {
            if (setting.cooldown == null
                    || setting.cooldown.isNegative()
                    || setting.cooldown.compareTo(setting.window) > 0) {
                throw invalid();
            }
        } else if (setting.cooldown != null) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(INVALID);
    }

    @Override
    public String toString() {
        return "RateLimitPolicySettings[REDACTED]";
    }

    static final class Setting {

        private final long limit;
        private final Duration window;
        private final Duration cooldown;

        Setting(long limit, Duration window, Duration cooldown) {
            this.limit = limit;
            this.window = window;
            this.cooldown = cooldown;
        }

        long limit() {
            return limit;
        }

        Duration window() {
            return window;
        }

        Duration cooldown() {
            return cooldown;
        }

        @Override
        public String toString() {
            return "Setting[REDACTED]";
        }
    }
}
