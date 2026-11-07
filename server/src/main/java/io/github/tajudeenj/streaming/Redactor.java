package io.github.tajudeenj.streaming;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks personal and account data before any text leaves the bank boundary for a model provider.
 * Patterns are linear (no nested quantifiers) to avoid catastrophic backtracking.
 */
public final class Redactor {

    public record Result(String text, int count) {}

    private record Rule(String label, Pattern pattern, boolean luhn) {}

    private static final List<Rule> RULES = List.of(
            new Rule("EMIRATES_ID", Pattern.compile("\\b784[- ]?\\d{4}[- ]?\\d{7}[- ]?\\d\\b"), false),
            new Rule("IBAN", Pattern.compile("\\b[A-Z]{2}\\d{2}(?: ?[A-Z0-9]{4}){3,7}(?: ?[A-Z0-9]{1,3})?\\b"), false),
            new Rule("CARD", Pattern.compile("\\b\\d(?:[ -]?\\d){12,18}\\b"), true),
            new Rule("EMAIL", Pattern.compile("\\b[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{1,253}\\.[A-Za-z]{2,24}\\b"), false),
            new Rule("PHONE", Pattern.compile("(?<!\\d)(?:\\+971|00971|0)[ -]?5\\d[ -]?\\d{3}[ -]?\\d{4}(?!\\d)"), false));

    private Redactor() {}

    public static Result redact(String input) {
        String text = input;
        int total = 0;
        for (Rule r : RULES) {
            Matcher m = r.pattern.matcher(text);
            StringBuilder out = new StringBuilder(text.length());
            int n = 0;
            while (m.find()) {
                if (r.luhn && !luhnValid(m.group())) {
                    m.appendReplacement(out, Matcher.quoteReplacement(m.group()));
                    continue;
                }
                m.appendReplacement(out, "[" + r.label + "]");
                n++;
            }
            m.appendTail(out);
            if (n > 0) {
                text = out.toString();
                total += n;
            }
        }
        return new Result(text, total);
    }

    static boolean luhnValid(String raw) {
        int sum = 0;
        boolean dbl = false;
        int digits = 0;
        for (int k = raw.length() - 1; k >= 0; k--) {
            char c = raw.charAt(k);
            if (c < '0' || c > '9') continue;
            int d = c - '0';
            if (dbl) {
                d *= 2;
                if (d > 9) d -= 9;
            }
            sum += d;
            dbl = !dbl;
            digits++;
        }
        return digits >= 13 && sum % 10 == 0;
    }
}
