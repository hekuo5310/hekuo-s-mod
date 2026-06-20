package com.hekuo.mod;

import com.hekuo.mod.config.ModConfig;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 客户端入口 - 仅用于客户端独有的功能
 * 大部分功能在服务端运行，客户端可选安装
 */
public class HekuosModClient implements ClientModInitializer {

    public static final Logger CLIENT_LOGGER = LoggerFactory.getLogger("hekuos-mod-client");

    @Override
    public void onInitializeClient() {
        CLIENT_LOGGER.info("Hekuo's Mod 客户端初始化...");

        // 客户端特有的功能（如特殊渲染）可在此注册
        // 大部分游戏逻辑在服务端处理

        CLIENT_LOGGER.info("Hekuo's Mod 客户端初始化完成");
    }
}
