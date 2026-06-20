package com.hekuo.mod;

import com.hekuo.mod.ai.ConversationManager;
import com.hekuo.mod.command.ModCommands;
import com.hekuo.mod.config.ModConfig;
import com.hekuo.mod.onebot.OneBotBridge;
import com.hekuo.mod.tracker.EndRodTracker;
import com.hekuo.mod.tracker.WaterEvaporationTracker;
import com.hekuo.mod.web.StatusServerManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HekuosMod implements ModInitializer {

    public static final String MOD_ID = "hekuos-mod";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("Hekuo's Mod 正在初始化...");

        // 加载配置
        ModConfig config = ModConfig.get();
        LOGGER.info("配置已加载");

        // 注册命令
        CommandRegistrationCallback.EVENT.register(ModCommands::register);

        // 注册水蒸发追踪器的tick事件
        ServerTickEvents.END_SERVER_TICK.register(WaterEvaporationTracker::onServerTick);

        // 注册末地烛追踪器的tick事件
        ServerTickEvents.END_SERVER_TICK.register(EndRodTracker::onServerTick);

        // 服务器启动时初始化
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            // 设置OneBot服务器实例
            OneBotBridge.getInstance().setServer(server);

            // 启动OneBot连接
            if (config.oneBotEnabled) {
                OneBotBridge.getInstance().connect(config.oneBotConfig);
            }

            // 启动状态网页服务
            if (config.webStatusEnabled) {
                StatusServerManager.getInstance().start(server, config.webStatusConfig);
            }

            LOGGER.info("Hekuo's Mod 服务器端已启动");
        });

        // 服务器停止时清理
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            OneBotBridge.getInstance().disconnect();
            StatusServerManager.getInstance().stop();
            ConversationManager.getInstance().saveConversations();
            LOGGER.info("Hekuo's Mod 已停止");
        });

        // 保存配置
        config.save();

        LOGGER.info("Hekuo's Mod 初始化完成!");
    }
}
