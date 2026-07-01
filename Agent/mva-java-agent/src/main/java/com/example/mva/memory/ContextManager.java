package com.example.mva.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Sliding-window message buffer that <b>always preserves the System prompt</b>.
 * <p>
 * When the message count exceeds the configured maximum, the oldest
 * non-system messages are evicted first (trimming starts after index 0).
 */
public class ContextManager {

    private static final Logger log = LoggerFactory.getLogger(ContextManager.class);

    private final List<ChatMessage> messages;
    private final int maxMessages;
    private int trimmedCount = 0;

    public ContextManager(int maxMessages) {
        this.maxMessages = Math.max(2, maxMessages);  // at least system + 1
        this.messages = new ArrayList<>();
    }

    /* ---------------------------------------------------------------
     *  Mutators
     * --------------------------------------------------------------- */

    /** Set or replace the system prompt (always kept at index 0). */
    public void setSystemPrompt(String prompt) {
        ChatMessage sys = ChatMessage.system(prompt);
        if (messages.isEmpty()) {
            messages.add(sys);
        } else {
            // Replace existing system message at index 0
            if (messages.getFirst().isSystem()) {
                messages.set(0, sys);
            } else {
                messages.add(0, sys);
            }
        }
    }

    /** Append a message, then trim if over capacity. */
    public void addMessage(ChatMessage msg) {
        messages.add(msg);
        trim();
    }

    /** Reset everything except the system prompt. */
    public void clear() {
        ChatMessage sys = (!messages.isEmpty() && messages.getFirst().isSystem())
                ? messages.getFirst()
                : null;
        messages.clear();
        if (sys != null) messages.add(sys);
        trimmedCount = 0;
    }

    /* ---------------------------------------------------------------
     *  Accessors
     * --------------------------------------------------------------- */

    public List<ChatMessage> getMessages() {
        return Collections.unmodifiableList(messages);
    }

    /** Number of messages that have been evicted so far. */
    public int getTrimmedCount() {
        return trimmedCount;
    }

    public int size() {
        return messages.size();
    }

    /* ---------------------------------------------------------------
     *  Sliding-window trim
     * --------------------------------------------------------------- */

    private void trim() {
        while (messages.size() > maxMessages) {
            // Remove the oldest non-system message (index 0 is always system)
            if (messages.size() > 1 && messages.get(1) != null) {
                messages.remove(1);
                trimmedCount++;
                log.debug("[Context] 滑动窗口截断: 已移除 {} 条历史消息", trimmedCount);
            } else {
                break;
            }
        }
    }

    @Override
    public String toString() {
        return "Context[size=%d/%d, trimmed=%d]".formatted(messages.size(), maxMessages, trimmedCount);
    }
}
