package com.example.mva.llm;

import com.example.mva.config.AgentProperties;
import com.example.mva.memory.ChatMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

/**
 * Raw HTTP LLM client supporting both OpenAI-compatible and Anthropic APIs.
 * <p>
 * Built exclusively on Java 21 {@link HttpClient} — no third-party SDKs.
 */
@Component
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final AgentProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http;

    public LlmClient(AgentProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    @PostConstruct
    void checkKey() {
        String key = props.getApiKey();
        if (key == null || key.isBlank() || "sk-placeholder".equals(key)) {
            log.warn("""
                    ╔═══════════════════════════════════════════════╗
                    ║  LLM API 密钥未配置!                          ║
                    ║  请设置环境变量 LLM_API_KEY                   ║
                    ║  例如 (PowerShell):                           ║
                    ║    $env:LLM_API_KEY="sk-您的key"              ║
                    ║  例如 (bash):                                ║
                    ║    export LLM_API_KEY="sk-您的key"            ║
                    ║  当前提供者: {}                               ║
                    ║  当前端点: {}                                 ║
                    ║  当前模型: {}                                 ║
                    ╚═══════════════════════════════════════════════╝
                    """.formatted(props.getProvider(), props.getEndpoint(), props.getModel()));
        } else {
            log.info("[LLM] 已配置: provider={} model={} endpoint={}",
                    props.getProvider(), props.getModel(), props.getEndpoint());
        }
    }

    /**
     * Send the full message list (with tool definitions) to the LLM and
     * parse the response into a structured {@link LlmResponse}.
     */
    public LlmResponse call(List<ChatMessage> messages, List<Map<String, Object>> toolDefs) {
        return switch (props.getProvider().toLowerCase()) {
            case "openai" -> callOpenAi(messages, toolDefs);
            case "anthropic" -> callAnthropic(messages, toolDefs);
            default -> throw new IllegalArgumentException("不支持的 LLM 提供者: " + props.getProvider());
        };
    }

    /* ================================================================
     *  OpenAI-compatible  (e.g. OpenAI, Azure, Ollama, vLLM)
     * ================================================================ */

    private LlmResponse callOpenAi(List<ChatMessage> messages, List<Map<String, Object>> toolDefs) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", props.getModel());
            body.put("max_tokens", props.getMaxTokens());
            body.put("temperature", props.getTemperature());

            ArrayNode msgArr = body.putArray("messages");
            for (ChatMessage m : messages) {
                msgArr.add(toOpenAiMessage(m));
            }

            if (toolDefs != null && !toolDefs.isEmpty()) {
                body.set("tools", mapper.valueToTree(toolDefs));
                body.put("tool_choice", "auto");
            }

            String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(body);
            log.debug("[LLM] OpenAI 请求:\n{}", json);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(props.getEndpoint().replaceAll("/+$", "") + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + props.getApiKey())
                    .timeout(TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.error("[LLM] OpenAI API 错误 ({}): {}", resp.statusCode(), resp.body());
                return new LlmResponse("LLM 调用失败: HTTP " + resp.statusCode(), null, "error");
            }

            JsonNode root = mapper.readTree(resp.body());
            JsonNode choice = root.path("choices").get(0);
            if (choice == null) {
                return new LlmResponse("LLM 返回了空响应", null, "error");
            }

            JsonNode msg = choice.path("message");
            String content = msg.path("content").asText(null);
            String finishReason = choice.path("finish_reason").asText("stop");

            // Parse tool calls
            List<ChatMessage.ToolCall> toolCalls = null;
            JsonNode tcArr = msg.path("tool_calls");
            if (tcArr.isArray() && tcArr.size() > 0) {
                toolCalls = new ArrayList<>();
                for (JsonNode tc : tcArr) {
                    toolCalls.add(new ChatMessage.ToolCall(
                            tc.path("id").asText(),
                            tc.path("type").asText("function"),
                            tc.path("function").path("name").asText(),
                            tc.path("function").path("arguments").asText()
                    ));
                }
            }

            log.info("[Trace] OpenAI 响应 — finish_reason={} tool_calls={}", finishReason,
                    toolCalls != null ? toolCalls.size() : 0);
            if (content != null && !content.isBlank()) {
                log.info("[Trace] 模型思考: {}", truncate(content, 300));
            }

            return new LlmResponse(content, toolCalls, finishReason);

        } catch (Exception e) {
            log.error("[LLM] OpenAI 调用异常", e);
            return new LlmResponse("LLM 调用异常: " + e.getMessage(), null, "error");
        }
    }

    private ObjectNode toOpenAiMessage(ChatMessage m) {
        ObjectNode node = mapper.createObjectNode();
        node.put("role", m.role().name());

        switch (m.role()) {
            case tool -> {
                node.put("tool_call_id", m.toolCallId());
                node.put("content", m.content());
            }
            case assistant -> {
                node.put("content", m.content());
                if (m.hasToolCalls()) {
                    ArrayNode tcArr = node.putArray("tool_calls");
                    for (ChatMessage.ToolCall tc : m.toolCalls()) {
                        ObjectNode tcObj = tcArr.addObject();
                        tcObj.put("id", tc.id());
                        tcObj.put("type", tc.type());
                        ObjectNode fn = tcObj.putObject("function");
                        fn.put("name", tc.functionName());
                        fn.put("arguments", tc.functionArguments());
                    }
                }
            }
            default -> node.put("content", m.content() != null ? m.content() : "");
        }
        return node;
    }

    /* ================================================================
     *  Anthropic API
     * ================================================================ */

    private LlmResponse callAnthropic(List<ChatMessage> messages, List<Map<String, Object>> toolDefs) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", props.getModel());
            body.put("max_tokens", props.getMaxTokens());
            body.put("temperature", props.getTemperature());

            // System prompt → top-level "system" field
            String systemText = null;
            ArrayNode msgs = body.putArray("messages");
            for (ChatMessage m : messages) {
                if (m.isSystem()) {
                    systemText = m.content();
                } else if (m.isTool()) {
                    // Anthropic: tool_result goes inside a user message
                    ObjectNode toolResult = mapper.createObjectNode();
                    toolResult.put("type", "tool_result");
                    toolResult.put("tool_use_id", m.toolCallId());
                    toolResult.put("content", m.content());
                    msgs.addObject().put("role", "user").set("content",
                            mapper.createArrayNode().add(toolResult));
                } else {
                    msgs.add(toAnthropicMessage(m));
                }
            }
            if (systemText != null) body.put("system", systemText);

            if (toolDefs != null && !toolDefs.isEmpty()) {
                ArrayNode toolsArr = body.putArray("tools");
                for (Map<String, Object> def : toolDefs) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> fn = (Map<String, Object>) def.get("function");
                    ObjectNode t = toolsArr.addObject();
                    t.put("name", (String) fn.get("name"));
                    t.put("description", (String) fn.get("description"));
                    t.set("input_schema", mapper.valueToTree(fn.get("parameters")));
                }
            }

            log.debug("[LLM] Anthropic 请求:\n{}", mapper.writerWithDefaultPrettyPrinter().writeValueAsString(body));

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(props.getEndpoint().replaceAll("/+$", "") + "/messages"))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", props.getApiKey())
                    .header("anthropic-version", "2023-06-01")
                    .timeout(TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.error("[LLM] Anthropic API 错误 ({}): {}", resp.statusCode(), resp.body());
                return new LlmResponse("LLM 调用失败: HTTP " + resp.statusCode(), null, "error");
            }

            JsonNode root = mapper.readTree(resp.body());
            String stopReason = root.path("stop_reason").asText("end_turn");

            // Parse content blocks
            StringBuilder text = new StringBuilder();
            List<ChatMessage.ToolCall> toolCalls = null;
            JsonNode contentArr = root.path("content");
            if (contentArr.isArray()) {
                for (JsonNode block : contentArr) {
                    String type = block.path("type").asText();
                    switch (type) {
                        case "text" -> text.append(block.path("text").asText());
                        case "tool_use" -> {
                            if (toolCalls == null) toolCalls = new ArrayList<>();
                            toolCalls.add(new ChatMessage.ToolCall(
                                    block.path("id").asText(),
                                    "tool_use",
                                    block.path("name").asText(),
                                    block.path("input").toString()
                            ));
                        }
                    }
                }
            }

            String content = text.isEmpty() ? null : text.toString();
            if (content != null && !content.isBlank()) {
                log.info("[Trace] 模型思考: {}", truncate(content, 300));
            }

            String finishReason = switch (stopReason) {
                case "tool_use" -> "tool_calls";
                case "end_turn" -> "stop";
                case "max_tokens" -> "length";
                default -> stopReason;
            };

            return new LlmResponse(content, toolCalls, finishReason);

        } catch (Exception e) {
            log.error("[LLM] Anthropic 调用异常", e);
            return new LlmResponse("LLM 调用异常: " + e.getMessage(), null, "error");
        }
    }

    private ObjectNode toAnthropicMessage(ChatMessage m) {
        ObjectNode node = mapper.createObjectNode();
        node.put("role", m.isAssistant() ? "assistant" : "user");

        if (m.isAssistant() && m.hasToolCalls()) {
            ArrayNode content = node.putArray("content");
            if (m.content() != null) {
                content.addObject().put("type", "text").put("text", m.content());
            }
            for (ChatMessage.ToolCall tc : m.toolCalls()) {
                ObjectNode tu = content.addObject();
                tu.put("type", "tool_use");
                tu.put("id", tc.id());
                tu.put("name", tc.functionName());
                try {
                    tu.set("input", mapper.readTree(tc.functionArguments()));
                } catch (Exception e) {
                    tu.put("input", tc.functionArguments());
                }
            }
        } else {
            node.put("content", m.content() != null ? m.content() : "");
        }
        return node;
    }

    /* ---------- helpers ---------- */

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
