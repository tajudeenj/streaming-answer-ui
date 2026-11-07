package io.github.tajudeenj.streaming;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Thread-safe Server-Sent Events writer. Heartbeats and payload events may come from different threads. */
public final class SseWriter implements AutoCloseable {
    private final OutputStream out;
    private final Object lock = new Object();
    private boolean closed;

    public SseWriter(OutputStream out) { this.out = out; }

    public void event(String name, String json) throws ClientGoneException {
        write("event: " + name + "\ndata: " + json + "\n\n");
    }

    public void comment(String text) throws ClientGoneException {
        write(": " + text + "\n\n");
    }

    private void write(String frame) throws ClientGoneException {
        synchronized (lock) {
            if (closed) throw new ClientGoneException(null);
            try {
                out.write(frame.getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (IOException e) {
                closed = true;
                throw new ClientGoneException(e);
            }
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
            try { out.close(); } catch (IOException ignored) { /* client already gone */ }
        }
    }
}
