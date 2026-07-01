package com.example.mva.llm;

import com.example.mva.memory.ChatMessage;
import java.util.List;

/**
 * Parsed response from the LLM provider.
 */
public record LlmResponse(
        String content,
        List<ChatMessage.ToolCall> toolCalls,
        String finishReason
) {
    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    public boolean isFinished() {
        return "stop".equals(finishReason) || (content != null && !hasToolCalls());
    }
}
