package com.hekuo.mod.mixin.server;

import com.hekuo.mod.HekuosMod;
import com.hekuo.mod.config.ModConfig;
import com.hekuo.mod.onebot.OneBotBridge;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 玩家管理器Mixin - 用于OneBot事件转发（玩家加入/离开/死亡）
 */
@Mixin(PlayerManager.class)
public class PlayerManagerMixin {

    /**
     * 玩家加入服务器 - 转发到OneBot
     */
    @Inject(method = "onPlayerConnect", at = @At("TAIL"))
    private void onPlayerConnect(CallbackInfo ci) {
        // 参数无法直接获取，使用备用方式
    }

    /**
     * 玩家离开服务器 - 转发到OneBot
     */
    @Inject(method = "remove", at = @At("HEAD"))
    private void onPlayerRemove(ServerPlayerEntity player, CallbackInfo ci) {
        if (!ModConfig.get().oneBotEnabled) return;
        try {
            OneBotBridge.getInstance().forwardPlayerLeave(player);
        } catch (Exception e) {
            HekuosMod.LOGGER.error("转发离开消息到OneBot失败", e);
        }
    }
}
