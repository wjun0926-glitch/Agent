package com.example.mva.agent;

import com.example.mva.config.AgentProperties;
import com.example.mva.dto.ChatResponse;
import com.example.mva.llm.LlmClient;
import com.example.mva.llm.LlmResponse;
import com.example.mva.memory.ChatMessage;
import com.example.mva.memory.ContextManager;
import com.example.mva.tool.ToolRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Hand-written ReAct (Reasoning + Acting) loop — the brain of the agent.
 * <p>
 * For each user message:
 * <ol>
 *   <li>Append the user message to the session context</li>
 *   <li>Send the full context + tool definitions to the LLM</li>
 *   <li>Parse the response:
 *     <ul>
 *       <li>If the LLM replies with text (finish_reason = "stop") → return it</li>
 *       <li>If the LLM issues tool calls → execute locally, append results, loop</li>
 *     </ul>
 *   </li>
 *   <li>Repeat up to {@code maxIterations} times to prevent infinite loops</li>
 * </ol>
 */
@Component
public class AgentRuntime {

    private static final Logger log = LoggerFactory.getLogger(AgentRuntime.class);

    /** 工具结果最大字符数，超出部分截断以防止撑爆上下文 */
    private static final int MAX_TOOL_RESULT_LENGTH = 2000;

    private final LlmClient llmClient;
    private final ToolRegistry toolRegistry;
    private final SessionManager sessionManager;
    private final AgentProperties properties;

    public AgentRuntime(LlmClient llmClient,
                        ToolRegistry toolRegistry,
                        SessionManager sessionManager,
                        AgentProperties properties) {
        this.llmClient = llmClient;
        this.toolRegistry = toolRegistry;
        this.sessionManager = sessionManager;
        this.properties = properties;
    }

    @PostConstruct
    void logConfig() {
        log.info("[Agent] ReAct Runtime 已初始化 (maxIterations={})", properties.getMaxIterations());
    }

    /**
     * Process a single user message within a session.
     *
     * @param sessionId       session identifier
     * @param userMessage     the user's input
     * @return {@link ChatResponse} containing the final answer and a trace log
     */
    public ChatResponse processMessage(String sessionId, String userMessage) {
        ContextManager ctx = sessionManager.getOrCreate(sessionId);
        ctx.addMessage(ChatMessage.user(userMessage));

        List<String> traceLog = new ArrayList<>();
        int iteration = 0;
        int maxIter = properties.getMaxIterations();

        while (iteration < maxIter) {
            iteration++;
            traceLog.add("[迭代 %d/%d] 发送请求至 LLM...".formatted(iteration, maxIter));
            log.info("═══════════════════════════════════════════════");
            log.info("[ReAct] 会话={} 迭代={}/{}", sessionId, iteration, maxIter);
            log.info("═══════════════════════════════════════════════");

            // 1. Call the LLM
            List<ChatMessage> messages = ctx.getMessages();
            var toolDefs = toolRegistry.getToolDefinitions();

            log.debug("[ReAct] 发送 %d 条消息 + %d 个工具定义".formatted(messages.size(), toolDefs.size()));

            LlmResponse llmResp = llmClient.call(messages, toolDefs);

            // 2. Extract thought content
            String thought = llmResp.content();
            if (thought != null && !thought.isBlank()) {
                traceLog.add("[思考] " + thought);
                log.info("[Trace] 模型思考过程:\n{}", thought);
            }

            // 3. Handle errors
            if ("error".equals(llmResp.finishReason())) {
                String errMsg = thought != null ? thought : "LLM 调用返回了错误";
                traceLog.add("[错误] " + errMsg);
                ctx.addMessage(ChatMessage.assistant(errMsg, null));
                return ChatResponse.ok(sessionId, "抱歉，处理时出现错误: " + errMsg, traceLog);
            }

            // 4. Tool calls vs. direct response
            if (llmResp.hasToolCalls()) {
                // Add assistant message with tool calls to context
                ctx.addMessage(ChatMessage.assistant(thought, llmResp.toolCalls()));

                // Execute each tool call
                for (ChatMessage.ToolCall tc : llmResp.toolCalls()) {
                    traceLog.add("[工具调用] " + tc.functionName() + "(" + tc.functionArguments() + ")");
                    log.info("[Trace] 尝试调用工具: {} (args={})", tc.functionName(), tc.functionArguments());

                    String result;
                    try {
                        result = toolRegistry.executeTool(tc.functionName(), tc.functionArguments(), sessionId);
                        traceLog.add("[工具结果] " + truncate(result, 200));
                        log.info("[Trace] 工具执行结果 [{}]: {}", tc.functionName(), truncate(result, 200));
                    } catch (Exception e) {
                        result = "工具执行异常: " + e.getMessage();
                        traceLog.add("[工具异常] " + result);
                        log.warn("[Trace] 工具执行异常 [{}]: {}", tc.functionName(), e.getMessage());
                    }

                    // 截断过大的工具结果，防止撑爆 LLM 上下文
                    String trimmed = result.length() > MAX_TOOL_RESULT_LENGTH
                            ? result.substring(0, MAX_TOOL_RESULT_LENGTH) + "\n\n...(截断: 原始结果 " + result.length() + " 字符)"
                            : result;

                    // Append tool result as observation
                    ctx.addMessage(ChatMessage.tool(tc.id(), trimmed));
                }
                // Continue loop — let the LLM process the observations
            } else {
                // Direct response — done
                String reply = thought != null ? thought : "";
                traceLog.add("[最终回复] " + truncate(reply, 300));
                log.info("[Trace] 最终回复: {}", truncate(reply, 300));
                ctx.addMessage(ChatMessage.assistant(reply, null));
                return ChatResponse.ok(sessionId, reply, traceLog);
            }
        }

        // Max iterations reached
        String msg = "已达最大迭代次数 (%d)，请简化您的请求或拆分提问。".formatted(maxIter);
        traceLog.add("[截断] " + msg);
        log.warn("[ReAct] 会话={} 已达最大迭代次数", sessionId);
        ctx.addMessage(ChatMessage.assistant(msg, null));
        return ChatResponse.truncated(sessionId, msg, traceLog);
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
