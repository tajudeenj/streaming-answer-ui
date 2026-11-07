package io.github.tajudeenj.streaming;

import java.io.IOException;

/** Receives streamed text fragments; throws {@link ClientGoneException} when the browser has disconnected. */
@FunctionalInterface
public interface TokenSink {
    void accept(String fragment) throws IOException;
}
