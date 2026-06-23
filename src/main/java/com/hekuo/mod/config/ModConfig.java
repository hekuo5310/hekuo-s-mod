package com.hekuo.mod.config;

import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;

import java.io.*;
import java.nio.file.Path;
import java.util.*;

/**
 * 模组配置类 - 所有功能都可以独立开关
 * 配置文件位于 config/hekuos-mod.json
 */
public class ModConfig {

    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("hekuos-mod.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // ==================== 功能开关 ====================

    // 功能1: 铁砧火焰保护水桶（双端）
    public boolean fireProtectionWaterBucketEnabled = true;

    // 功能2: 修复牧羊人小屋（双端）
    public boolean fixShepherdHouseEnabled = true;

    // 功能3: 下界冰变霜冰（双端）
    public boolean netherIceToFrostedIceEnabled = true;

    // 功能4: AI对话功能（双端 - 单人游戏也可用）
    public boolean aiChatEnabled = false;
    public AiProviderConfig aiProvider = new AiProviderConfig();
    public String aiSystemPrompt = "你是一个有用的AI助手，正在Minecraft服务器中与玩家对话。请用简洁友好的方式回答问题。";
    public int aiMaxTokens = 2048;
    public double aiTemperature = 0.7;
    public boolean aiThinkingEnabled = false;
    public int aiThinkingBudget = 4096;

    // 功能5: OneBot群服互联（仅服务端）
    public boolean oneBotEnabled = false;
    public OneBotConfig oneBotConfig = new OneBotConfig();

    // 功能6: 末地烛交互（双端）
    public boolean endRodInteractionEnabled = true;

    // 功能7: 服务器状态网页（仅服务端）
    public boolean webStatusEnabled = false;
    public WebStatusConfig webStatusConfig = new WebStatusConfig();

    // 功能8: UDP mod 分发（双端 - 服务端发/客户端收，automodpack 同款但用可靠 UDP）
    public boolean udpDistributionEnabled = false;
    public UdpDistributionConfig udpDistributionConfig = new UdpDistributionConfig();

    // ==================== 配置子类 ====================

    public static class AiProviderConfig {
        public String provider = "openai"; // openai, anthropic, google
        public String apiKey = "";
        public String model = "gpt-3.5-turbo";
        public String baseUrl = "https://api.openai.com/v1";
        public int timeout = 30;
    }

    public static class OneBotConfig {
        public String wsUrl = "ws://127.0.0.1:6700";
        public String accessToken = "";
        public List<Long> groupIds = new ArrayList<>();
        public String qqToPlayerFormat = "[QQ:{qq}] {message}";
        public String playerToQqFormat = "[{player}] {message}";
        public boolean forwardJoinLeave = true;
        public boolean forwardDeath = true;
        public boolean forwardAdvancement = true;
    }

    public static class WebStatusConfig {
        public int port = 8080;
        public int refreshInterval = 5;
        // Nuitka编译的二进制文件路径，为空则自动搜索
        // 自动搜索顺序：hekuos-mod-web/hekuos-mod-web → config/hekuos-mod/hekuos-mod-web
        // 若找不到二进制，会fallback到搜索系统中的python3
        public String binaryPath = "";
    }

    public static class UdpDistributionConfig {
        public int port = 25566;           // 独立 UDP 端口
        public int chunkSize = 1024;       // 每块数据大小（字节）
        public int ackTimeoutMs = 500;     // ACK 超时（毫秒）
        public int maxRetries = 10;        // 单块最大重试次数
        public String serverHost = "";     // 客户端用：服务端 UDP 地址，空=用当前连接的服务器 IP
        public int maxFileSizeMb = 200;    // 单文件大小上限（MB），防恶意大文件
    }

    // ==================== 序列化/反序列化 ====================

    public static ModConfig load() {
        File file = CONFIG_PATH.toFile();
        if (file.exists()) {
            try (Reader reader = new FileReader(file)) {
                ModConfig config = GSON.fromJson(reader, ModConfig.class);
                if (config != null) return config;
            } catch (Exception e) {
                System.err.println("[Hekuo's Mod] 加载配置失败，使用默认配置: " + e.getMessage());
            }
        }
        ModConfig config = new ModConfig();
        config.save();
        return config;
    }

    public void save() {
        try (Writer writer = new FileWriter(CONFIG_PATH.toFile())) {
            GSON.toJson(this, writer);
        } catch (Exception e) {
            System.err.println("[Hekuo's Mod] 保存配置失败: " + e.getMessage());
        }
    }

    // ==================== 运行时配置访问 ====================

    private static ModConfig instance;

    public static ModConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    public static void reload() {
        instance = load();
    }
}
