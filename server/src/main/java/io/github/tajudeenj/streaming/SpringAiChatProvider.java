package io.github.tajudeenj.streaming;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

/**
 * Streams from whichever Spring AI chat model is configured. Closing the blocking stream cancels the
 * upstream subscription, so a browser disconnect stops token generation (and spend) immediately.
 */
public final class SpringAiChatProvider implements LlmProvider {
    private final ChatModel chatModel;
    private final String modelName;
    private final Duration deadline;

    public SpringAiChatProvider(ChatModel chatModel, String modelName, Duration deadline) {
        this.chatModel = chatModel;
        this.modelName = modelName;
        this.deadline = deadline;
    }

    @Override
    public String model() { return modelName; }

    @Override
    public void stream(List<ChatMessage> messages, List<Source> sources, TokenSink sink)
            throws IOException, UpstreamException, InterruptedException {
        List<Message> prompt = new ArrayList<>(messages.size());
        for (ChatMessage m : messages) {
            prompt.add(switch (m.role()) {
                case "system" -> new SystemMessage(m.content());
                case "assistant" -> new AssistantMessage(m.content());
                default -> new UserMessage(m.content());
            });
        }
        try (Stream<ChatResponse> responses = chatModel.stream(new Prompt(prompt)).timeout(deadline).toStream(16)) {
            Iterator<ChatResponse> it = responses.iterator();
            while (it.hasNext()) {
                if (Thread.currentThread().isInterrupted()) throw new ClientGoneException(null);
                Generation g = it.next().getResult();
                if (g == null || g.getOutput() == null) continue;
                String text = g.getOutput().getText();
                if (text != null && !text.isEmpty()) sink.accept(text);
            }
        } catch (ClientGoneException e) {
            throw e;
        } catch (RuntimeException e) {
            if (Thread.currentThread().isInterrupted()) throw new ClientGoneException(e);
            if (hasCause(e, TimeoutException.class)) {
                throw new UpstreamException("The answer took too long and was stopped.", "total timeout", e);
            }
            throw new UpstreamException("The answer service is unavailable right now. Try again shortly.",
                    "upstream failure: " + e.getClass().getSimpleName(), e);
        }
    }

    private static boolean hasCause(Throwable t, Class<? extends Throwable> type) {
        for (Throwable c = t; c != null; c = c.getCause()) if (type.isInstance(c)) return true;
        return false;
    }
}
