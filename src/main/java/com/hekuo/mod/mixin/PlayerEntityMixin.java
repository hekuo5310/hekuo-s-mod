package com.hekuo.mod.mixin;

import com.hekuo.mod.HekuosMod;
import com.hekuo.mod.config.ModConfig;
import com.hekuo.mod.tracker.EndRodTracker;
import com.hekuo.mod.util.CarpetFakePlayerDetector;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Formatting;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 玩家实体Mixin - 拦截末地烛右键玩家事件
 */
@Mixin(PlayerEntity.class)
public abstract class PlayerEntityMixin extends LivingEntity {

    protected PlayerEntityMixin(EntityType<? extends LivingEntity> entityType, World world) {
        super(entityType, world);
    }

    /**
     * 拦截玩家交互事件 - 检测末地烛右键玩家
     */
    @Inject(method = "interact", at = @At("HEAD"), cancellable = true)
    private void onInteract(PlayerEntity player, Hand hand, CallbackInfoReturnable<ActionResult> cir) {
        if (!ModConfig.get().endRodInteractionEnabled) return;
        if (this.getWorld().isClient()) return;

        PlayerEntity self = (PlayerEntity) (Object) this;
        PlayerEntity other = player;

        // 检查手持物品是否是末地烛
        ItemStack heldItem = other.getStackInHand(hand);
        if (heldItem.getItem() != Items.END_ROD) return;

        // 只在服务端处理
        if (!(other instanceof ServerPlayerEntity serverPlayerA)) return;
        if (!(self instanceof ServerPlayerEntity serverPlayerB)) return;

        // 检查目标是否是Carpet假人
        if (CarpetFakePlayerDetector.isCarpetFakePlayer(serverPlayerB)) {
            serverPlayerA.sendMessage(
                Text.literal("你是真饿了连假人都不放过").formatted(Formatting.RED),
                true
            );
            cir.setReturnValue(ActionResult.FAIL);
            return;
        }

        // 检查使用者是否是Carpet假人
        if (CarpetFakePlayerDetector.isCarpetFakePlayer(serverPlayerA)) {
            cir.setReturnValue(ActionResult.PASS);
            return;
        }

        // 处理末地烛交互
        boolean handled = EndRodTracker.onEndRodInteract(
            serverPlayerA, serverPlayerB, serverPlayerA.getServer()
        );

        if (handled) {
            // 消耗末地烛耐久（如果是创造模式不消耗）
            // 1.21.1: ItemStack.damage(int, LivingEntity, EquipmentSlot)
            if (!other.getAbilities().creativeMode) {
                EquipmentSlot slot = hand == Hand.MAIN_HAND
                    ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND;
                heldItem.damage(1, other, slot);
            }
            cir.setReturnValue(ActionResult.SUCCESS);
        }
    }
}
