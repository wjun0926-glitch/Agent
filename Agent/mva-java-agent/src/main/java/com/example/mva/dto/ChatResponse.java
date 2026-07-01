package com.example.mva.dto;

import java.util.List;

/**
 * Response payload returned from POST /api/chat.
 */
public record ChatResponse(
        String sessionId,
        String reply,
        boolean truncated,
        List<String> traceLog
) {

    public static ChatResponse ok(String sessionId, String reply, List<String> traceLog) {
        return new ChatResponse(sessionId, reply, false, traceLog);
    }

    public static ChatResponse truncated(String sessionId, String reply, List<String> traceLog) {
        return new ChatResponse(sessionId, reply, true, traceLog);
    }
}
