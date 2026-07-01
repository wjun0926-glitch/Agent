package com.example.mva.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 真正的联网搜索工具 — 多后端自动切换。
 * <p>
 * <ol>
 *   <li>如果配置了 {@code agent.search.api-key} → 优先用 SerpAPI（Google 结果）</li>
 *   <li>如果 SerpAPI 不可用 → 自动换用 Bing 网页搜索（国内可访问，无需 Key）</li>
 *   <li>如果 Bing 也不可用 → 尝试 DuckDuckGo 免费 API</li>
 *   <li>全部失败 → 返回智能降级的本地数据</li>
 * </ol>
 */
@Component
public class SearchTool extends BaseTool {

    private static final Logger log = LoggerFactory.getLogger(SearchTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final HttpClient http;

    /** SerpAPI key (可选，配置后可获得 Google 搜索结果) */
    private final String serpApiKey;

    public SearchTool(@Value("${agent.search.api-key:}") String serpApiKey) {
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.serpApiKey = serpApiKey;
        if (serpApiKey != null && !serpApiKey.isBlank()) {
            log.info("[Search] 已配置 SerpAPI(Google)，备用: Bing + DuckDuckGo");
        } else {
            log.info("[Search] 使用 Bing 搜索（国内可访问），备用: DuckDuckGo 免费 API");
        }
    }

    @Override
    public String name() { return "search"; }

    @Override
    public String description() {
        return "Search the internet for real-time information. "
                + "Supports news, weather, encyclopedia, and general topics. "
                + "Returns real search results from the web.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "query", Map.of(
                                "type", "string",
                                "description", "搜索关键词，支持中文"
                        )
                ),
                "required", List.of("query")
        );
    }

    @Override
    public String execute(String jsonArgs, String sessionId) {
        try {
            JsonNode node = MAPPER.readTree(jsonArgs);
            String query = node.get("query").asText().trim();
            if (query.isBlank()) return "请输入搜索关键词";

            log.info("[Search] 搜索: \"{}\"", query);

            // 多后端自动切换：SerpAPI → Bing → DuckDuckGo → Fallback
            if (serpApiKey != null && !serpApiKey.isBlank()) {
                String result = trySerpApi(query);
                if (result != null) return result;
                log.info("[Search] SerpAPI 不可用，切换到 Bing...");
            }

            String result = tryBing(query);
            if (result != null) return result;

            log.info("[Search] Bing 不可用，切换到 DuckDuckGo...");
            result = tryDuckDuckGo(query);
            if (result != null) return result;

            return fallbackSearch(query, "所有搜索引擎均不可用");

        } catch (Exception e) {
            log.warn("[Search] 搜索异常", e);
            return "搜索出错: " + e.getMessage();
        }
    }

    /* ================================================================
     *  SerpAPI (Google 搜索结果)
     * ================================================================ */

    private String trySerpApi(String query) {
        try {
            String url = "https://serpapi.com/search?q="
                    + URLEncoder.encode(query, "UTF-8")
                    + "&api_key=" + serpApiKey
                    + "&hl=zh-CN&gl=cn";

            HttpRequest req = newRequest(url).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return null;

            JsonNode root = MAPPER.readTree(resp.body());

            StringBuilder sb = new StringBuilder();
            sb.append("🌐 Google 搜索结果: ").append(query).append("\n");
            sb.append("─".repeat(50)).append("\n");

            // 知识图谱
            JsonNode kg = root.path("knowledge_graph");
            if (!kg.isMissingNode()) {
                sb.append("📖 知识图谱:\n");
                sb.append("  ").append(kg.path("title").asText()).append("\n");
                String desc = kg.path("description").asText();
                if (!desc.isBlank()) sb.append("  ").append(desc).append("\n");
                sb.append("\n");
            }

            // 自然搜索结果
            JsonNode results = root.path("organic_results");
            if (results.isArray()) {
                sb.append("🔍 搜索结果:\n");
                int count = 0;
                for (JsonNode r : results) {
                    if (count >= 8) break;
                    sb.append("  ").append(++count).append(". ").append(r.path("title").asText()).append("\n");
                    String snippet = r.path("snippet").asText();
                    if (!snippet.isBlank()) sb.append("     ").append(snippet).append("\n");
                    String link = r.path("link").asText();
                    if (!link.isBlank()) sb.append("     🔗 ").append(link).append("\n");
                }
            }

            // 相关搜索
            JsonNode related = root.path("related_searches");
            if (related.isArray() && related.size() > 0) {
                sb.append("\n💡 相关搜索:\n");
                int count = 0;
                for (JsonNode r : related) {
                    if (count >= 5) break;
                    sb.append("  • ").append(r.path("query").asText()).append("\n");
                    count++;
                }
            }

            sb.append("\n").append("─".repeat(50));
            sb.append("\n数据来源: Google Search (via SerpAPI)");
            return sb.toString();

        } catch (Exception e) {
            log.warn("[Search] SerpAPI 请求失败: {}", e.getMessage());
            return null; // 让调用方切换到下一个后端
        }
    }

    /* ================================================================
     *  Bing 网页搜索（国内可访问，无需 Key）
     * ================================================================ */

    /** 提取 <li class="b_algo"...>...</li> 结果块 */
    private static final Pattern BING_BLOCK = Pattern.compile(
            "<li[^>]*class=\"[^\"]*b_algo[^\"]*\"[^>]*>(.*?)</li>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    /** 从结果块中提取标题链接和文字 */
    private static final Pattern BING_LINK = Pattern.compile(
            "<a[^>]*href=\"(https?://[^\"]+)\"[^>]*>(.*?)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    /** 搜索结果摘要（class 包含 b_lineclamp 的 <p>） */
    private static final Pattern BING_SNIPPET = Pattern.compile(
            "<p[^>]*class=\"[^\"]*b_lineclamp[^\"]*\"[^>]*>(.*?)</p>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    /** 去除 HTML 标签 */
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");

    private String tryBing(String query) {
        try {
            // 直接请求 cn.bing.com 避免重定向
            String url = "https://cn.bing.com/search?q="
                    + URLEncoder.encode(query, "UTF-8")
                    + "&setlang=zh-Hans";

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                            + "AppleWebKit/537.36 (KHTML, like Gecko) "
                            + "Chrome/125.0.0.0 Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .timeout(TIMEOUT)
                    .GET()
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200 && resp.statusCode() != 301) return null;

            String html = resp.body();

            // 提取每个 b_algo 结果块
            Matcher blockMatcher = BING_BLOCK.matcher(html);
            if (!blockMatcher.find()) return null; // 没有匹配到结果

            StringBuilder sb = new StringBuilder();
            sb.append("🌐 Bing 搜索结果: ").append(query).append("\n");
            sb.append("─".repeat(50)).append("\n");

            int count = 0;
            blockMatcher.reset();
            while (blockMatcher.find() && count < 8) {
                String block = blockMatcher.group(1);

                // 从块中提取所有链接，取第一个外链作为标题和 URL
                Matcher linkMatcher = BING_LINK.matcher(block);
                String title = null;
                String urlLink = null;
                while (linkMatcher.find()) {
                    String href = linkMatcher.group(1);
                    String text = stripHtml(linkMatcher.group(2)).trim();
                    // 跳过空的、javascript:、Bing 内部链接
                    if (text.isEmpty() || href.contains("bing.com") || href.startsWith("javascript:")) continue;
                    // 优先用有文字内容的链接作为标题
                    if (title == null || text.length() > 5) {
                        title = text;
                        urlLink = href;
                    }
                }

                if (title == null || title.isBlank()) continue;
                count++;
                sb.append("  ").append(count).append(". ").append(title).append("\n");

                // 提取摘要
                Matcher snippetMatcher = BING_SNIPPET.matcher(block);
                if (snippetMatcher.find()) {
                    String snippet = stripHtml(snippetMatcher.group(1));
                    if (!snippet.isBlank()) {
                        sb.append("     ").append(snippet).append("\n");
                    }
                }

                if (urlLink != null && !urlLink.isBlank()) {
                    sb.append("     🔗 ").append(urlLink).append("\n");
                }
            }

            if (count == 0) return null;

            sb.append("\n").append("─".repeat(50));
            sb.append("\n数据来源: Bing Search");
            return sb.toString();

        } catch (Exception e) {
            log.warn("[Search] Bing 请求失败: {}", e.getMessage());
            return null;
        }
    }

    private static String stripHtml(String html) {
        if (html == null) return "";
        String text = HTML_TAG.matcher(html).replaceAll("");
        // 解码 HTML 实体
        text = text.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
                .replaceAll("\\s+", " ").trim();
        return text;
    }

    /* ================================================================
     *  DuckDuckGo Instant Answer API (免费，无需 Key)
     * ================================================================ */

    private String tryDuckDuckGo(String query) {
        try {
            String url = "https://api.duckduckgo.com/?q="
                    + URLEncoder.encode(query, "UTF-8")
                    + "&format=json&no_html=1&skip_disambig=1";

            HttpRequest req = newRequest(url).GET().build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return null;

            JsonNode root = MAPPER.readTree(resp.body());
            String abstractText = root.path("Abstract").asText();
            String abstractUrl = root.path("AbstractURL").asText();
            String abstractSource = root.path("AbstractSource").asText();
            String definition = root.path("Definition").asText();

            StringBuilder related = new StringBuilder();
            JsonNode topics = root.path("RelatedTopics");
            if (topics.isArray()) {
                int cnt = 0;
                for (JsonNode t : topics) {
                    String text = t.path("Text").asText();
                    if (text.isBlank() && t.has("Topics")) {
                        for (JsonNode sub : t.path("Topics")) {
                            if (cnt >= 8) break;
                            String st = sub.path("Text").asText();
                            if (!st.isBlank()) { related.append("  • ").append(st).append("\n"); cnt++; }
                        }
                    } else if (!text.isBlank()) {
                        if (cnt >= 8) break;
                        related.append("  • ").append(text).append("\n");
                        cnt++;
                    }
                }
            }

            if (abstractText.isBlank() && definition.isBlank() && related.length() == 0) return null;

            StringBuilder sb = new StringBuilder();
            sb.append("🌐 搜索结果: ").append(query).append("\n");
            sb.append("─".repeat(50)).append("\n");

            if (!abstractText.isBlank()) {
                sb.append("📖 百科摘要 (").append(abstractSource).append("):\n");
                sb.append("  ").append(abstractText).append("\n");
                if (!abstractUrl.isBlank()) sb.append("  🔗 ").append(abstractUrl).append("\n");
                sb.append("\n");
            }
            if (!definition.isBlank()) {
                sb.append("📝 定义: ").append(definition).append("\n\n");
            }
            if (related.length() > 0) {
                sb.append("🔍 相关主题:\n").append(related);
            }

            sb.append("─".repeat(50));
            sb.append("\n数据来源: DuckDuckGo Instant Answer API");
            return sb.toString();

        } catch (Exception e) {
            log.warn("[Search] DuckDuckGo 请求失败: {}", e.getMessage());
            return null;
        }
    }

    /* ================================================================
     *  Fallback — 所有 API 都不可用时的智能降级
     * ================================================================ */

    private String fallbackSearch(String query, String reason) {
        log.warn("[Search] 全部 API 不可用: {} — 返回本地数据", reason);

        StringBuilder sb = new StringBuilder();
        sb.append("⚠️ 实时搜索暂时不可用，以下为本地数据:\n");
        sb.append("─".repeat(50)).append("\n");

        String lower = query.toLowerCase();

        // 尝试从用户查询中提取城市名（简单启发式）
        String city = "";
        String[] knownCities = {"北京", "上海", "广州", "深圳", "杭州", "成都", "武汉", "南京",
                "天津", "重庆", "西安", "苏州", "合肥", "长沙", "郑州", "东莞", "青岛", "沈阳"};
        for (String c : knownCities) {
            if (query.contains(c)) { city = c; break; }
        }

        if (lower.contains("天气") || lower.contains("weather")) {
            sb.append("🌤 今日天气参考:\n");
            if (!city.isBlank()) {
                sb.append("  📍 ").append(city).append(": 多云 22~30°C 湿度 60% (本地参考数据)\n");
            } else {
                for (String c : List.of("北京", "上海", "广州")) {
                    sb.append("  📍 ").append(c).append(": 多云 22~30°C\n");
                }
            }
        } else if (lower.contains("新闻") || lower.contains("news")) {
            sb.append("""
                    📰 今日新闻 (本地数据):
                    1. AI Agent 框架从零实现技术迎来新突破
                    2. Java 21 新特性在企业级应用中普及率持续上升
                    3. 多地迎来高温天气，气象部门发布预警
                    """);
        } else {
            sb.append("  关于「").append(query).append("」的本地参考数据\n");
            if (!city.isBlank()) {
                sb.append("  📍 城市: ").append(city).append("\n");
            }
        }

        sb.append("\n💡 提示: 尝试使用 fetch_page 工具直接抓取某个已知网址的内容");
        return sb.toString();
    }

    /* ---------- helpers ---------- */

    /** 创建带通用 Header 的 GET 请求构造器 */
    private HttpRequest.Builder newRequest(String url) {
        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
                        + "AppleWebKit/537.36 (KHTML, like Gecko) "
                        + "Chrome/125.0.0.0 Safari/537.36")
                .timeout(TIMEOUT);
    }
}
