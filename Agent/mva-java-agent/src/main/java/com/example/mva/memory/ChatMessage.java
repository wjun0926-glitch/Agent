package com.example.mva.memory;

import java.util.List;

/**
 * Immutable representation of a single turn in the agent conversation.
 * <p>
 * The {@code role} field follows the LLM message convention:
 * <ul>
 *   <li><b>system</b> — initial instruction (always preserved through trimming)</li>
 *   <li><b>user</b> — human input</li>
 *   <li><b>assistant</b> — model output, optionally containing tool calls</li>
 *   <li><b>tool</b> — result of a tool execution, linked via {@code toolCallId}</li>
 * </ul>
 */
public record ChatMessage(
        Role role,
        String content,
        List<ToolCall> toolCalls,
        String toolCallId
) {

    public enum Role { system, user, assistant, tool }

    /** A function call requested by the model. */
    public record ToolCall(
            String id,
            String type,
            String functionName,
            String functionArguments
    ) {
        public ToolCall {
            type = (type == null) ? "function" : type;
        }
    }

    /* ---------- static factories ---------- */

    public static ChatMessage system(String content) {
        return new ChatMessage(Role.system, content, null, null);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(Role.user, content, null, null);
    }

    public static ChatMessage assistant(String content, List<ToolCall> toolCalls) {
        return new ChatMessage(Role.assistant, content, toolCalls, null);
    }

    public static ChatMessage tool(String toolCallId, String content) {
        return new ChatMessage(Role.tool, content, null, toolCallId);
    }

    /* ---------- helpers ---------- */

    public boolean isSystem()   { return role == Role.system; }
    public boolean isUser()     { return role == Role.user; }
    public boolean isAssistant(){ return role == Role.assistant; }
    public boolean isTool()     { return role == Role.tool; }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    @Override
    public String toString() {
        return "[%s] %s".formatted(role, switch (role) {
            case system    -> "[SYSTEM] " + truncate(content, 80);
            case user      -> content;
            case assistant -> hasToolCalls()
                    ? "Thought: " + truncate(content, 120) + " → Tools: " + toolCalls
                    : content;
            case tool      -> "[ToolResult id=%s] %s".formatted(toolCallId, truncate(content, 120));
        });
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
