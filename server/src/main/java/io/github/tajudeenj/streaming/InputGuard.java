package io.github.tajudeenj.streaming;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * First-line input hygiene: strips control characters and rejects common prompt-injection phrasing.
 * This is a cheap pre-filter, not a complete defence; the system prompt and output handling
 * must still assume user text is hostile.
 */
public final class InputGuard {

    private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}&&[^\\n\\t]]|[\\u200B-\\u200F\\u202A-\\u202E\\u2066-\\u2069]");

    private static final List<Pattern> INJECTION = List.of(
            Pattern.compile("\\b(ignore|disregard|forget|override)\\b.{0,40}\\b(previous|prior|above|all|earlier|system)\\b.{0,20}\\b(instructions?|rules?|prompts?|messages?)\\b"),
            Pattern.compile("\\b(reveal|print|show|repeat|leak)\\b.{0,30}\\b(system|hidden|initial|developer)\\b.{0,15}\\b(prompt|instructions?|message)\\b"),
            Pattern.compile("\\byou are (now|no longer)\\b"),
            Pattern.compile("\\b(developer|jailbreak|dan|god) mode\\b"),
            Pattern.compile("<\\s*/?\\s*(system|assistant|im_start|im_end)\\s*>"));

    private InputGuard() {}

    public static String sanitize(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFKC);
        return CONTROL.matcher(n).replaceAll("").strip();
    }

    public static boolean looksLikeInjection(String sanitized) {
        String lower = sanitized.toLowerCase(Locale.ROOT);
        for (Pattern p : INJECTION) if (p.matcher(lower).find()) return true;
        return false;
    }
}
