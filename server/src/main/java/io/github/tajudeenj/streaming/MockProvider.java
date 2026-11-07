package io.github.tajudeenj.streaming;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * Offline provider for demos and load tests. It behaves like a well-grounded model: it answers only with
 * sentences taken from the retrieved sources, cites each one, and streams at a realistic jittered pace.
 */
public final class MockProvider implements LlmProvider {
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+");
    private static final int MAX_SENTENCES_PER_SOURCE = 3;

    @Override
    public String model() { return "mock"; }

    @Override
    public void stream(List<ChatMessage> messages, List<Source> sources, TokenSink sink)
            throws java.io.IOException, InterruptedException {
        List<String> paragraphs = new ArrayList<>();
        for (int i = 0; i < sources.size() && paragraphs.size() < 3; i++) {
            String[] sentences = SENTENCE_END.split(sources.get(i).chunk().text().replace('\n', ' ').strip());
            StringBuilder p = new StringBuilder();
            for (int s = 0; s < Math.min(MAX_SENTENCES_PER_SOURCE, sentences.length); s++) {
                if (s > 0) p.append(' ');
                p.append(sentences[s]);
            }
            p.append(" [S").append(i + 1).append(']');
            paragraphs.add(p.toString());
        }
        String text = String.join("\n\n", paragraphs);

        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        Thread.sleep(250 + rnd.nextInt(250)); // simulated time-to-first-token
        int k = 0;
        while (k < text.length()) {
            int end = text.indexOf(' ', k + 1);
            end = end < 0 ? text.length() : end;
            sink.accept(text.substring(k, end));
            k = end;
            Thread.sleep(18 + rnd.nextInt(30));
        }
    }
}
