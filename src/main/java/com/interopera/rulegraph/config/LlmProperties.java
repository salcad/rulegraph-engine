package com.interopera.rulegraph.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the optional LLM-backed rule extractor, under the {@code rulegraph.llm.*}
 * prefix. The model is reached through the OpenRouter API (an OpenAI-compatible chat-completions
 * gateway), so any frontier model can be selected by id without code changes.
 *
 * <p>When {@code enabled} is {@code false} (the default) the extractor falls back to the
 * deterministic seed set and no network call is ever made — the system runs fully offline. Even
 * when enabled, a missing key or any call/parse failure degrades back to the seed extractor.
 *
 * @param enabled        turns the LLM-backed extractor on; off by default
 * @param apiKey         OpenRouter API key (set via {@code OPENROUTER_API_KEY})
 * @param baseUrl        OpenRouter API base, e.g. {@code https://openrouter.ai/api/v1}
 * @param model          OpenRouter model id, e.g. {@code anthropic/claude-3.5-sonnet}
 * @param timeoutSeconds per-request timeout for the chat-completions call
 */
@ConfigurationProperties(prefix = "rulegraph.llm")
public record LlmProperties(
        boolean enabled,
        String apiKey,
        String baseUrl,
        String model,
        int timeoutSeconds
) {
    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    public String chatCompletionsUrl() {
        String base = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        return base + "/chat/completions";
    }
}
