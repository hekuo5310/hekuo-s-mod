package com.hekuo.mod.util;

import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Carpet假人检测器
 *
 * 1.21.1: ServerCommonNetworkHandler.connection / ClientConnection.channel /
 * ServerCommonNetworkHandler.latency 都是 protected/private，
 * 无法在 mod 层直接访问。退化为仅靠命名模式判断（Carpet 默认命名足够区分）。
 * 如需更精确检测，可考虑用 Mixin Accessor 暴露这些字段。
 */
public class CarpetFakePlayerDetector {

    /**
     * 检测玩家是否是Carpet假人
     */
    public static boolean isCarpetFakePlayer(ServerPlayerEntity player) {
        if (player == null) return false;

        String name = player.getName().getString();

        // Carpet假人常见的命名模式
        if (name.startsWith("bot_") || name.startsWith("npb_") ||
            name.startsWith("camera-") || name.contains("[Camera]") ||
            name.contains("[Bot]") || name.startsWith("fake_")) {
            return true;
        }

        return false;
    }

    /**
     * 更严格的假人检测 - 用于需要精确判断的场景
     */
    public static boolean isDefinitelyFakePlayer(ServerPlayerEntity player) {
        if (player == null) return false;

        String name = player.getName().getString();

        // 明确的Carpet假人命名模式
        if (name.startsWith("bot_") || name.startsWith("npb_") ||
            name.startsWith("camera-")) {
            return true;
        }

        return false;
    }
}
