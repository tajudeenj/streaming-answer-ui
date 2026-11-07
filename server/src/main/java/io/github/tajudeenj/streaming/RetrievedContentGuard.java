package io.github.tajudeenj.streaming;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Indirect prompt-injection screen for document content. Text that will be placed in front of the
 * model is attacker-reachable (anyone who can edit a source document), so it gets the same suspicion
 * as user input plus checks for instructions hidden from human reviewers.
 */
public final class RetrievedContentGuard {

    private static final Pattern HIDDEN_MARKUP = Pattern.compile("<!--|-->|<\\s*(script|style|iframe)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern INVISIBLE = Pattern.compile("[\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2064\\u2066-\\u2069\\uFEFF]");
    private static final List<Pattern> ADDRESSED_TO_MODEL = List.of(
            Pattern.compile("\\b(assistant|chatbot|language model|ai model|llm)\\s*[:,]"),
            Pattern.compile("\\bas an? (ai|assistant|language model)\\b"),
            Pattern.compile("\\b(when|if) (you are )?(answering|responding|asked)\\b.{0,40}\\b(say|tell|respond|reply|state)\\b"),
            Pattern.compile("\\b(tell|inform|assure) the (customer|user|client)s? that\\b"));

    private RetrievedContentGuard() {}

    /** @return the reason the text is unsafe to show to a model, or empty when it is clean. */
    public static Optional<String> inspect(String raw) {
        if (HIDDEN_MARKUP.matcher(raw).find()) return Optional.of("hidden markup");
        if (INVISIBLE.matcher(raw).find()) return Optional.of("invisible characters");
        String clean = InputGuard.sanitize(raw);
        if (InputGuard.looksLikeInjection(clean)) return Optional.of("instruction override");
        String lower = clean.toLowerCase(Locale.ROOT);
        for (Pattern p : ADDRESSED_TO_MODEL) {
            if (p.matcher(lower).find()) return Optional.of("text addressed to the model");
        }
        return Optional.empty();
    }
}
