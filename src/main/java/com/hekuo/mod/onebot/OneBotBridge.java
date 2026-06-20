package com.hekuo.mod.onebot;

import com.google.gson.*;
import com.hekuo.mod.HekuosMod;
import com.hekuo.mod.config.ModConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.*;
import java.util.concurrent.*;

/**
 * OneBot群服互联桥接器
 * 通过WebSocket连接到OneBot协议实现QQ群与MC服务器的消息互通
 * 仅服务端可用
 */
public class OneBotBridge {

    private static OneBotBridge instance;
    private WebSocketClient wsClient;
    private MinecraftServer server;
    private ModConfig.OneBotConfig config;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private boolean connected = false;
    private int reconnectAttempts = 0;
    private static final int MAX_RECONNECT_ATTEMPTS = 100;
    private static final Gson GSON = new Gson();

    public static OneBotBridge getInstance() {
        if (instance == null) {
            instance = new OneBotBridge();
        }
        return instance;
    }

    private OneBotBridge() {}

    /**
     * 连接到OneBot WebSocket
     */
    public void connect(ModConfig.OneBotConfig config) {
        this.config = config;
        if (config.groupIds.isEmpty()) {
            HekuosMod.LOGGER.warn("OneBot: 未配置群号，跳过连接");
            return;
        }

        try {
            URI uri = new URI(config.wsUrl);
            Map<String, String> headers = new HashMap<>();
            if (config.accessToken != null && !config.accessToken.isEmpty()) {
                headers.put("Authorization", "Bearer " + config.accessToken);
            }

            wsClient = new WebSocketClient(uri, headers) {
                @Override
                public void onOpen(ServerHandshake handshake) {
                    connected = true;
                    reconnectAttempts = 0;
                    HekuosMod.LOGGER.info("OneBot: 已连接到 {}", config.wsUrl);

                    // 订阅群消息事件
                    // 不同的OneBot实现可能需要不同的订阅方式
                }

                @Override
                public void onMessage(String message) {
                    handleOneBotMessage(message);
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    connected = false;
                    HekuosMod.LOGGER.info("OneBot: 连接关闭 (code={}, reason={})", code, reason);
                    scheduleReconnect();
                }

                @Override
                public void onError(Exception ex) {
                    HekuosMod.LOGGER.error("OneBot: WebSocket错误", ex);
                    connected = false;
                }
            };

            wsClient.connect();
            HekuosMod.LOGGER.info("OneBot: 正在连接到 {}...", config.wsUrl);

        } catch (Exception e) {
            HekuosMod.LOGGER.error("OneBot: 连接失败", e);
            scheduleReconnect();
        }
    }

    /**
     * 断开连接
     */
    public void disconnect() {
        if (wsClient != null) {
            try {
                wsClient.close();
            } catch (Exception e) {
                HekuosMod.LOGGER.error("OneBot: 断开连接失败", e);
            }
        }
        scheduler.shutdown();
        connected = false;
    }

    /**
     * 重连机制
     */
    private void scheduleReconnect() {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            HekuosMod.LOGGER.warn("OneBot: 已达最大重连次数，停止重连");
            return;
        }

        reconnectAttempts++;
        long delay = Math.min(30, 5 * reconnectAttempts); // 递增延迟，最多30秒

        scheduler.schedule(() -> {
            if (!connected && config != null) {
                HekuosMod.LOGGER.info("OneBot: 第{}次重连...", reconnectAttempts);
                connect(config);
            }
        }, delay, TimeUnit.SECONDS);
    }

    /**
     * 处理从OneBot收到的消息
     */
    private void handleOneBotMessage(String rawMessage) {
        try {
            JsonObject msg = JsonParser.parseString(rawMessage).getAsJsonObject();

            // 检查是否是群消息事件
            if (!msg.has("post_type")) return;
            String postType = msg.get("post_type").getAsString();

            if ("message".equals(postType)) {
                String messageType = msg.get("message_type").getAsString();
                if (!"group".equals(messageType)) return;

                long groupId = msg.get("group_id").getAsLong();

                // 检查是否在配置的群列表中
                if (!config.groupIds.contains(groupId)) return;

                long userId = msg.get("user_id").getAsLong();
                String rawMsgContent = msg.get("raw_message").getAsString();
                String senderNickname = "未知";

                if (msg.has("sender")) {
                    JsonObject sender = msg.getAsJsonObject("sender");
                    if (sender.has("nickname")) {
                        senderNickname = sender.get("nickname").getAsString();
                    } else if (sender.has("card") && !sender.get("card").isJsonNull()) {
                        senderNickname = sender.get("card").getAsString();
                    }
                }

                // 在MC服务器中广播消息
                broadcastToServer(groupId, userId, senderNickname, rawMsgContent);
            }

        } catch (Exception e) {
            HekuosMod.LOGGER.error("OneBot: 处理消息失败", e);
        }
    }

    /**
     * 将QQ消息广播到MC服务器
     */
    private void broadcastToServer(long groupId, long userId, String nickname, String message) {
        if (server == null) return;

        String formatted = config.qqToPlayerFormat
            .replace("{qq}", String.valueOf(userId))
            .replace("{nickname}", nickname)
            .replace("{message}", message)
            .replace("{group}", String.valueOf(groupId));

        server.execute(() -> {
            server.getPlayerManager().broadcast(
                Text.literal(formatted).formatted(Formatting.AQUA),
                false
            );
        });
    }

    /**
     * 将MC消息发送到QQ群
     */
    public void sendToGroup(long groupId, String message) {
        if (!connected || wsClient == null) return;

        try {
            JsonObject msg = new JsonObject();
            msg.addProperty("action", "send_group_msg");
            JsonObject params = new JsonObject();
            params.addProperty("group_id", groupId);
            params.addProperty("message", message);
            msg.add("params", params);

            wsClient.send(GSON.toJson(msg));
        } catch (Exception e) {
            HekuosMod.LOGGER.error("OneBot: 发送群消息失败", e);
        }
    }

    /**
     * 设置Minecraft服务器实例
     */
    public void setServer(MinecraftServer server) {
        this.server = server;
    }

    /**
     * 从MC服务器转发玩家消息到QQ
     */
    public void forwardPlayerMessage(ServerPlayerEntity player, String message) {
        if (!connected || config == null) return;

        String formatted = config.playerToQqFormat
            .replace("{player}", player.getName().getString())
            .replace("{message}", message);

        for (long groupId : config.groupIds) {
            sendToGroup(groupId, formatted);
        }
    }

    /**
     * 转发玩家加入消息
     */
    public void forwardPlayerJoin(ServerPlayerEntity player) {
        if (!connected || !config.forwardJoinLeave) return;
        for (long groupId : config.groupIds) {
            sendToGroup(groupId, player.getName().getString() + " 加入了服务器");
        }
    }

    /**
     * 转发玩家离开消息
     */
    public void forwardPlayerLeave(ServerPlayerEntity player) {
        if (!connected || !config.forwardJoinLeave) return;
        for (long groupId : config.groupIds) {
            sendToGroup(groupId, player.getName().getString() + " 离开了服务器");
        }
    }

    /**
     * 转发玩家死亡消息
     */
    public void forwardPlayerDeath(ServerPlayerEntity player, Text deathMessage) {
        if (!connected || !config.forwardDeath) return;
        for (long groupId : config.groupIds) {
            sendToGroup(groupId, deathMessage.getString());
        }
    }

    /**
     * 转发玩家获得进度消息
     */
    public void forwardAdvancement(ServerPlayerEntity player, Text advancement) {
        if (!connected || !config.forwardAdvancement) return;
        for (long groupId : config.groupIds) {
            sendToGroup(groupId, player.getName().getString() + " 获得了进度: " + advancement.getString());
        }
    }

    public boolean isConnected() {
        return connected;
    }
}
