package com.fixhub.platform.identity.internal.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class AccountTest {

    @Test
    void createsPendingAccountWithNormalizedIdentityFields() {
        Account account = Account.create("  First.Last+tag@Example.COM  ", "+96550000001", "en-US");

        assertThat(account.getEmail()).isEqualTo("First.Last+tag@Example.COM");
        assertThat(account.getEmailNormalized()).isEqualTo("first.last+tag@example.com");
        assertThat(account.getPhone()).isEqualTo("+96550000001");
        assertThat(account.getPreferredLocale()).isEqualTo("en-US");
        assertThat(account.getStatus()).isEqualTo(AccountStatus.PENDING_VERIFICATION);
        assertThat(account.getVersion()).isZero();
    }

    @Test
    void supportsAccountWithoutPhone() {
        Account account = Account.create("person@example.com", null, "fr");

        assertThat(account.getPhone()).isNull();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(
            strings = {
                "",
                "   ",
                "missing-at.example.com",
                ".leading@example.com",
                "trailing.@example.com",
                "double..dot@example.com",
                "person@localhost",
                "person@-example.com",
                "person@example-.com",
                "person@exämple.com"
            })
    void rejectsInvalidOrNonAsciiEmail(String email) {
        assertThatThrownBy(() -> Account.create(email, null, "en"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsEmailWithMailboxLongerThanSixtyFourCharacters() {
        String email = "a".repeat(65) + "@example.com";

        assertThatThrownBy(() -> Account.create(email, null, "en"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsEmailWithDomainLongerThanTwoHundredFiftyFiveCharacters() {
        String domain =
                String.join(
                        ".", "a".repeat(63), "b".repeat(63), "c".repeat(63), "d".repeat(63), "e");
        String email = "person@" + domain;

        assertThat(domain).hasSize(257);
        assertThat(email).hasSize(264);
        assertThat(Arrays.stream(domain.split("\\.")).map(String::length))
                .allMatch(length -> length <= 63);

        assertThatThrownBy(() -> Account.create(email, null, "en"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsMaximumSupportedEmailLengths() {
        String localPart = "a".repeat(64);
        String domain =
                String.join(".", "b".repeat(63), "c".repeat(63), "d".repeat(63), "e".repeat(63));
        String email = localPart + "@" + domain;

        assertThat(localPart).hasSize(64);
        assertThat(domain).hasSize(255);
        assertThat(email).hasSize(320);
        assertThat(Account.create(email, null, "en").getEmail()).isEqualTo(email);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "96550000001",
                "+06550000001",
                "+1",
                "+1234567890123456",
                "+965 50000001"
            })
    void rejectsNonCanonicalPhone(String phone) {
        assertThatThrownBy(() -> Account.create("person@example.com", phone, "en"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsE164PhoneWithMaximumFifteenDigits() {
        String phone = "+123456789012345";

        assertThat(phone.substring(1)).hasSize(15);
        assertThat(Account.create("person@example.com", phone, "en").getPhone()).isEqualTo(phone);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", " ", "EN-us", "en_US", "en--US"})
    void rejectsMissingMalformedOrNonNormalizedLocale(String locale) {
        assertThatThrownBy(() -> Account.create("person@example.com", null, locale))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsLocaleLongerThanColumnCapacity() {
        assertThatThrownBy(() -> Account.create("person@example.com", null, "en-" + "a".repeat(33)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsNormalizedLocaleBeyondArabicAndEnglish() {
        Account account = Account.create("person@example.com", null, "zh-Hant-TW");

        assertThat(account.getPreferredLocale()).isEqualTo("zh-Hant-TW");
    }

    @Test
    void acceptsNormalizedLocaleAtColumnCapacity() {
        String locale = "en-Latn-US-x-abcdefgh-abcdefgh-abcd";

        assertThat(locale).hasSize(35);
        assertThat(Account.create("person@example.com", null, locale).getPreferredLocale())
                .isEqualTo(locale);
    }

    @Test
    void changesOnlyApprovedMutableIdentityFields() {
        Account account = Account.create("old@example.com", "+96550000001", "en");

        account.changeEmail(" New+tag@Example.COM ");
        account.changePhone(null);
        account.changePreferredLocale("de-DE");

        assertThat(account.getEmail()).isEqualTo("New+tag@Example.COM");
        assertThat(account.getEmailNormalized()).isEqualTo("new+tag@example.com");
        assertThat(account.getPhone()).isNull();
        assertThat(account.getPreferredLocale()).isEqualTo("de-DE");
        assertThat(account.getStatus()).isEqualTo(AccountStatus.PENDING_VERIFICATION);
    }

    @Test
    void declaresExactlyApprovedStatuses() {
        assertThat(AccountStatus.values())
                .containsExactly(
                        AccountStatus.PENDING_VERIFICATION,
                        AccountStatus.ACTIVE,
                        AccountStatus.SUSPENDED,
                        AccountStatus.DISABLED);
    }

    @Test
    void containsNoProfileOrBusinessState() {
        assertThat(Arrays.stream(Account.class.getDeclaredFields()).map(field -> field.getName()))
                .doesNotContain(
                        "name",
                        "displayName",
                        "avatar",
                        "globalRoles",
                        "memberships",
                        "bookings",
                        "credential");
    }
}
