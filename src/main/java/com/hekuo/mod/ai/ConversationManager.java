package com.hekuo.mod.ai;

import com.google.gson.*;
import com.hekuo.mod.HekuosMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.*;
import java.nio.file.Path;
import java.util.*;

/**
 * 对话管理器 - 管理AI对话的会话和消息历史
 * 持久化到 config/hekuos-mod/conversations.json
 */
public class ConversationManager {

    private static final Path CONVERSATIONS_DIR = FabricLoader.getInstance().getConfigDir().resolve("hekuos-mod");
    private static final Path CONVERSATIONS_FILE = CONVERSATIONS_DIR.resolve("conversations.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static ConversationManager instance;

    private final Map<String, Conversation> conversations = new LinkedHashMap<>();
    private String currentConversationId; // 用于跟踪当前正在添加消息的会话

    public static ConversationManager getInstance() {
        if (instance == null) {
            instance = new ConversationManager();
            instance.loadConversations();
        }
        return instance;
    }

    /**
     * 获取或创建一个对话
     */
    public Conversation getOrCreateConversation(String conversationId) {
        this.currentConversationId = conversationId;
        return conversations.computeIfAbsent(conversationId, id -> {
            Conversation conv = new Conversation(id);
            HekuosMod.LOGGER.info("创建新对话: {}", id);
            return conv;
        });
    }

    /**
     * 获取对话
     */
    public Conversation getConversation(String conversationId) {
        return conversations.get(conversationId);
    }

    /**
     * 添加助手消息到当前对话
     */
    public void addAssistantMessage(String content, String thinking) {
        if (currentConversationId != null) {
            Conversation conv = conversations.get(currentConversationId);
            if (conv != null) {
                conv.addMessage("assistant", content);
                if (thinking != null) {
                    conv.addThinking(thinking);
                }
            }
        }
    }

    /**
     * 生成新的会话ID
     */
    public String generateConversationId() {
        return "conv-" + UUID.randomUUID().toString().substring(0, 8);
    }

    // ==================== 持久化 ====================

    public void loadConversations() {
        try {
            File dir = CONVERSATIONS_DIR.toFile();
            if (!dir.exists()) dir.mkdirs();

            File file = CONVERSATIONS_FILE.toFile();
            if (file.exists()) {
                try (Reader reader = new FileReader(file)) {
                    JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                    JsonArray convs = root.getAsJsonArray("conversations");

                    if (convs != null) {
                        for (JsonElement elem : convs) {
                            JsonObject convObj = elem.getAsJsonObject();
                            String id = convObj.get("id").getAsString();
                            Conversation conv = new Conversation(id);

                            JsonArray msgs = convObj.getAsJsonArray("messages");
                            if (msgs != null) {
                                for (JsonElement msgElem : msgs) {
                                    JsonObject msgObj = msgElem.getAsJsonObject();
                                    String role = msgObj.get("role").getAsString();
                                    String content = msgObj.get("content").getAsString();
                                    conv.addMessage(role, content);
                                }
                            }

                            JsonArray thinking = convObj.getAsJsonArray("thinkingHistory");
                            if (thinking != null) {
                                for (JsonElement tElem : thinking) {
                                    conv.addThinking(tElem.getAsString());
                                }
                            }

                            conversations.put(id, conv);
                        }
                    }

                    HekuosMod.LOGGER.info("已加载 {} 个对话", conversations.size());
                }
            }
        } catch (Exception e) {
            HekuosMod.LOGGER.error("加载对话失败", e);
        }
    }

    public void saveConversations() {
        try {
            File dir = CONVERSATIONS_DIR.toFile();
            if (!dir.exists()) dir.mkdirs();

            JsonObject root = new JsonObject();
            JsonArray convs = new JsonArray();

            for (Conversation conv : conversations.values()) {
                JsonObject convObj = new JsonObject();
                convObj.addProperty("id", conv.id);

                JsonArray msgs = new JsonArray();
                for (Message msg : conv.messages) {
                    JsonObject msgObj = new JsonObject();
                    msgObj.addProperty("role", msg.role);
                    msgObj.addProperty("content", msg.content);
                    msgs.add(msgObj);
                }
                convObj.add("messages", msgs);

                JsonArray thinking = new JsonArray();
                for (String t : conv.thinkingHistory) {
                    thinking.add(t);
                }
                convObj.add("thinkingHistory", thinking);

                convs.add(convObj);
            }

            root.add("conversations", convs);

            try (Writer writer = new FileWriter(CONVERSATIONS_FILE.toFile())) {
                GSON.toJson(root, writer);
            }

            HekuosMod.LOGGER.info("已保存 {} 个对话", conversations.size());
        } catch (Exception e) {
            HekuosMod.LOGGER.error("保存对话失败", e);
        }
    }

    // ==================== 数据类 ====================

    public static class Conversation {
        public final String id;
        public final List<Message> messages = new ArrayList<>();
        public final List<String> thinkingHistory = new ArrayList<>();

        public Conversation(String id) {
            this.id = id;
        }

        public void addMessage(String role, String content) {
            messages.add(new Message(role, content));
        }

        public void addThinking(String thinking) {
            thinkingHistory.add(thinking);
        }

        public List<Message> getMessages() {
            return Collections.unmodifiableList(messages);
        }

        public String getThinkingSummary() {
            if (thinkingHistory.isEmpty()) return null;
            return String.join("\n---\n", thinkingHistory);
        }
    }

    public static class Message {
        public final String role;
        public final String content;

        public Message(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }
}
