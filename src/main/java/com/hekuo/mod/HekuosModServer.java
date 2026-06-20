package com.hekuo.mod;

import com.hekuo.mod.config.ModConfig;
import net.fabricmc.api.DedicatedServerModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 服务端专用入口 - 仅服务端可用功能初始化
 */
public class HekuosModServer implements DedicatedServerModInitializer {

    public static final Logger SERVER_LOGGER = LoggerFactory.getLogger("hekuos-mod-server");

    @Override
    public void onInitializeServer() {
        SERVER_LOGGER.info("Hekuo's Mod 服务端专用功能初始化...");

        // OneBot和状态网页等仅服务端功能在此初始化
        // 主要逻辑在HekuosMod.onInitialize中处理

        SERVER_LOGGER.info("Hekuo's Mod 服务端专用功能初始化完成");
    }
}
