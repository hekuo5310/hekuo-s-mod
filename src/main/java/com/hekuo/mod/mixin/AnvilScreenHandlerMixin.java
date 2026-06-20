package com.hekuo.mod.mixin;

import com.hekuo.mod.config.ModConfig;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.AnvilScreenHandler;
import net.minecraft.screen.ForgingScreenHandler;
import net.minecraft.inventory.Inventory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 铁砧Mixin - 允许水桶附魔火焰保护
 *
 * 在铁砧中，将水桶和火焰保护附魔书组合时，
 * 允许产生附魔了火焰保护的水桶
 */
@Mixin(AnvilScreenHandler.class)
public abstract class AnvilScreenHandlerMixin extends ForgingScreenHandler {

    @Shadow
    private int levelCost;

    public AnvilScreenHandlerMixin() {
        super(null, 0, null, null);
    }

    /**
     * 修改铁砧更新逻辑，允许水桶接受火焰保护附魔
     */
    @Inject(method = "updateResult", at = @At("HEAD"), cancellable = true)
    private void onUpdateResult(CallbackInfo ci) {
        if (!ModConfig.get().fireProtectionWaterBucketEnabled) return;

        AnvilScreenHandler self = (AnvilScreenHandler) (Object) this;
        Inventory input = self.input;
        ItemStack firstSlot = input.getStack(0);
        ItemStack secondSlot = input.getStack(1);

        // 检查：第一个槽位是水桶，第二个槽位含有火焰保护附魔
        if (firstSlot.getItem() != Items.WATER_BUCKET || secondSlot.isEmpty()) return;

        int fireProtectionLevel = net.minecraft.enchantment.EnchantmentHelper
                .getLevel(Enchantments.FIRE_PROTECTION, secondSlot);

        if (fireProtectionLevel <= 0 || fireProtectionLevel > 4) return;

        // 创建附魔结果
        ItemStack result = firstSlot.copy();
        result.addEnchantment(Enchantments.FIRE_PROTECTION, fireProtectionLevel);

        // 设置输出槽位
        self.output.setStack(0, result);

        // 设置经验消耗
        this.levelCost = fireProtectionLevel * 2 + 1;

        ci.cancel();
    }
}
