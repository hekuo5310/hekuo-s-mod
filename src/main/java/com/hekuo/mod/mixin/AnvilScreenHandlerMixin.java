package com.hekuo.mod.mixin;

import com.hekuo.mod.config.ModConfig;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.screen.AnvilScreenHandler;
import net.minecraft.screen.ForgingScreenHandler;
import org.spongepowered.asm.mixin.Mixin;
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

    public AnvilScreenHandlerMixin() {
        super(null, 0, null, null);
    }

    /**
     * 通过遍历附魔组件按 RegistryKey 匹配，避开 dynamic registry 解析
     */
    private static int getEnchantLevelByKey(ItemStack stack,
                                              net.minecraft.registry.RegistryKey<Enchantment> key) {
        ItemEnchantmentsComponent comp = stack.get(DataComponentTypes.ENCHANTMENTS);
        if (comp == null) return 0;
        for (RegistryEntry<Enchantment> entry : comp.getEnchantments()) {
            if (entry.matchesKey(key)) return comp.getLevel(entry);
        }
        return 0;
    }

    /**
     * 修改铁砧更新逻辑，允许水桶接受火焰保护附魔
     */
    @Inject(method = "updateResult", at = @At("HEAD"), cancellable = true)
    private void onUpdateResult(CallbackInfo ci) {
        if (!ModConfig.get().fireProtectionWaterBucketEnabled) return;

        // 1.21.1: input/output 是 protected 字段，本Mixin extends ForgingScreenHandler 可访问
        Inventory inputInv = this.input;
        ItemStack firstSlot = inputInv.getStack(0);
        ItemStack secondSlot = inputInv.getStack(1);

        // 检查：第一个槽位是水桶，第二个槽位含有火焰保护附魔
        if (firstSlot.getItem() != Items.WATER_BUCKET || secondSlot.isEmpty()) return;

        int fireProtectionLevel = getEnchantLevelByKey(secondSlot, Enchantments.FIRE_PROTECTION);
        if (fireProtectionLevel <= 0 || fireProtectionLevel > 4) return;

        // 从 secondSlot 取该附魔的 RegistryEntry（已存在于该附魔书中）
        ItemEnchantmentsComponent secondComp = secondSlot.get(DataComponentTypes.ENCHANTMENTS);
        if (secondComp == null) return;
        RegistryEntry<Enchantment> fireProtEntry = null;
        for (RegistryEntry<Enchantment> entry : secondComp.getEnchantments()) {
            if (entry.matchesKey(Enchantments.FIRE_PROTECTION)) {
                fireProtEntry = entry;
                break;
            }
        }
        if (fireProtEntry == null) return;

        // 创建附魔结果
        ItemStack result = firstSlot.copy();
        ItemEnchantmentsComponent.Builder enchBuilder = new ItemEnchantmentsComponent.Builder(
            result.getOrDefault(DataComponentTypes.ENCHANTMENTS, ItemEnchantmentsComponent.DEFAULT)
        );
        enchBuilder.add(fireProtEntry, fireProtectionLevel);
        result.set(DataComponentTypes.ENCHANTMENTS, enchBuilder.build());

        // 设置输出槽位
        this.output.setStack(0, result);
    }
}
