package com.example.mva;

import com.example.mva.tool.CalculatorTool;
import com.example.mva.tool.FetchPageTool;
import com.example.mva.tool.SearchTool;
import com.example.mva.tool.TodoManagerTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the MVA application.
 * <p>
 * Tests verify tool correctness and API wiring.
 * Full ReAct tests require a real LLM API key.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MvaApplicationTests {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private CalculatorTool calculator;

    @Autowired
    private SearchTool search;

    @Autowired
    private FetchPageTool fetchPage;

    @Autowired
    private TodoManagerTool todoManager;

    /* ================================================================
     *  Calculator tests
     * ================================================================ */

    @Test
    @DisplayName("Calculator: basic arithmetic")
    void calculatorBasic() {
        assertThat(calculator.execute("{\"expression\":\"123*456\"}", "test"))
                .isEqualTo("56088");
    }

    @Test
    @DisplayName("Calculator: addition")
    void calculatorAddition() {
        assertThat(calculator.execute("{\"expression\":\"56088+2000\"}", "test"))
                .isEqualTo("58088");
    }

    @Test
    @DisplayName("Calculator: parentheses and decimals")
    void calculatorComplex() {
        assertThat(calculator.execute("{\"expression\":\"(1.5+2.5)*3\"}", "test"))
                .isEqualTo("12");
    }

    @Test
    @DisplayName("Calculator: division")
    void calculatorDivision() {
        assertThat(calculator.execute("{\"expression\":\"100/4\"}", "test"))
                .isEqualTo("25");
    }

    @Test
    @DisplayName("Calculator: handles Chinese operators")
    void calculatorChineseOperators() {
        assertThat(calculator.execute("{\"expression\":\"3乘以4加5\"}", "test"))
                .isEqualTo("17");
    }

    /* ================================================================
     *  Search tests（真正的联网搜索 / 降级模拟数据）
     * ================================================================ */

    @Test
    @DisplayName("Search: weather query")
    void searchWeather() {
        String result = search.execute("{\"query\":\"今天天气怎么样？\"}", "test");
        // 无论是真实搜索结果还是降级模拟数据，都应返回非空且有实质内容
        assertThat(result)
                .isNotBlank()
                .containsAnyOf("天气", "weather", "预报", "°C", "温度");
    }

    @Test
    @DisplayName("Search: news query")
    void searchNews() {
        String result = search.execute("{\"query\":\"今日新闻\"}", "test");
        // 应该返回新闻相关内容（真实或模拟）
        assertThat(result).containsAnyOf("新闻", "news", "消息");
    }

    @Test
    @DisplayName("Search: general query returns useful content")
    void searchGeneral() {
        String result = search.execute("{\"query\":\"Java 21\"}", "test");
        // 任何搜索结果都不应为空
        assertThat(result).isNotBlank();
    }

    @Test
    @DisplayName("Search: empty query returns error")
    void searchEmptyQuery() {
        String result = search.execute("{\"query\":\"\"}", "test");
        assertThat(result).contains("请输入");
    }

    /* ================================================================
     *  FetchPage tests
     * ================================================================ */

    @Test
    @DisplayName("FetchPage: valid URL returns content")
    void fetchPageValidUrl() {
        // 抓取一个稳定的公共页面
        String result = fetchPage.execute("{\"url\":\"https://example.com\"}", "test");
        assertThat(result).contains("Example");  // example.com 页面包含 "Example Domain"
    }

    @Test
    @DisplayName("FetchPage: empty URL returns error")
    void fetchPageEmptyUrl() {
        String result = fetchPage.execute("{\"url\":\"\"}", "test");
        assertThat(result).contains("请输入");
    }

    @Test
    @DisplayName("FetchPage: invalid URL returns error")
    void fetchPageInvalidUrl() {
        String result = fetchPage.execute("{\"url\":\"not-a-valid-url\"}", "test");
        assertThat(result).satisfiesAnyOf(
                r -> assertThat(r).contains("出错"),
                r -> assertThat(r).contains("超时"),
                r -> assertThat(r).contains("Failed")
        );
    }

    /* ================================================================
     *  TodoManager tests
     * ================================================================ */

    @Test
    @DisplayName("TodoManager: add and list")
    void todoAddAndList() {
        String sid = "test-todo-" + System.currentTimeMillis();

        // Add a todo
        String addResult = todoManager.execute(
                "{\"action\":\"add\",\"item\":\"测试待办事项\"}", sid);
        assertThat(addResult).contains("已添加");

        // List todos
        String listResult = todoManager.execute(
                "{\"action\":\"list\"}", sid);
        assertThat(listResult).contains("测试待办事项");
    }

    /* ================================================================
     *  API endpoint tests
     * ================================================================ */

    @Test
    @DisplayName("GET /api/sessions returns session list")
    void listSessions() {
        ResponseEntity<Map> resp = rest.getForEntity("/api/sessions", Map.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody()).containsKey("sessions");
    }

    @Test
    @DisplayName("POST /api/chat with missing sessionId returns 400")
    void chatMissingSession() {
        ResponseEntity<Map> resp = rest.postForEntity(
                "/api/chat",
                Map.of("message", "hello"),
                Map.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("DELETE /api/sessions is idempotent")
    void deleteSession() {
        ResponseEntity<Map> resp = rest.exchange(
                "/api/sessions/nonexistent",
                org.springframework.http.HttpMethod.DELETE,
                null,
                Map.class);
        assertThat(resp.getStatusCode().value()).isEqualTo(200);
        assertThat(resp.getBody().get("status")).isEqualTo("ok");
    }
}
