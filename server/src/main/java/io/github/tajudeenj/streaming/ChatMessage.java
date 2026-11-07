package io.github.tajudeenj.streaming;

/** One turn of conversation sent to the model. Role is always "system", "user" or "assistant". */
public record ChatMessage(String role, String content) {
    public ChatMessage {
        if (!role.equals("system") && !role.equals("user") && !role.equals("assistant")) {
            throw new IllegalArgumentException("invalid role");
        }
    }
}
