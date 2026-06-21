package com.hekuo.mod.mixin;

import com.hekuo.mod.HekuosMod;
import com.hekuo.mod.config.ModConfig;
import com.hekuo.mod.tracker.WaterEvaporationTracker;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.IceBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 冰块Mixin - 当下界的水旁边有冰块时，冰块变霜冰，水多存活2400刻
 *
 * 注意：不包括浮冰(Packed Ice)和蓝冰(Blue Ice)
 *
 * 1.21.1: IceBlock 已不再 override onBlockAdded（移到 Block 父类），
 * 此 Mixin 仅在 randomTick 时检查，不再 hook 放置事件。
 */
@Mixin(IceBlock.class)
public class IceBlockMixin {

    /**
     * 在冰块随机tick时检查附近水并转换
     */
    @Inject(method = "randomTick", at = @At("HEAD"))
    private void onRandomTick(BlockState state, ServerWorld world, BlockPos pos, Random random, CallbackInfo ci) {
        if (!ModConfig.get().netherIceToFrostedIceEnabled) return;

        // 只在下界生效
        if (!world.getRegistryKey().equals(World.NETHER)) return;

        // 检查是否是普通冰块（不包括浮冰和蓝冰）
        Block block = state.getBlock();
        if (block != Blocks.ICE) return;

        // 检查附近是否有水
        checkNearbyWaterAndConvert(world, pos);
    }

    /**
     * 检查冰块附近是否有水，如果有的话：
     * 1. 将冰块变为霜冰
     * 2. 给水额外2400刻的存活时间
     */
    private void checkNearbyWaterAndConvert(World world, BlockPos icePos) {
        boolean hasNearbyWater = false;

        // 检查6个方向
        for (BlockPos neighbor : new BlockPos[]{
            icePos.up(), icePos.down(), icePos.north(),
            icePos.south(), icePos.east(), icePos.west()
        }) {
            if (world.getBlockState(neighbor).isOf(Blocks.WATER)) {
                hasNearbyWater = true;
                // 给水额外存活时间
                WaterEvaporationTracker.refreshIceBonus(world, neighbor);
            }
        }

        // 如果附近有水，将冰块变为霜冰
        if (hasNearbyWater) {
            world.setBlockState(icePos, Blocks.FROSTED_ICE.getDefaultState(), 3);
            HekuosMod.LOGGER.debug("下界冰块 ({}) 已转换为霜冰", icePos);
        }
    }
}
