package io.github.tajudeenj.streaming;

import java.io.IOException;
import java.util.List;

/**
 * Streams a model completion fragment by fragment. Implementations must stop promptly when the sink throws.
 * The retrieved sources are already embedded in the messages; they are passed separately only for
 * providers that need structured access (the offline mock builds an extractive answer from them).
 */
public interface LlmProvider {
    String model();

    void stream(List<ChatMessage> messages, List<Source> sources, TokenSink sink)
            throws IOException, UpstreamException, InterruptedException;
}
