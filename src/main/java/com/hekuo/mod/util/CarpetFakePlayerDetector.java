package com.hekuo.mod.util;

import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Carpet假人检测器
 * Carpet mod生成的假人特征：
 * 1. 延迟为0ms
 * 2. 名字中包含特殊标记（如 [Camera] 或前缀 bot/npb 等）
 * 3. 通过Carpet的特定NBT标签标记
 */
public class CarpetFakePlayerDetector {

    /**
     * 检测玩家是否是Carpet假人
     */
    public static boolean isCarpetFakePlayer(ServerPlayerEntity player) {
        if (player == null) return false;

        // 方法1: 检查延迟 - Carpet假人延迟为0
        // 真实玩家通常会有一定延迟，但localhost玩家也可能为0
        // 所以这只是辅助判断

        // 方法2: 检查玩家名称特征
        String name = player.getName().getString();

        // Carpet假人常见的命名模式
        if (name.startsWith("bot_") || name.startsWith("npb_") ||
            name.startsWith("camera-") || name.contains("[Camera]") ||
            name.contains("[Bot]") || name.startsWith("fake_")) {
            return true;
        }

        // 方法3: 检查连接特征
        // Carpet假人通过FakePlayerManager创建
        // 它们的网络连接与真实玩家不同
        try {
            // 假人的网络Handler类型不同
            var connection = player.networkHandler;
            if (connection != null) {
                var channel = connection.getConnection().getChannel();
                if (channel == null || !channel.isActive()) {
                    // 没有活跃网络连接的"玩家"很可能是假人
                    return true;
                }
            }
        } catch (Exception e) {
            // 如果获取网络信息失败，可能是假人
            return true;
        }

        // 方法4: 检查延迟为0的特殊情况
        try {
            // Carpet假人的延迟总是0
            var playerListEntry = player.networkHandler;
            if (playerListEntry != null) {
                int latency = player.pingMilliseconds;
                // 如果延迟为0且不是单人游戏，很可能是假人
                // 但需要谨慎，因为局域网玩家可能也是0
            }
        } catch (Exception ignored) {}

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

        // 检查是否是通过Carpet创建的
        try {
            // Carpet假人没有真实的网络连接
            var connection = player.networkHandler;
            if (connection == null) return true;

            var netConn = connection.getConnection();
            if (netConn == null) return true;

            var channel = netConn.getChannel();
            if (channel == null || !channel.isOpen()) return true;

        } catch (Exception e) {
            return true;
        }

        return false;
    }
}
