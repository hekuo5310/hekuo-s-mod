package com.hekuo.mod.tracker;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.*;

/**
 * 末地烛交互追踪器
 *
 * 功能规则：
 * - 玩家A用末地烛右键玩家B -> 施加失明III 5s + 缓慢V 10s
 * - 20s内重复右键 -> 冷却15s不施加效果
 * - 每次右键公屏金色字体："【玩家A】使用末地烛c了【玩家B】！"
 * - 5秒内被右键20次 -> 30%概率获得特殊牛奶桶
 *   - 牛奶桶备注: 金色字体 "你真的要喝掉它吗awa？"
 *   - 喝完: 回收桶，回复5点生命，10s饱和V，5s抗性提升V
 *   - 公屏粉色字体: "【饮用玩家】喝掉了【玩家B】的"牛奶！""
 * - 一个游戏日内被末地烛右键超500次 -> 再次被右键直接死亡
 * - 排除Carpet假人
 */
public class EndRodTracker {

    // 玩家A -> 玩家B -> 上次右键时间
    private static final Map<UUID, Map<UUID, Long>> lastInteractTime = new HashMap<>();

    // 玩家A -> 玩家B -> 冷却结束时间
    private static final Map<UUID, Map<UUID, Long>> cooldownEnd = new HashMap<>();

    // 玩家B -> 5秒内的右键次数
    private static final Map<UUID, List<Long>> recentRightClicks = new HashMap<>();

    // 玩家B -> 一个游戏日内的总右键次数
    private static final Map<UUID, Integer> dailyClickCount = new HashMap<>();

    // 游戏日重置追踪
    private static long lastDayCheck = 0;

    // 特殊牛奶桶NBT标签
    private static final String SPECIAL_MILK_TAG = "hekuos_end_rod_milk";
    private static final String MILK_SOURCE_TAG = "hekuos_milk_source";

    /**
     * 处理末地烛右键玩家事件
     *
     * @return true如果事件被处理（应该取消原版行为）
     */
    public static boolean onEndRodInteract(ServerPlayerEntity playerA, ServerPlayerEntity playerB,
                                            MinecraftServer server) {
        // 检查是否被右键超过500次（游戏日内）
        int dailyCount = dailyClickCount.getOrDefault(playerB.getUuid(), 0);
        if (dailyCount >= 500) {
            // 直接死亡
            playerB.kill();
            playerB.sendMessage(Text.literal("你在一个游戏日内被末地烛右键了太多次...")
                .formatted(Formatting.RED), true);
            return true;
        }

        // 同样检查玩家A的右键次数
        int dailyCountA = dailyClickCount.getOrDefault(playerA.getUuid(), 0);
        if (dailyCountA >= 500) {
            playerA.kill();
            playerA.sendMessage(Text.literal("你在一个游戏日内右键了太多次...")
                .formatted(Formatting.RED), true);
            return true;
        }

        long currentTick = server.getOverworld().getTime();

        // 更新每日计数
        checkDailyReset(currentTick);
        dailyClickCount.merge(playerB.getUuid(), 1, Integer::sum);
        dailyClickCount.merge(playerA.getUuid(), 1, Integer::sum);

        // 检查冷却
        Map<UUID, Long> aCooldowns = cooldownEnd.computeIfAbsent(playerA.getUuid(), k -> new HashMap<>());
        Long cooldownEnd = aCooldowns.get(playerB.getUuid());

        // 广播消息 - 无论是否在冷却中都要发
        String msgA = playerA.getName().getString();
        String msgB = playerB.getName().getString();
        server.getPlayerManager().broadcast(
            Text.literal(msgA + "使用末地烛c了" + msgB + "！")
                .formatted(Formatting.GOLD),
            false
        );

        if (cooldownEnd != null && currentTick < cooldownEnd) {
            // 在冷却期内，不施加效果
            return true;
        }

        // 检查是否在20秒内重复右键（20s = 400tick）
        Map<UUID, Long> aLastTimes = lastInteractTime.computeIfAbsent(playerA.getUuid(), k -> new HashMap<>());
        Long lastTime = aLastTimes.get(playerB.getUuid());

        if (lastTime != null && (currentTick - lastTime) < 400) {
            // 20秒内重复右键，进入15秒冷却
            aCooldowns.put(playerB.getUuid(), currentTick + 300); // 15s = 300tick
            aLastTimes.put(playerB.getUuid(), currentTick);
            return true;
        }

        // 施加效果
        aLastTimes.put(playerB.getUuid(), currentTick);

        // 失明III 5秒 (100tick)
        playerB.addStatusEffect(new StatusEffectInstance(
            StatusEffects.BLINDNESS, 100, 2, false, true, true
        ));

        // 缓慢V 10秒 (200tick)
        playerB.addStatusEffect(new StatusEffectInstance(
            StatusEffects.SLOWNESS, 200, 4, false, true, true
        ));

        // 记录5秒内的右键次数
        List<Long> recentClicks = recentRightClicks.computeIfAbsent(playerB.getUuid(), k -> new ArrayList<>());
        recentClicks.add(currentTick);

        // 清理超过5秒的记录
        recentClicks.removeIf(time -> (currentTick - time) > 100); // 5s = 100tick

        // 检查5秒内是否有20次右键
        if (recentClicks.size() >= 20) {
            // 30%概率获得特殊牛奶
            Random random = new Random();
            if (random.nextDouble() < 0.30) {
                giveSpecialMilk(playerB, playerA, server);
            }
            // 重置计数
            recentClicks.clear();
        }

        return true;
    }

    /**
     * 给玩家特殊牛奶桶
     */
    private static void giveSpecialMilk(ServerPlayerEntity sourcePlayer, ServerPlayerEntity clicker,
                                          MinecraftServer server) {
        ItemStack milkBucket = new ItemStack(Items.MILK_BUCKET);

        // 自定义数据标记 - 存入 CUSTOM_DATA 组件 (1.21 不再使用物品 NBT)
        NbtCompound nbt = new NbtCompound();
        nbt.putInt(SPECIAL_MILK_TAG, 1);
        nbt.putString(MILK_SOURCE_TAG, sourcePlayer.getName().getString());
        milkBucket.set(DataComponentTypes.CUSTOM_DATA, NbtComponent.of(nbt));

        // 设置显示名 - 金色字体 "你真的要喝掉它吗awa？"
        milkBucket.set(DataComponentTypes.CUSTOM_NAME,
            Text.literal("你真的要喝掉它吗awa？").formatted(Formatting.GOLD)
        );

        // 设置Lore
        milkBucket.set(DataComponentTypes.LORE, new LoreComponent(java.util.List.of(
            Text.literal("来自" + sourcePlayer.getName().getString() + "的\"牛奶\"")
                .formatted(Formatting.LIGHT_PURPLE)
        )));

        // 给予点击者
        clicker.giveItemStack(milkBucket);
        clicker.sendMessage(
            Text.literal("你获得了一桶特殊的\"牛奶\"...").formatted(Formatting.GOLD),
            true
        );
    }

    /**
     * 检查物品是否是特殊牛奶桶
     */
    public static boolean isSpecialMilk(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        NbtComponent comp = stack.get(DataComponentTypes.CUSTOM_DATA);
        NbtCompound nbt = comp != null ? comp.getNbt() : null;
        return nbt != null && nbt.contains(SPECIAL_MILK_TAG);
    }

    /**
     * 获取牛奶来源玩家名称
     */
    public static String getMilkSourceName(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        NbtComponent comp = stack.get(DataComponentTypes.CUSTOM_DATA);
        NbtCompound nbt = comp != null ? comp.getNbt() : null;
        if (nbt != null && nbt.contains(MILK_SOURCE_TAG)) {
            return nbt.getString(MILK_SOURCE_TAG);
        }
        return null;
    }

    /**
     * 处理特殊牛奶被喝下的效果
     */
    public static void onSpecialMilkDrink(ServerPlayerEntity player, ItemStack milkBucket,
                                            MinecraftServer server) {
        if (!isSpecialMilk(milkBucket)) return;

        String sourceName = getMilkSourceName(milkBucket);
        String drinkerName = player.getName().getString();

        // 回收桶（喝完不返还空桶）
        milkBucket.decrement(1);
        // 给予空桶
        player.giveItemStack(new ItemStack(Items.BUCKET));

        // 回复5点生命值
        player.heal(5.0f);

        // 10秒饱和V (200tick)
        player.addStatusEffect(new StatusEffectInstance(
            StatusEffects.SATURATION, 200, 4, false, true, true
        ));

        // 5秒抗性提升V (100tick)
        player.addStatusEffect(new StatusEffectInstance(
            StatusEffects.RESISTANCE, 100, 4, false, true, true
        ));

        // 公屏粉色字体广播
        server.getPlayerManager().broadcast(
            Text.literal(drinkerName + "喝掉了" + sourceName + "的\"牛奶！\"")
                .formatted(Formatting.LIGHT_PURPLE),
            false
        );
    }

    /**
     * 检查游戏日重置
     */
    private static void checkDailyReset(long currentTick) {
        // 一个游戏日 = 24000tick
        long currentDay = currentTick / 24000;
        if (currentDay != lastDayCheck) {
            lastDayCheck = currentDay;
            dailyClickCount.clear();
            recentRightClicks.clear();
        }
    }

    /**
     * 每tick调用 - 清理过期数据
     */
    public static void onServerTick(MinecraftServer server) {
        long currentTick = server.getOverworld().getTime();

        // 每6000tick（5分钟）清理一次过期数据
        if (currentTick % 6000 != 0) return;

        // 清理过期的交互时间记录
        lastInteractTime.forEach((uuidA, map) ->
            map.entrySet().removeIf(entry -> (currentTick - entry.getValue()) > 24000)
        );

        // 清理过期的冷却记录
        cooldownEnd.forEach((uuidA, map) ->
            map.entrySet().removeIf(entry -> currentTick > entry.getValue())
        );
    }

    /**
     * 清理所有追踪数据
     */
    public static void clear() {
        lastInteractTime.clear();
        cooldownEnd.clear();
        recentRightClicks.clear();
        dailyClickCount.clear();
    }
}
