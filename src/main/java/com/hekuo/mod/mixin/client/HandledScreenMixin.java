package com.hekuo.mod.mixin.client;

import com.hekuo.mod.config.ModConfig;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.AnvilScreen;
import net.minecraft.screen.AnvilScreenHandler;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 客户端铁砧屏幕Mixin - 在客户端显示水桶可以附魔火焰保护的提示
 */
@Mixin(HandledScreen.class)
public class HandledScreenMixin {

    @Inject(method = "render", at = @At("TAIL"))
    private void onRender(CallbackInfo ci) {
        // 客户端渲染提示（可选功能）
        // 主要逻辑在服务端处理
    }
}
