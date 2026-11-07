package io.github.tajudeenj.streaming;

import java.io.IOException;

/** Signals that the downstream client closed the connection, so upstream work must stop. */
public final class ClientGoneException extends IOException {
    private static final long serialVersionUID = 1L;

    public ClientGoneException(Throwable cause) { super("client disconnected", cause); }
}
