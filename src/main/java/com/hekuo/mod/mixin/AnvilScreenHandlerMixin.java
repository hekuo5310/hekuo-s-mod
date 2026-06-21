package com.hekuo.mod.mixin;

import com.hekuo.mod.config.ModConfig;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.screen.AnvilScreenHandler;
import net.minecraft.screen.ForgingScreenHandler;
import net.minecraft.screen.slot.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Optional;

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
     * 修改铁砧更新逻辑，允许水桶接受火焰保护附魔
     */
    @Inject(method = "updateResult", at = @At("HEAD"), cancellable = true)
    private void onUpdateResult(CallbackInfo ci) {
        if (!ModConfig.get().fireProtectionWaterBucketEnabled) return;

        AnvilScreenHandler self = (AnvilScreenHandler) (Object) this;
        // 1.21.1: input/output 是 protected 字段，子类（本Mixin）可访问
        Inventory input = this.input;
        ItemStack firstSlot = input.getStack(0);
        ItemStack secondSlot = input.getStack(1);

        // 检查：第一个槽位是水桶，第二个槽位含有火焰保护附魔
        if (firstSlot.getItem() != Items.WATER_BUCKET || secondSlot.isEmpty()) return;

        // 1.21.1: 附魔为 RegistryEntry<Enchantment>
        Optional<RegistryEntry.Reference<Enchantment>> fireProtOpt =
            Registries.ENCHANTMENT.getEntry(Enchantments.FIRE_PROTECTION);
        if (fireProtOpt.isEmpty()) return;
        RegistryEntry<Enchantment> fireProt = fireProtOpt.get();

        int fireProtectionLevel = net.minecraft.enchantment.EnchantmentHelper
                .getLevel(fireProt, secondSlot);

        if (fireProtectionLevel <= 0 || fireProtectionLevel > 4) return;

        // 创建附魔结果
        ItemStack result = firstSlot.copy();
        // 1.21.1: 附魔通过组件写入
        ItemEnchantmentsComponent.Builder enchBuilder = new ItemEnchantmentsComponent.Builder(
            result.getOrDefault(DataComponentTypes.ENCHANTMENTS, ItemEnchantmentsComponent.DEFAULT)
        );
        enchBuilder.add(fireProt, fireProtectionLevel);
        result.set(DataComponentTypes.ENCHANTMENTS, enchBuilder.build());

        // 设置输出槽位
        this.output.setStack(0, result);
    }
}
