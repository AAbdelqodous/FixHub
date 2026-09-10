package com.fixhub.platform.identity.internal.account;

import com.fixhub.platform.common.jpa.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.IllformedLocaleException;
import java.util.Locale;
import java.util.regex.Pattern;
import lombok.Getter;

@Getter
@Entity
@Table(name = "identity_accounts")
public class Account extends AuditableEntity {

    private static final int MAX_EMAIL_LENGTH = 320;
    private static final int MAX_LOCALE_LENGTH = 35;
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile(
                    "^[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+"
                            + "(?:\\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*"
                            + "@[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
                            + "(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$");
    private static final Pattern E164_PATTERN = Pattern.compile("^\\+[1-9][0-9]{1,14}$");

    @Column(name = "email", nullable = false, length = MAX_EMAIL_LENGTH)
    private String email;

    @Column(name = "email_normalized", nullable = false, length = MAX_EMAIL_LENGTH)
    private String emailNormalized;

    @Column(name = "phone", length = 16)
    private String phone;

    @Column(name = "preferred_locale", nullable = false, length = MAX_LOCALE_LENGTH)
    private String preferredLocale;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private AccountStatus status;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected Account() {}

    private Account(String email, String phone, String preferredLocale) {
        setEmail(email);
        this.phone = requireValidPhone(phone);
        this.preferredLocale = requireNormalizedLocale(preferredLocale);
        this.status = AccountStatus.PENDING_VERIFICATION;
    }

    public static Account create(String email, String phone, String preferredLocale) {
        return new Account(email, phone, preferredLocale);
    }

    public void changeEmail(String email) {
        setEmail(email);
    }

    public void changePhone(String phone) {
        this.phone = requireValidPhone(phone);
    }

    public void changePreferredLocale(String preferredLocale) {
        this.preferredLocale = requireNormalizedLocale(preferredLocale);
    }

    private void setEmail(String email) {
        String trimmedEmail = requireValidEmail(email);
        this.email = trimmedEmail;
        this.emailNormalized = trimmedEmail.toLowerCase(Locale.ROOT);
    }

    private static String requireValidEmail(String email) {
        if (email == null) {
            throw new IllegalArgumentException("Email is required");
        }

        String trimmedEmail = email.trim();
        if (trimmedEmail.isEmpty()
                || trimmedEmail.length() > MAX_EMAIL_LENGTH
                || !isAscii(trimmedEmail)
                || !EMAIL_PATTERN.matcher(trimmedEmail).matches()) {
            throw new IllegalArgumentException("Email must use conventional ASCII syntax");
        }

        int separator = trimmedEmail.lastIndexOf('@');
        if (separator > 64 || trimmedEmail.length() - separator - 1 > 255) {
            throw new IllegalArgumentException("Email must use conventional ASCII syntax");
        }

        return trimmedEmail;
    }

    private static boolean isAscii(String value) {
        return value.chars().allMatch(character -> character <= 0x7f);
    }

    private static String requireValidPhone(String phone) {
        if (phone != null && !E164_PATTERN.matcher(phone).matches()) {
            throw new IllegalArgumentException("Phone must use canonical E.164 format");
        }
        return phone;
    }

    private static String requireNormalizedLocale(String preferredLocale) {
        if (preferredLocale == null
                || preferredLocale.isBlank()
                || preferredLocale.length() > MAX_LOCALE_LENGTH) {
            throw new IllegalArgumentException("Preferred locale is required");
        }

        try {
            String normalized =
                    new Locale.Builder().setLanguageTag(preferredLocale).build().toLanguageTag();
            if (!normalized.equals(preferredLocale)) {
                throw new IllegalArgumentException(
                        "Preferred locale must be a normalized BCP 47 tag");
            }
            return normalized;
        } catch (IllformedLocaleException exception) {
            throw new IllegalArgumentException(
                    "Preferred locale must be a normalized BCP 47 tag", exception);
        }
    }
}
