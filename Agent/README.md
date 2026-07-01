# MVA — Minimal Viable Agent (Java 21 + Spring Boot 3.x)

> **纯手工实现的 ReAct Agent**，不依赖任何 LLM 框架（LangChain4j、Spring AI 等）。
> 核心调度大脑自研实现，仅使用 Java 21 标准库 + Spring Boot Web 能力。

---

## 环境要求

| 依赖 | 版本 | 说明 |
|------|------|------|
| JDK  | 21+  | |
| Maven | 3.8+ | |
| LLM API Key | DeepSeek / OpenAI / Anthropic | 设置环境变量 `LLM_API_KEY` |
| SEARCH_API_KEY | 可选 | SerpAPI Key，不配置则自动用 Bing 搜索 |

## 快速启动

### 1. 配置

```bash
# LLM API Key（必填）
export LLM_API_KEY=sk-your-key-here

# 可选：联网搜索（不配置自动用 Bing 免费搜索）
export SEARCH_API_KEY=your-serpapi-key   # SerpAPI → Google 搜索结果
# 注册地址: https://serpapi.com/ (免费额度每月100次)

# 可选：模型和端点（默认已配好 DeepSeek）
export LLM_MODEL=deepseek-chat
```

> **联网搜索说明：** 默认使用 Bing 搜索（国内可访问，无需 Key）。
> 如果配置了 `SEARCH_API_KEY` 则使用 SerpAPI（Google 结果）。
> 也可以在 `application.yml` 中修改 `agent.search.api-key`。

### 2. 编译运行

```bash
cd mva-java-agent
mvn clean package -DskipTests
java -jar target/mva-java-agent-1.0.0.jar
```

或者直接使用 Maven 插件：

```bash
mvn spring-boot:run
```

### 3. 打开浏览器

访问 [http://localhost:8080](http://localhost:8080) 进入聊天界面。

---

## API 接口说明

### POST `/api/chat` — 发送消息

**请求体：**

```json
{
  "sessionId": "窗口1",
  "message": "123乘以456等于多少？"
}
```

**响应：**

```json
{
  "sessionId": "窗口1",
  "reply": "56088",
  "truncated": false,
  "traceLog": [
    "[迭代 1/5] 发送请求至 LLM...",
    "[思考] 用户想计算 123*456，我来调用计算器工具...",
    "[工具调用] calculator({\"expression\":\"123*456\"})",
    "[工具结果] 56088",
    "[最终回复] 123 乘以 456 等于 56088。"
  ]
}
```

### GET `/api/sessions` — 查看活跃会话

```json
{
  "sessions": ["窗口1", "窗口2"]
}
```

### DELETE `/api/sessions/{sessionId}` — 删除会话

### DELETE `/api/sessions` — 清除所有会话

---

## 测试场景演示

### 场景 1：基础工具调用

**窗口 1：** `123乘以456等于多少？`

```
→ LLM 调用 calculator("123*456")
→ 返回结果 56088
→ 组装最终答案
```

### 场景 2：联网搜索 + 多会话隔离

**窗口 2：** `查一下合肥今天的天气，并记一个待办。"

```
→ LLM 调用 search("合肥天气")
→ 返回真实天气数据（SerpAPI → Bing → DuckDuckGo 自动切换）
→ 再调用 todo_manager (add)
→ 所有操作仅影响窗口 2 的上下文
```

### 场景 3：搜索 + 网页抓取组合

**窗口 3：** `搜索最新的 AI 新闻，然后给我读第一篇的内容`

```
→ LLM 先调用 search("AI 最新新闻 2026")
→ 返回搜索结果列表（含链接）
→ LLM 再调用 fetch_page("https://xxx")
→ 抓取页面内容，LLM 阅读后总结
```

### 场景 4：带工具的追问与记忆召回

**回到窗口 1：** `把刚才的计算结果再加上 2000`

```
→ LLM 从历史消息中找回之前的结果 56088
→ 调用 calculator("56088+2000")
→ 返回 58088
```

---

## 架构设计

```
┌─────────────────────────────────────────────────────────┐
│                    Web 交互层                            │
│    HTML/JS Frontend  ←→  REST Controller                │
└──────────────────────┬──────────────────────────────────┘
                       │ sessionId
┌──────────────────────▼──────────────────────────────────┐
│              SessionManager                              │
│     (ConcurrentHashMap<String, ContextManager>)          │
│     — 多会话内存隔离                                     │
└──────────────────────┬──────────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────────┐
│              AgentRuntime (ReAct 循环)                    │
│                                                          │
│  ┌─────┐    ┌──────────┐    ┌──────────┐    ┌───────┐  │
│  │用户输│───▶│ LLM 调用 │───▶│ 工具调用 │───▶│ 循环  │  │
│  │入    │    │          │    │          │    │(≤5次) │  │
│  └─────┘    └──────────┘    └──────────┘    └───────┘  │
│                  │                      │               │
│                  ▼                      ▼               │
│           ┌──────────┐          ┌──────────┐           │
│           │直接回复  │          │工具结果  │           │
│           │→ 返回    │          │→ LLM 再  │           │
│           │          │          │  次分析  │           │
│           └──────────┘          └──────────┘           │
└──────────────────────┬──────────────────────────────────┘
                       │
        ┌──────────────┼──────────────────┐
        ▼              ▼                  ▼
┌────────────┐ ┌────────────┐ ┌────────────────┐
│LlmClient   │ │ToolRegistry│ │Context         │
│(Java 21    │ │  ├─ Calculator │Manager          │
│ HttpClient)│ │  ├─ Search    │(滑动窗口)       │
│            │ │  ├─ FetchPage │                 │
│            │ │  └─ TodoMgr  │                 │
└────────────┘ └────────────┘ └────────────────┘
```

### 核心模块

| 模块 | 职责 |
|------|------|
| **LlmClient** | 使用 Java 21 `HttpClient` 封装对 OpenAI / Anthropic API 的 HTTP 调用 |
| **AgentRuntime** | 纯手工编写的 ReAct 状态机，控制思考-行动-观察循环 |
| **ToolRegistry** | 工具注册中心，自动发现 `@Component` 工具 Bean |
| **ContextManager** | 滑动窗口消息管理，始终保留 System Prompt |
| **SessionManager** | 多会话隔离，基于 `ConcurrentHashMap` |
| **SearchTool** | **多后端联网搜索**（SerpAPI→Bing→DuckDuckGo 自动降级）|
| **FetchPageTool** | **URL 网页内容抓取**，HTML 净化提取正文 |
| **CalculatorTool** | 安全的四则运算，递归下降解析器，无 eval() |

---

## 记忆召回机制设计

### 滑动窗口 (`ContextManager`)

- 默认最多保留 **12 条消息**
- **System Prompt 始终固定在 index 0**，永不截断
- 超出上限时，从 **index 1 开始**移除最早的非系统消息
- 每次截断记录 `trimmedCount`，日志可见

### 多会话隔离 (`SessionManager`)

- 每个 `sessionId` 对应一个独立的 `ContextManager`
- 使用 `ConcurrentHashMap` 实现线程安全的内存存储
- 会话之间完全隔离，互不干扰

### ReAct 上下文

- 消息按时间线严格追加：`User → Assistant(Thought+ToolCalls) → Tool(Observation) → Assistant(Final)`
- 工具调用结果作为 `role="tool"` 的消息写回上下文
- LLM 每次请求会看到完整的对话历史（受滑动窗口限制）

---

## 内置工具

| 工具 | 功能 | 参数 | 数据来源 |
|------|------|------|---------|
| `calculator` | 数学表达式计算 | `expression: string` | 本地递归下降解析器 |
| `search` | **多后端联网搜索**（SerpAPI→Bing→DuckDuckGo→本地降级） | `query: string` | ✅ 真实网络 |
| `fetch_page` | **URL 网页内容抓取**（HTML 净化提取正文） | `url: string` | ✅ 真实网络 |
| `todo_manager` | 待办事项管理 | `action`, `item?` | 本地内存 |

---

## 技术栈

- **Java 21** — Records, Pattern Matching, Sealed Interfaces, Text Blocks, `HttpClient`
- **Spring Boot 3.3** — IoC, REST, `@ConfigurationProperties`
- **Jackson** — JSON 序列化/反序列化
- **零 AI 框架依赖** — 全程手写
- **联网搜索** — 多后端自动降级（SerpAPI / Bing / DuckDuckGo / 本地数据）

## 搜索配置说明

本项目默认使用 **Bing 搜索引擎**（国内可访问，无需 API Key），
如果需要更完整的 Google 搜索结果，可配置 SerpAPI：

```yaml
# application.yml 中修改
agent:
  search:
    api-key: ${SEARCH_API_KEY:}   # 填入 SerpAPI Key
```

搜索后端自动切换逻辑：

| 优先级 | 后端 | 条件 | 结果质量 |
|--------|------|------|---------|
| 1 | SerpAPI (Google) | 配置了 `search.api-key` | ⭐⭐⭐⭐⭐ |
| 2 | **Bing (cn.bing.com)** | 默认可用，无需 Key | ⭐⭐⭐⭐ |
| 3 | DuckDuckGo | API 可用时 | ⭐⭐⭐ |
| 4 | 本地降级数据 | 所有 API 不可用时 | ⭐ |

此外，`fetch_page` 工具可抓取任意 URL 的文本内容，配合 search 实现"搜索→阅读→总结"的完整链路。

## License

MIT
