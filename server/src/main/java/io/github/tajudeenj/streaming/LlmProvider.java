package io.github.tajudeenj.streaming;

import java.io.IOException;
import java.util.List;

/** Streams a model completion fragment by fragment. Implementations must stop promptly when the sink throws. */
public interface LlmProvider {
    String model();

    void stream(List<ChatMessage> messages, TokenSink sink) throws IOException, UpstreamException, InterruptedException;
}
