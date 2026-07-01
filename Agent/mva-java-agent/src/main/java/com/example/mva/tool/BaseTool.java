package com.example.mva.tool;

import java.util.Map;

/**
 * Abstract base for every tool the agent can invoke.
 * <p>
 * Subclasses are discovered by {@link ToolRegistry} via Spring scanning
 * and exposed to the LLM as OpenAI / Anthropic function definitions.
 */
public abstract class BaseTool {

    /** Unique tool name (used in LLM tool-call payloads). */
    public abstract String name();

    /** Human-readable description (fed to the LLM for tool selection). */
    public abstract String description();

    /**
     * JSON Schema for the tool's parameters (OpenAI "parameters" format).
     * <pre>
     * {
     *   "type": "object",
     *   "properties": { … },
     *   "required": [ … ]
     * }
     * </pre>
     */
    public abstract Map<String, Object> parametersSchema();

    /**
     * Execute the tool with the given JSON arguments.
     *
     * @param jsonArgs  JSON object string matching {@link #parametersSchema()}
     * @param sessionId the current session identifier (for session-scoped state)
     * @return serialised result message (will be sent back to the LLM as observation)
     */
    public abstract String execute(String jsonArgs, String sessionId);

    /* ---------- convenience ---------- */

    @Override
    public String toString() {
        return "Tool[name=%s]".formatted(name());
    }
}
