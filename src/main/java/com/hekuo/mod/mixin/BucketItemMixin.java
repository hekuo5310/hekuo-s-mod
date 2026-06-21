package com.hekuo.mod.mixin;

import com.hekuo.mod.HekuosMod;
import com.hekuo.mod.config.ModConfig;
import com.hekuo.mod.tracker.WaterEvaporationTracker;
import net.minecraft.block.Blocks;
import net.minecraft.block.FluidBlock;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BucketItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import java.util.Optional;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 水桶Mixin - 允许附魔了火焰保护的水桶在下界放水
 */
@Mixin(BucketItem.class)
public class BucketItemMixin {

    /**
     * 拦截水桶使用方法，在下界时允许附魔了火焰保护的水桶放水
     */
    @Inject(method = "use", at = @At("HEAD"), cancellable = true)
    private void onUse(World world, PlayerEntity user, Hand hand, CallbackInfoReturnable<TypedActionResult<ItemStack>> cir) {
        if (!ModConfig.get().fireProtectionWaterBucketEnabled) return;

        ItemStack stack = user.getStackInHand(hand);
        BucketItem self = (BucketItem) (Object) this;

        // 只处理水桶
        if (stack.getItem() != Items.WATER_BUCKET) return;

        // 检查是否在下界
        if (!world.getRegistryKey().equals(World.NETHER)) return;

        // 检查水桶是否附魔了火焰保护 (1.21.1: 附魔为 RegistryEntry<Enchantment>)
        Optional<RegistryEntry.Reference<Enchantment>> fireProtOpt =
            Registries.ENCHANTMENT.getEntry(Enchantments.FIRE_PROTECTION);
        if (fireProtOpt.isEmpty()) return;
        int fireProtectionLevel = EnchantmentHelper.getLevel(fireProtOpt.get(), stack);
        if (fireProtectionLevel <= 0) return;

        // 获取玩家看向的位置
        HitResult hitResult = user.raycast(5.0, 0.0f, false);
        if (hitResult == null || hitResult.getType() == HitResult.Type.MISS) return;

        BlockPos pos = net.minecraft.util.math.BlockPos.ofFloored(hitResult.getPos());

        // 检查目标位置是否可以放水
        if (world.getBlockState(pos).isAir() || world.getBlockState(pos).isOf(Blocks.WATER)) {
            // 放置水
            world.setBlockState(pos, Blocks.WATER.getDefaultState(), 3);

            // 播放放水音效
            world.playSound(null, pos, SoundEvents.ITEM_BUCKET_EMPTY, SoundCategory.BLOCKS, 1.0f, 1.0f);

            // 注册蒸发追踪
            WaterEvaporationTracker.registerWater(world, pos, fireProtectionLevel);

            // 将水桶变为空桶
            if (!user.getAbilities().creativeMode) {
                stack.decrement(1);
                user.giveItemStack(new ItemStack(Items.BUCKET));
            }

            cir.setReturnValue(TypedActionResult.success(stack, world.isClient()));
        }
    }
}
