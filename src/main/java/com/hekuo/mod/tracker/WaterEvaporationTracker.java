package com.hekuo.mod.tracker;

import net.minecraft.block.Blocks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.*;

/**
 * 水蒸发追踪器 - 管理下界中附魔水桶放出的水的蒸发逻辑
 *
 * 蒸发规则：
 * - 火焰保护1: 每40tick有70%蒸发概率, 100tick后强制消失
 * - 火焰保护2: 每50tick有60%蒸发概率, 160tick后强制消失
 * - 火焰保护3: 每70tick有55%蒸发概率, 190tick后强制消失
 * - 火焰保护4: 每100tick有50%蒸发概率, 240tick后强制消失
 *
 * 冰块额外加成: 附近有霜冰时额外存活2400tick
 */
public class WaterEvaporationTracker {

    // 水源记录
    private static final Map<WaterKey, WaterEntry> activeWaters = new LinkedHashMap<>();

    public static class WaterKey {
        public final String dimension;
        public final BlockPos pos;

        public WaterKey(String dimension, BlockPos pos) {
            this.dimension = dimension;
            this.pos = pos.toImmutable();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof WaterKey)) return false;
            WaterKey that = (WaterKey) o;
            return dimension.equals(that.dimension) && pos.equals(that.pos);
        }

        @Override
        public int hashCode() {
            return Objects.hash(dimension, pos);
        }
    }

    public static class WaterEntry {
        public final int fireProtectionLevel;
        public long placedTick;
        public long forcedRemoveTick;
        public long checkInterval;
        public double evapChance;
        public long bonusTicks; // 冰块额外加成
        public boolean removed;

        public WaterEntry(int level, long currentTick) {
            this.fireProtectionLevel = level;
            this.placedTick = currentTick;
            this.bonusTicks = 0;
            this.removed = false;

            switch (level) {
                case 1:
                    this.checkInterval = 40;
                    this.evapChance = 0.70;
                    this.forcedRemoveTick = currentTick + 100;
                    break;
                case 2:
                    this.checkInterval = 50;
                    this.evapChance = 0.60;
                    this.forcedRemoveTick = currentTick + 160;
                    break;
                case 3:
                    this.checkInterval = 70;
                    this.evapChance = 0.55;
                    this.forcedRemoveTick = currentTick + 190;
                    break;
                case 4:
                    this.checkInterval = 100;
                    this.evapChance = 0.50;
                    this.forcedRemoveTick = currentTick + 240;
                    break;
                default:
                    // 超过4级按4级处理
                    this.checkInterval = 100;
                    this.evapChance = 0.50;
                    this.forcedRemoveTick = currentTick + 240;
                    break;
            }
        }

        /**
         * 计算当前强制消失时间（含冰块加成）
         */
        public long getEffectiveForcedRemoveTick() {
            return forcedRemoveTick + bonusTicks;
        }
    }

    /**
     * 注册一个下界水源
     */
    public static void registerWater(World world, BlockPos pos, int fireProtectionLevel) {
        if (!world.getRegistryKey().equals(World.NETHER)) return;

        String dimension = world.getRegistryKey().getValue().toString();
        WaterKey key = new WaterKey(dimension, pos);

        // 检查附近是否有冰块/霜冰，给予额外存活时间
        long bonusTicks = calculateIceBonus(world, pos);

        WaterEntry entry = new WaterEntry(fireProtectionLevel, world.getServer().getOverworld().getTime());
        entry.bonusTicks = bonusTicks;

        activeWaters.put(key, entry);
    }

    /**
     * 检查附近是否有冰块，计算额外存活tick
     * 功能3：当下界的水旁边有冰块时，冰块变霜冰，水多存活2400刻
     */
    private static long calculateIceBonus(World world, BlockPos waterPos) {
        // 检查6个方向的方块
        boolean hasIceNearby = false;
        for (BlockPos neighbor : getNeighbors(waterPos)) {
            if (world.getBlockState(neighbor).isOf(Blocks.ICE)) {
                hasIceNearby = true;
                // 将冰块转换为霜冰
                world.setBlockState(neighbor, Blocks.FROSTED_ICE.getDefaultState());
            }
        }

        // 检查更远范围(2格)
        if (!hasIceNearby) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dy = -2; dy <= 2; dy++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        if (Math.abs(dx) + Math.abs(dy) + Math.abs(dz) > 3) continue;
                        BlockPos checkPos = waterPos.add(dx, dy, dz);
                        if (world.getBlockState(checkPos).isOf(Blocks.ICE)) {
                            hasIceNearby = true;
                            world.setBlockState(checkPos, Blocks.FROSTED_ICE.getDefaultState());
                        }
                    }
                }
            }
        }

        return hasIceNearby ? 2400L : 0L;
    }

    /**
     * 刷新冰块加成（当新的冰块出现在水附近时调用）
     */
    public static void refreshIceBonus(World world, BlockPos waterPos) {
        String dimension = world.getRegistryKey().getValue().toString();
        WaterKey key = new WaterKey(dimension, waterPos);
        WaterEntry entry = activeWaters.get(key);
        if (entry != null && entry.bonusTicks < 2400L) {
            entry.bonusTicks = 2400L;
        }
    }

    private static List<BlockPos> getNeighbors(BlockPos pos) {
        return Arrays.asList(
            pos.up(), pos.down(), pos.north(), pos.south(), pos.east(), pos.west()
        );
    }

    /**
     * 每tick调用，检查蒸发逻辑
     */
    public static void onServerTick(MinecraftServer server) {
        if (activeWaters.isEmpty()) return;

        long currentTick = server.getOverworld().getTime();
        Random random = new Random();

        Iterator<Map.Entry<WaterKey, WaterEntry>> it = activeWaters.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<WaterKey, WaterEntry> mapEntry = it.next();
            WaterKey key = mapEntry.getKey();
            WaterEntry entry = mapEntry.getValue();

            if (entry.removed) {
                it.remove();
                continue;
            }

            // 找到对应的世界
            ServerWorld world = null;
            for (ServerWorld sw : server.getWorlds()) {
                if (sw.getRegistryKey().getValue().toString().equals(key.dimension)) {
                    world = sw;
                    break;
                }
            }

            if (world == null) {
                it.remove();
                continue;
            }

            // 检查水是否还存在（可能被玩家手动移除）
            if (!world.getBlockState(key.pos).isOf(Blocks.WATER)) {
                it.remove();
                continue;
            }

            long elapsed = currentTick - entry.placedTick;
            long effectiveForcedRemove = entry.getEffectiveForcedRemoveTick();

            // 强制消失
            if (currentTick >= effectiveForcedRemove) {
                removeWater(world, key.pos);
                it.remove();
                continue;
            }

            // 定期蒸发检查
            if (elapsed > 0 && elapsed % entry.checkInterval == 0) {
                if (random.nextDouble() < entry.evapChance) {
                    removeWater(world, key.pos);
                    it.remove();
                }
            }
        }
    }

    /**
     * 移除水源方块
     */
    private static void removeWater(ServerWorld world, BlockPos pos) {
        if (world.getBlockState(pos).isOf(Blocks.WATER)) {
            world.setBlockState(pos, Blocks.AIR.getDefaultState());
        }
    }

    /**
     * 清理所有追踪的水
     */
    public static void clear() {
        activeWaters.clear();
    }
}
