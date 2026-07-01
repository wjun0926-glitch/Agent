package com.example.mva.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Maps the "agent.*" namespace from application.yml into a typed configuration bean.
 *
 * <pre>
 * agent:
 *   provider: openai|anthropic
 *   api-key: ${LLM_API_KEY}
 *   model: gpt-4o-mini
 *   endpoint: https://api.openai.com/v1
 *   max-tokens: 2048
 *   temperature: 0.7
 *   max-messages: 12
 *   max-iterations: 5
 * </pre>
 */
@Component
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {

    /* ---------- LLM ---------- */
    private String provider = "openai";
    private String apiKey = "sk-placeholder";
    private String model = "gpt-4o-mini";
    private String endpoint = "https://api.openai.com/v1";
    private int maxTokens = 2048;
    private double temperature = 0.7;

    /* ---------- Context ---------- */
    private int maxMessages = 12;

    /* ---------- ReAct loop ---------- */
    private int maxIterations = 5;

    /* ---------- Boilerplate ---------- */

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }

    public int getMaxTokens() { return maxTokens; }
    public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }

    public double getTemperature() { return temperature; }
    public void setTemperature(double temperature) { this.temperature = temperature; }

    public int getMaxMessages() { return maxMessages; }
    public void setMaxMessages(int maxMessages) { this.maxMessages = maxMessages; }

    public int getMaxIterations() { return maxIterations; }
    public void setMaxIterations(int maxIterations) { this.maxIterations = maxIterations; }
}
