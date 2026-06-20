package com.hekuo.mod.mixin;

import com.hekuo.mod.config.ModConfig;
import com.hekuo.mod.tracker.EndRodTracker;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.MilkBucketItem;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 牛奶桶Mixin - 处理特殊牛奶桶的饮用效果
 */
@Mixin(MilkBucketItem.class)
public class MilkBucketItemMixin {

    /**
     * 拦截牛奶桶使用，检测是否是特殊牛奶
     */
    @Inject(method = "finishUsing", at = @At("HEAD"))
    private void onFinishUsing(ItemStack stack, World world, PlayerEntity user,
                                CallbackInfoReturnable<ItemStack> cir) {
        if (!ModConfig.get().endRodInteractionEnabled) return;
        if (world.isClient()) return;

        // 检查是否是特殊牛奶
        if (EndRodTracker.isSpecialMilk(stack) && user instanceof ServerPlayerEntity serverPlayer) {
            EndRodTracker.onSpecialMilkDrink(serverPlayer, stack, serverPlayer.getServer());
        }
    }
}
