package com.hekuo.mod;

import com.hekuo.mod.config.ModConfig;
import com.hekuo.mod.distribution.UdpDistributionClient;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
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

        // 进服后自动触发 UDP mod 同步（若开启）
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            ModConfig config = ModConfig.get();
            if (config.udpDistributionEnabled) {
                ModConfig.UdpDistributionConfig udp = config.udpDistributionConfig;
                UdpDistributionClient.getInstance().startSync(
                    udp.serverHost, udp.port, udp.chunkSize,
                    udp.ackTimeoutMs, udp.maxRetries, udp.maxFileSizeMb
                );
            }
        });

        CLIENT_LOGGER.info("Hekuo's Mod 客户端初始化完成");
    }
}
