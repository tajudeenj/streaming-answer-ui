package io.github.tajudeenj.streaming;

/** A failure on the model-provider side, carrying a message that is safe to show to end users. */
public final class UpstreamException extends Exception {
    private static final long serialVersionUID = 1L;
    private final String publicMessage;

    public UpstreamException(String publicMessage, String detail, Throwable cause) {
        super(detail, cause);
        this.publicMessage = publicMessage;
    }

    public String publicMessage() { return publicMessage; }
}
