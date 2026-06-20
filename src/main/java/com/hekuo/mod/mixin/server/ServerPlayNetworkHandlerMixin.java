package com.hekuo.mod.mixin.server;

import com.hekuo.mod.HekuosMod;
import com.hekuo.mod.config.ModConfig;
import com.hekuo.mod.onebot.OneBotBridge;
import net.minecraft.network.message.ChatMessage;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 网络处理器Mixin - 用于OneBot消息转发
 * 拦截聊天消息、玩家加入/离开等事件
 */
@Mixin(ServerPlayNetworkHandler.class)
public class ServerPlayNetworkHandlerMixin {

    @Shadow
    public ServerPlayerEntity player;

    /**
     * 拦截玩家聊天消息 - 转发到OneBot
     */
    @Inject(method = "handleChatMessage", at = @At("HEAD"))
    private void onChatMessage(ChatMessage message, CallbackInfo ci) {
        if (!ModConfig.get().oneBotEnabled) return;
        try {
            String chatContent = message.getContent().getString();
            OneBotBridge.getInstance().forwardPlayerMessage(player, chatContent);
        } catch (Exception e) {
            HekuosMod.LOGGER.error("转发聊天消息到OneBot失败", e);
        }
    }
}
