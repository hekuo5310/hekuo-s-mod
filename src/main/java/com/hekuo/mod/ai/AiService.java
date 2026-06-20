package com.hekuo.mod.ai;

import com.google.gson.*;
import com.hekuo.mod.HekuosMod;
import com.hekuo.mod.config.ModConfig;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/**
 * AI服务 - 支持OpenAI/Anthropic/Google三种协议
 * 支持思考(thinking)模式
 */
public class AiService {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    /**
     * 调用AI API并异步返回结果
     */
    public CompletableFuture<AiResponse> chatAsync(String userMessage, String conversationId) {
        return CompletableFuture.supplyAsync(() -> chat(userMessage, conversationId), executor);
    }

    /**
     * 同步调用AI API
     */
    public AiResponse chat(String userMessage, String conversationId) {
        ModConfig config = ModConfig.get();
        if (!config.aiChatEnabled) {
            return new AiResponse("AI功能未启用", null, null);
        }

        try {
            ConversationManager.Conversation conv = ConversationManager.getInstance()
                    .getOrCreateConversation(conversationId);

            // 添加用户消息到历史
            conv.addMessage("user", userMessage);

            String provider = config.aiProvider.provider.toLowerCase();

            switch (provider) {
                case "openai":
                    return callOpenAiCompatible(conv, config);
                case "anthropic":
                    return callAnthropic(conv, config);
                case "google":
                    return callGoogle(conv, config);
                default:
                    return new AiResponse("不支持的AI提供商: " + provider, null, null);
            }
        } catch (Exception e) {
            HekuosMod.LOGGER.error("AI API调用失败", e);
            return new AiResponse("AI调用失败: " + e.getMessage(), null, null);
        }
    }

    /**
     * OpenAI兼容协议调用（也兼容各种OpenAI格式的API）
     */
    private AiResponse callOpenAiCompatible(ConversationManager.Conversation conv, ModConfig config) throws Exception {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", config.aiProvider.model);
        requestBody.addProperty("max_tokens", config.aiMaxTokens);
        requestBody.addProperty("temperature", config.aiTemperature);

        // 构建消息数组
        JsonArray messages = new JsonArray();

        // 系统提示词
        JsonObject systemMsg = new JsonObject();
        systemMsg.addProperty("role", "system");
        systemMsg.addProperty("content", config.aiSystemPrompt);
        messages.add(systemMsg);

        // 历史消息
        for (ConversationManager.Message msg : conv.getMessages()) {
            JsonObject msgObj = new JsonObject();
            msgObj.addProperty("role", msg.role);
            msgObj.addProperty("content", msg.content);
            messages.add(msgObj);
        }

        requestBody.add("messages", messages);

        // 发送请求
        String response = sendPostRequest(
            config.aiProvider.baseUrl + "/chat/completions",
            requestBody.toString(),
            config.aiProvider.apiKey,
            "Bearer"
        );

        return parseOpenAiResponse(response, config);
    }

    /**
     * Anthropic协议调用
     */
    private AiResponse callAnthropic(ConversationManager.Conversation conv, ModConfig config) throws Exception {
        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", config.aiProvider.model);
        requestBody.addProperty("max_tokens", config.aiMaxTokens);

        // 构建消息数组（Anthropic不需要system在messages中）
        JsonArray messages = new JsonArray();
        requestBody.addProperty("system", config.aiSystemPrompt);

        for (ConversationManager.Message msg : conv.getMessages()) {
            JsonObject msgObj = new JsonObject();
            msgObj.addProperty("role", msg.role);
            msgObj.addProperty("content", msg.content);
            messages.add(msgObj);
        }

        requestBody.add("messages", messages);

        // 思考模式
        if (config.aiThinkingEnabled) {
            JsonObject thinking = new JsonObject();
            thinking.addProperty("type", "enabled");
            thinking.addProperty("budget_tokens", config.aiThinkingBudget);
            requestBody.add("thinking", thinking);
        }

        String response = sendPostRequest(
            config.aiProvider.baseUrl + "/messages",
            requestBody.toString(),
            config.aiProvider.apiKey,
            "x-api-key"
        );

        return parseAnthropicResponse(response, config);
    }

    /**
     * Google Gemini协议调用
     */
    private AiResponse callGoogle(ConversationManager.Conversation conv, ModConfig config) throws Exception {
        JsonObject requestBody = new JsonObject();

        // 构建内容
        JsonArray contents = new JsonArray();

        for (ConversationManager.Message msg : conv.getMessages()) {
            JsonObject content = new JsonObject();
            content.addProperty("role", msg.role.equals("assistant") ? "model" : "user");
            JsonArray parts = new JsonArray();
            JsonObject part = new JsonObject();
            part.addProperty("text", msg.content);
            parts.add(part);
            content.add("parts", parts);
            contents.add(content);
        }

        requestBody.add("contents", contents);

        // 系统指令
        JsonObject systemInstruction = new JsonObject();
        JsonArray sysParts = new JsonArray();
        JsonObject sysPart = new JsonObject();
        sysPart.addProperty("text", config.aiSystemPrompt);
        sysParts.add(sysPart);
        systemInstruction.add("parts", sysParts);
        requestBody.add("systemInstruction", systemInstruction);

        // 生成配置
        JsonObject genConfig = new JsonObject();
        genConfig.addProperty("maxOutputTokens", config.aiMaxTokens);
        genConfig.addProperty("temperature", config.aiTemperature);
        requestBody.add("generationConfig", genConfig);

        String url = config.aiProvider.baseUrl + "/models/" + config.aiProvider.model
                + ":generateContent?key=" + config.aiProvider.apiKey;

        String response = sendPostRequest(url, requestBody.toString(), null, null);
        return parseGoogleResponse(response, config);
    }

    // ==================== HTTP请求 ====================

    private String sendPostRequest(String urlStr, String body, String apiKey, String authType) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "application/json");

        if (apiKey != null && !apiKey.isEmpty()) {
            if ("Bearer".equals(authType)) {
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            } else if ("x-api-key".equals(authType)) {
                conn.setRequestProperty("x-api-key", apiKey);
                conn.setRequestProperty("anthropic-version", "2023-06-01");
            }
        }

        conn.setDoOutput(true);
        conn.setConnectTimeout(ModConfig.get().aiProvider.timeout * 1000);
        conn.setReadTimeout(ModConfig.get().aiProvider.timeout * 1000);

        try (OutputStream os = conn.getOutputStream()) {
            byte[] input = body.getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        int responseCode = conn.getResponseCode();
        InputStream is = responseCode >= 400 ? conn.getErrorStream() : conn.getInputStream();

        StringBuilder response = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                response.append(line);
            }
        }

        if (responseCode >= 400) {
            HekuosMod.LOGGER.error("AI API错误 ({}): {}", responseCode, response);
            throw new RuntimeException("AI API返回错误: " + responseCode + " - " + response);
        }

        return response.toString();
    }

    // ==================== 响应解析 ====================

    private AiResponse parseOpenAiResponse(String response, ModConfig config) {
        JsonObject json = JsonParser.parseString(response).getAsJsonObject();
        JsonArray choices = json.getAsJsonArray("choices");

        if (choices != null && !choices.isEmpty()) {
            JsonObject firstChoice = choices.get(0).getAsJsonObject();
            JsonObject message = firstChoice.getAsJsonObject("message");
            String content = message.get("content").getAsString();
            String thinking = null;

            // 检查是否有思考内容（某些兼容接口）
            if (message.has("reasoning_content") && !message.get("reasoning_content").isJsonNull()) {
                thinking = message.get("reasoning_content").getAsString();
            }

            // 保存助手回复
            ConversationManager.getInstance().addAssistantMessage(content, thinking);

            return new AiResponse(content, thinking, null);
        }

        return new AiResponse("AI未返回有效响应", null, null);
    }

    private AiResponse parseAnthropicResponse(String response, ModConfig config) {
        JsonObject json = JsonParser.parseString(response).getAsJsonObject();
        JsonArray content = json.getAsJsonArray("content");

        if (content != null && !content.isEmpty()) {
            String textContent = null;
            String thinking = null;

            for (JsonElement elem : content) {
                JsonObject block = elem.getAsJsonObject();
                String type = block.get("type").getAsString();

                if ("text".equals(type)) {
                    textContent = block.get("text").getAsString();
                } else if ("thinking".equals(type)) {
                    thinking = block.get("thinking").getAsString();
                }
            }

            ConversationManager.getInstance().addAssistantMessage(textContent, thinking);
            return new AiResponse(textContent, thinking, null);
        }

        return new AiResponse("AI未返回有效响应", null, null);
    }

    private AiResponse parseGoogleResponse(String response, ModConfig config) {
        JsonObject json = JsonParser.parseString(response).getAsJsonObject();
        JsonArray candidates = json.getAsJsonArray("candidates");

        if (candidates != null && !candidates.isEmpty()) {
            JsonObject firstCandidate = candidates.get(0).getAsJsonObject();
            JsonObject content = firstCandidate.getAsJsonObject("content");
            JsonArray parts = content.getAsJsonArray("parts");

            String textContent = null;
            String thinking = null;

            for (JsonElement part : parts) {
                JsonObject partObj = part.getAsJsonObject();
                if (partObj.has("text")) {
                    if (partObj.has("thought") && partObj.get("thought").getAsBoolean()) {
                        thinking = partObj.get("text").getAsString();
                    } else {
                        textContent = partObj.get("text").getAsString();
                    }
                }
            }

            ConversationManager.getInstance().addAssistantMessage(textContent, thinking);
            return new AiResponse(textContent, thinking, null);
        }

        return new AiResponse("AI未返回有效响应", null, null);
    }

    // ==================== 响应数据类 ====================

    public static class AiResponse {
        public final String content;
        public final String thinking;
        public final String error;

        public AiResponse(String content, String thinking, String error) {
            this.content = content;
            this.thinking = thinking;
            this.error = error;
        }

        public boolean hasThinking() {
            return thinking != null && !thinking.isEmpty();
        }

        public boolean hasError() {
            return error != null && !error.isEmpty();
        }
    }
}
