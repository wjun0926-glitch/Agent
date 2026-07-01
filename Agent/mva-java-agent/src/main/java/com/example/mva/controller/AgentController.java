package com.example.mva.controller;

import com.example.mva.agent.AgentRuntime;
import com.example.mva.agent.SessionManager;
import com.example.mva.dto.ChatRequest;
import com.example.mva.dto.ChatResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * REST API for multi-session agent interaction.
 * <p>
 * All endpoints are prefixed with {@code /api}.
 */
@RestController
@RequestMapping("/api")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final AgentRuntime agentRuntime;
    private final SessionManager sessionManager;

    public AgentController(AgentRuntime agentRuntime, SessionManager sessionManager) {
        this.agentRuntime = agentRuntime;
        this.sessionManager = sessionManager;
    }

    /**
     * Send a message to the agent in a given session.
     * <p>
     * If the session does not exist yet it will be created automatically.
     */
    @PostMapping(value = "/chat", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest req) {
        if (req.sessionId() == null || req.sessionId().isBlank()) {
            return ResponseEntity.badRequest().body(
                    ChatResponse.ok("", "sessionId 不能为空", List.of()));
        }
        if (req.message() == null || req.message().isBlank()) {
            return ResponseEntity.badRequest().body(
                    ChatResponse.ok(req.sessionId(), "消息不能为空", List.of()));
        }

        log.info("[API] POST /api/chat  session={} message={}", req.sessionId(), req.message());
        ChatResponse resp = agentRuntime.processMessage(req.sessionId(), req.message());
        log.info("[API] 响应 session={} truncated={} replyLen={}",
                resp.sessionId(), resp.truncated(), resp.reply().length());
        return ResponseEntity.ok(resp);
    }

    /**
     * List all active session IDs.
     */
    @GetMapping(value = "/sessions", produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Set<String>> listSessions() {
        return Map.of("sessions", sessionManager.getActiveSessions());
    }

    /**
     * Delete a specific session and its history.
     */
    @DeleteMapping("/sessions/{sessionId}")
    public Map<String, String> deleteSession(@PathVariable String sessionId) {
        sessionManager.removeSession(sessionId);
        return Map.of("status", "ok", "sessionId", sessionId);
    }

    /**
     * Clear all sessions.
     */
    @DeleteMapping("/sessions")
    public Map<String, String> clearAllSessions() {
        sessionManager.clearAll();
        return Map.of("status", "ok");
    }
}
