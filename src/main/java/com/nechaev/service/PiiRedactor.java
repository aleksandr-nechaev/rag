package com.nechaev.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Regex-based output-side PII filter. Catches email, plausible phone numbers, and US/UK-style
// street addresses, redacting before the response leaves the service. Defense-in-depth alongside
// the system prompt instruction in prompts/system-vN.txt — neither layer alone is bulletproof.
//
// Known limitations:
//   - Phone matcher accepts 10–15 digits with separators and will false-positive on long ID-like
//     sequences (ISBNs, etc.).
//   - Address heuristic only catches "<number> <Capitalized word(s)> <street suffix>" English
//     forms; Cyrillic/lowercased/PO-box addresses pass through untouched.
// Both are acceptable trade-offs for a pet project; tighten via NER (Presidio/etc.) when needed.
@Component
public class PiiRedactor {

    private static final Pattern EMAIL = Pattern.compile(
            "\\b[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}\\b");
    private static final Pattern PHONE_CANDIDATE = Pattern.compile(
            "\\+?[\\d\\s\\-\\(\\)\\.]{10,20}");
    private static final Pattern STREET_ADDRESS = Pattern.compile(
            "\\b\\d{1,5}\\s+[A-Z][a-zA-Z]+(?:\\s+[A-Z][a-zA-Z]+)?\\s+"
                    + "(Street|St|Avenue|Ave|Road|Rd|Boulevard|Blvd|Lane|Ln|Drive|Dr"
                    + "|Court|Ct|Place|Pl|Square|Sq|Highway|Hwy|Parkway|Pkwy)\\.?\\b");

    private static final int PHONE_MIN_DIGITS = 10;
    private static final int PHONE_MAX_DIGITS = 15;
    private static final String EMAIL_REPLACEMENT = "[redacted-email]";
    private static final String PHONE_REPLACEMENT = "[redacted-phone]";
    private static final String ADDRESS_REPLACEMENT = "[redacted-address]";

    private record Rule(Pattern pattern, Counter counter, String replacement, Predicate<String> isPii) {}

    private final List<Rule> rules;

    public PiiRedactor(MeterRegistry registry) {
        // Ordering: email → address → phone. Address before phone removes house numbers and zip-like
        // sequences that would otherwise trigger the phone candidate pattern (10–20 chars of digits/
        // separators).
        this.rules = List.of(
                new Rule(EMAIL, counter(registry, "email"), EMAIL_REPLACEMENT, _ -> true),
                new Rule(STREET_ADDRESS, counter(registry, "address"), ADDRESS_REPLACEMENT, _ -> true),
                new Rule(PHONE_CANDIDATE, counter(registry, "phone"), PHONE_REPLACEMENT, PiiRedactor::isPlausiblePhone)
        );
    }

    public String redact(String text) {
        if (text == null || text.isEmpty()) return text;
        String result = text;
        for (Rule rule : rules) {
            result = redactWith(result, rule);
        }
        return result;
    }

    // Lazy-SB invariant: we only allocate a StringBuilder once a real PII match is found.
    // Skipping appendReplacement on a non-PII candidate is safe because the matcher's internal
    // lastAppendPosition is NOT advanced unless appendReplacement is called. Any candidate skipped
    // before the first PII match is therefore copied verbatim by the next appendReplacement (it
    // copies everything from lastAppendPosition to the start of the new match) or by appendTail.
    private static String redactWith(String text, Rule rule) {
        Matcher m = rule.pattern().matcher(text);
        StringBuilder sb = null;
        int count = 0;
        while (m.find()) {
            String candidate = m.group();
            if (rule.isPii().test(candidate)) {
                if (sb == null) sb = new StringBuilder(text.length());
                m.appendReplacement(sb, Matcher.quoteReplacement(rule.replacement()));
                count++;
            } else if (sb != null) {
                m.appendReplacement(sb, Matcher.quoteReplacement(candidate));
            }
        }
        if (sb == null) return text;
        m.appendTail(sb);
        rule.counter().increment(count);
        return sb.toString();
    }

    private static Counter counter(MeterRegistry registry, String type) {
        return Counter.builder("ai.pii.redactions").tag("type", type).register(registry);
    }

    private static boolean isPlausiblePhone(String candidate) {
        long digits = candidate.chars().filter(Character::isDigit).count();
        return digits >= PHONE_MIN_DIGITS && digits <= PHONE_MAX_DIGITS;
    }
}
