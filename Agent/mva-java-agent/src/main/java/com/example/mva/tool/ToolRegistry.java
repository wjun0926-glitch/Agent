package com.example.mva.tool;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Central registry that discovers {@link BaseTool} beans and exposes them
 * as LLM-compatible JSON tool definitions.
 * <p>
 * Also serves as the dispatch point: {@link #executeTool(String, String, String)}.
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, BaseTool> tools = new ConcurrentHashMap<>();

    public ToolRegistry(List<BaseTool> toolBeans) {
        for (BaseTool t : toolBeans) {
            tools.put(t.name(), t);
        }
    }

    @PostConstruct
    void logTools() {
        log.info("[Agent] 注册工具: {}", tools.keySet());
    }

    /**
     * @return OpenAI-compatible tool definitions array
     */
    public List<Map<String, Object>> getToolDefinitions() {
        return tools.values().stream()
                .map(t -> Map.<String, Object>of(
                        "type", "function",
                        "function", Map.of(
                                "name", t.name(),
                                "description", t.description(),
                                "parameters", t.parametersSchema()
                        )
                ))
                .collect(Collectors.toList());
    }

    /**
     * Look up a tool by name.
     */
    public BaseTool getTool(String name) {
        return tools.get(name);
    }

    /**
     * Dispatch execution.
     *
     * @param name      tool name
     * @param jsonArgs  JSON arguments matching the tool's parameter schema
     * @param sessionId current session (for session-scoped state)
     * @return execution result as a plain string
     */
    public String executeTool(String name, String jsonArgs, String sessionId) {
        BaseTool tool = tools.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("未知工具: " + name + "，可用工具: " + tools.keySet());
        }
        log.info("[Trace] 调用工具: {} args={} session={}", name, jsonArgs, sessionId);
        String result = tool.execute(jsonArgs, sessionId);
        log.info("[Trace] 工具结果 [{}]: {}", name, truncate(result, 200));
        return result;
    }

    private static String truncate(String s, int max) {
        return (s == null || s.length() <= max) ? s : s.substring(0, max) + "...";
    }
}
