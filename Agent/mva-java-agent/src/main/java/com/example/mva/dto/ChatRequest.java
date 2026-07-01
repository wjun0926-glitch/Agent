package com.example.mva.dto;

/**
 * Request payload for POST /api/chat.
 */
public record ChatRequest(
        String sessionId,
        String message
) {}
