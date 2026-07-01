package com.example.mva.agent;

import com.example.mva.config.AgentProperties;
import com.example.mva.memory.ContextManager;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages multiple isolated chat sessions, each backed by its own
 * {@link ContextManager}.
 * <p>
 * Sessions are created on first access and evicted explicitly via
 * {@link #removeSession(String)} or {@link #clearAll()}.
 */
@Component
public class SessionManager {

    private static final Logger log = LoggerFactory.getLogger(SessionManager.class);

    private final ConcurrentHashMap<String, ContextManager> sessions = new ConcurrentHashMap<>();
    private final AgentProperties properties;

    /** Shared system prompt for all sessions. */
    public static final String SYSTEM_PROMPT = """
            你是一个智能助手，可以通过调用工具来帮助用户完成任务。

            ## 可用工具

            1. **calculator** — 执行数学计算。传入参数 expression 为数学表达式字符串。
            2. **search** — **真正的互联网搜索**，可查询实时天气、新闻、百科等任何信息。需要实时数据时优先使用此工具。
            3. **fetch_page** — **抓取指定 URL 的网页内容**。当你需要阅读某篇文章、文档或具体页面时使用。
            4. **todo_manager** — 管理待办事项。action=add 添加待办，action=list 列出待办。

            ## 工作方式

            1. 分析用户的问题，判断是否需要调用工具。**如果需要实时信息，优先调用 search 或 fetch_page**。
            2. 如果需要工具，先说出你的推理过程（Thought），然后调用工具。
            3. 如果问题可以拆解为多个步骤，依次调用工具并基于返回结果继续推理。
            4. 所有工具调用完成后，给出完整的最终回答。
            5. 如果不需要工具，直接回答即可。

            请尽可能使用中文回答用户的问题。
            """;

    public SessionManager(AgentProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void logConfig() {
        log.info("[Agent] SessionManager 已初始化 (maxMessages={})", properties.getMaxMessages());
    }

    /** Get or create a context for the given session ID. */
    public ContextManager getOrCreate(String sessionId) {
        return sessions.computeIfAbsent(sessionId, id -> {
            var ctx = new ContextManager(properties.getMaxMessages());
            ctx.setSystemPrompt(SYSTEM_PROMPT);
            log.info("[Session] 创建新会话: {}", id);
            return ctx;
        });
    }

    /** Remove a session and its entire history. */
    public void removeSession(String sessionId) {
        ContextManager removed = sessions.remove(sessionId);
        if (removed != null) {
            log.info("[Session] 删除会话: {} (已移除 {} 条消息)", sessionId, removed.size());
        }
    }

    /** Remove all sessions. */
    public void clearAll() {
        int count = sessions.size();
        sessions.clear();
        log.info("[Session] 已清除全部 {} 个会话", count);
    }

    /** List active session IDs. */
    public Set<String> getActiveSessions() {
        return sessions.keySet();
    }
}
