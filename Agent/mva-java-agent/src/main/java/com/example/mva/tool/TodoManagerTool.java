package com.example.mva.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Per-session todo list manager.
 * <p>
 * Supports two actions:
 * <ul>
 *   <li><b>add</b> — appends a new item (requires {@code item})</li>
 *   <li><b>list</b> — returns all items for the current session</li>
 * </ul>
 */
@Component
public class TodoManagerTool extends BaseTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final ConcurrentHashMap<String, List<String>> store = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "todo_manager";
    }

    @Override
    public String description() {
        return "Manage per-session todo items. Supports adding (action=add, item=...) and listing (action=list) todos.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "action", Map.of(
                                "type", "string",
                                "enum", List.of("add", "list"),
                                "description", "Action to perform: 'add' to create a todo, 'list' to view all"
                        ),
                        "item", Map.of(
                                "type", "string",
                                "description", "Todo text (required when action=add)"
                        )
                ),
                "required", List.of("action")
        );
    }

    @Override
    public String execute(String jsonArgs, String sessionId) {
        try {
            JsonNode node = MAPPER.readTree(jsonArgs);
            String action = node.get("action").asText();
            String sid = (sessionId != null) ? sessionId : "default";

            return switch (action) {
                case "add" -> {
                    if (!node.has("item") || node.get("item").asText().isBlank()) {
                        yield "参数错误: action=add 时需要提供 item 参数";
                    }
                    String item = node.get("item").asText();
                    store.computeIfAbsent(sid, k -> Collections.synchronizedList(new ArrayList<>())).add(item);
                    yield "✅ 已添加待办事项: " + item;
                }
                case "list" -> {
                    List<String> items = store.getOrDefault(sid, List.of());
                    if (items.isEmpty()) {
                        yield "📋 当前没有待办事项。";
                    }
                    var sb = new StringBuilder("📋 待办事项列表 (" + items.size() + " 项):\n");
                    for (int i = 0; i < items.size(); i++) {
                        sb.append("  ").append(i + 1).append(". ").append(items.get(i)).append("\n");
                    }
                    yield sb.toString();
                }
                default -> "参数错误: 不支持的 action '" + action + "'，支持 add / list";
            };
        } catch (Exception e) {
            return "待办管理出错: " + e.getMessage();
        }
    }
}
