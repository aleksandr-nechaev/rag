package com.nechaev.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PiiRedactorTest {

    SimpleMeterRegistry registry;
    PiiRedactor redactor;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        redactor = new PiiRedactor(registry);
    }

    @Test
    void plainTextPassesThroughUnchanged() {
        String text = "Aleksandr has 5 years of Java experience.";
        assertThat(redactor.redact(text)).isEqualTo(text);
        assertThat(counter("email")).isEqualTo(0);
        assertThat(counter("phone")).isEqualTo(0);
    }

    @Test
    void nullInputReturnsNull() {
        assertThat(redactor.redact(null)).isNull();
    }

    @Test
    void emptyInputReturnsEmpty() {
        assertThat(redactor.redact("")).isEqualTo("");
    }

    @Test
    void singleEmailIsRedacted() {
        String result = redactor.redact("Reach me at john.doe@example.com tomorrow.");
        assertThat(result).isEqualTo("Reach me at [redacted-email] tomorrow.");
        assertThat(counter("email")).isEqualTo(1);
    }

    @Test
    void multipleEmailsAreAllRedacted() {
        String result = redactor.redact("Contacts: a@x.com, b@y.org, c@z.io");
        assertThat(result).isEqualTo("Contacts: [redacted-email], [redacted-email], [redacted-email]");
        assertThat(counter("email")).isEqualTo(3);
    }

    @Test
    void internationalPhoneIsRedacted() {
        String result = redactor.redact("Call me at +7 (812) 555-1234 anytime.");
        assertThat(result).contains("[redacted-phone]");
        assertThat(result).doesNotContain("555");
        assertThat(counter("phone")).isEqualTo(1);
    }

    @Test
    void domesticPhoneWithDashesIsRedacted() {
        String result = redactor.redact("Phone: 812-555-1234.");
        assertThat(result).contains("[redacted-phone]");
        assertThat(counter("phone")).isEqualTo(1);
    }

    @Test
    void shortDateLikeStringIsNotRedactedAsPhone() {
        // 2024-01-15 has 8 digits — below PHONE_MIN_DIGITS (10)
        String text = "Started on 2024-01-15 and ended on 2025-06-30.";
        assertThat(redactor.redact(text)).isEqualTo(text);
        assertThat(counter("phone")).isEqualTo(0);
    }

    @Test
    void mixedEmailAndPhoneAreBothRedacted() {
        String result = redactor.redact("Email john@x.com or call +1 555 123 4567.");
        assertThat(result).contains("[redacted-email]");
        assertThat(result).contains("[redacted-phone]");
        assertThat(counter("email")).isEqualTo(1);
        assertThat(counter("phone")).isEqualTo(1);
    }

    @Test
    void invalidEmailLikeStringIsNotRedacted() {
        String text = "Use the @-symbol carefully.";
        assertThat(redactor.redact(text)).isEqualTo(text);
        assertThat(counter("email")).isEqualTo(0);
    }

    @Test
    void streetAddressIsRedacted() {
        String result = redactor.redact("Lives at 123 Main Street, NYC.");
        assertThat(result).isEqualTo("Lives at [redacted-address], NYC.");
        assertThat(counter("address")).isEqualTo(1);
    }

    @Test
    void multiWordStreetAddressIsRedacted() {
        String result = redactor.redact("Office: 1600 Pennsylvania Avenue, Washington.");
        assertThat(result).contains("[redacted-address]");
        assertThat(result).doesNotContain("Pennsylvania");
        assertThat(counter("address")).isEqualTo(1);
    }

    @Test
    void shortSuffixVariantIsRedacted() {
        String result = redactor.redact("Visit 5 Park Ave on Sunday.");
        assertThat(result).contains("[redacted-address]");
        assertThat(counter("address")).isEqualTo(1);
    }

    @Test
    void lowercaseStreetSuffixIsNotRedactedAsAddress() {
        // Heuristic only matches capitalized suffixes — documented limitation.
        String text = "Took a 5 minute drive home.";
        assertThat(redactor.redact(text)).isEqualTo(text);
        assertThat(counter("address")).isEqualTo(0);
    }

    @Test
    void numberedListItemIsNotRedactedAsAddress() {
        String text = "Item 5 is the Pizza Order.";
        assertThat(redactor.redact(text)).isEqualTo(text);
        assertThat(counter("address")).isEqualTo(0);
    }

    private double counter(String type) {
        return registry.counter("ai.pii.redactions", "type", type).count();
    }
}
