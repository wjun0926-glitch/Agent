package com.example.mva.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * URL 网页抓取工具。
 * <p>
 * 可以抓取指定 URL 的文本内容（自动去除 HTML 标签），
 * 让 Agent 不仅能搜索，还能阅读具体网页。
 */
@Component
public class FetchPageTool extends BaseTool {

    private static final Logger log = LoggerFactory.getLogger(FetchPageTool.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    /** 最大抓取字符数 */
    private static final int MAX_CONTENT_LENGTH = 8000;
    /** 提取正文的最小段落长度（过短的段落忽略） */
    private static final int MIN_PARAGRAPH_LENGTH = 15;

    /** HTML 标签正则 */
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
    /** 多个空白字符正则 */
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    /** script / style 标签（含内容）正则 */
    private static final Pattern SCRIPT_STYLE = Pattern.compile(
            "(?is)<(script|style|noscript)[^>]*>.*?</\\1\\s*>");

    private final HttpClient http;

    public FetchPageTool() {
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public String name() {
        return "fetch_page";
    }

    @Override
    public String description() {
        return "Fetch the content of a webpage by URL. "
                + "Strips HTML tags and returns clean readable text. "
                + "Useful for reading news articles, documentation, and specific web pages. "
                + "Max content length is " + (MAX_CONTENT_LENGTH / 1000) + "K characters.";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "url", Map.of(
                                "type", "string",
                                "description", "要抓取的完整 URL（须包含 http:// 或 https://）"
                        )
                ),
                "required", List.of("url")
        );
    }

    @Override
    public String execute(String jsonArgs, String sessionId) {
        try {
            JsonNode node = MAPPER.readTree(jsonArgs);
            String url = node.get("url").asText().trim();

            if (url.isBlank()) {
                return "请输入 URL";
            }

            // 自动补全协议
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://" + url;
            }

            log.info("[FetchPage] 抓取: {}", url);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                            + "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .timeout(TIMEOUT)
                    .GET()
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            int status = resp.statusCode();
            String html = resp.body();

            if (status != 200) {
                return "抓取失败: HTTP " + status;
            }

            // 提取纯文本
            String text = extractText(html);

            // 截断过长内容
            if (text.length() > MAX_CONTENT_LENGTH) {
                text = text.substring(0, MAX_CONTENT_LENGTH)
                        + "\n\n... (内容过长，已截断，全文 " + html.length() + " 字节)";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("📄 页面内容: ").append(url).append("\n");
            sb.append("  (HTTP ").append(status).append(", 文本 ").append(text.length()).append(" 字符)\n");
            sb.append("─".repeat(50)).append("\n");
            sb.append(text);

            return sb.toString();

        } catch (Exception e) {
            log.warn("[FetchPage] 抓取异常", e);
            return "抓取出错: " + e.getMessage();
        }
    }

    /**
     * 从 HTML 提取可读文本。
     */
    private static String extractText(String html) {
        // 1. 移除 script / style 块
        String noScript = SCRIPT_STYLE.matcher(html).replaceAll("");

        // 2. 替换常见的块级标签为换行
        noScript = noScript.replaceAll("(?is)</?(div|p|br|tr|li|h[1-6]|blockquote|section|article|table)[^>]*>", "\n");

        // 3. 替换换行标签
        noScript = noScript.replaceAll("(?is)<br\\s*/?>", "\n");

        // 4. 去除所有 HTML 标签
        String noTag = HTML_TAG.matcher(noScript).replaceAll("");

        // 5. 解码 HTML 实体
        noTag = noTag.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&nbsp;", " ");

        // 6. 合并空白字符
        noTag = WHITESPACE.matcher(noTag).replaceAll(" ");

        // 7. 拆分行，过滤过短或空白行
        StringBuilder sb = new StringBuilder();
        String[] lines = noTag.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.length() >= MIN_PARAGRAPH_LENGTH) {
                sb.append(trimmed).append("\n");
            }
        }

        return sb.toString().trim();
    }

    /** 用于 JSON 解析的静态 ObjectMapper（本类单独持有） */
    private static final ObjectMapper MAPPER = new ObjectMapper();
}
