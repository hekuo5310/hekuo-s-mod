package com.hekuo.mod.mixin;

import com.hekuo.mod.HekuosMod;
import com.hekuo.mod.config.ModConfig;
import net.minecraft.structure.pool.StructurePool;
import net.minecraft.structure.pool.StructurePoolElement;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * 村庄结构Mixin - 修复牧羊人小屋不生成的bug
 *
 * Minecraft 1.20.1中，牧羊人小屋(shepherd_house)由于结构池配置问题
 * 导致权重为0或缺失，此Mixin修复该问题
 */
@Mixin(StructurePool.class)
public class ShepherdHouseMixin {

    @Shadow
    private List<StructurePoolElement> elements;

    @Shadow
    private Identifier id;

    /**
     * 在结构池初始化后检查并修复牧羊人小屋
     */
    @Inject(method = "<init>", at = @At("RETURN"))
    private void onInit(CallbackInfo ci) {
        if (!ModConfig.get().fixShepherdHouseEnabled) return;

        String poolId = id.toString();

        // 检查是否是村庄房屋结构池
        if (!poolId.contains("village") || !poolId.contains("houses")) return;

        // 检查是否缺少牧羊人小屋元素
        boolean hasShepherd = elements.stream().anyMatch(e ->
            e.toString().contains("shepherd") || e.toString().contains("shepherd_house")
        );

        if (hasShepherd) return;

        // 牧羊人小屋缺失 - 这是已知的Minecraft bug
        // 确定生物群系类型
        String biome = null;
        if (poolId.contains("plains")) biome = "plains";
        else if (poolId.contains("desert")) biome = "desert";
        else if (poolId.contains("savanna")) biome = "savanna";
        else if (poolId.contains("snowy")) biome = "snowy";
        else if (poolId.contains("taiga")) biome = "taiga";

        if (biome == null) return;

        try {
            Identifier shepherdId = new Identifier("minecraft",
                "village/" + biome + "/houses/" + biome + "_shepherd_house");
            StructurePoolElement shepherdElement = StructurePoolElement.ofSingle(shepherdId.toString())
                    .method_28918(); // setProjection
            // 添加牧羊人小屋（权重2，与其他小屋一致）
            for (int i = 0; i < 2; i++) {
                elements.add(shepherdElement);
            }
            HekuosMod.LOGGER.info("已修复{}村庄牧羊人小屋生成", biome);
        } catch (Exception e) {
            HekuosMod.LOGGER.error("修复牧羊人小屋失败: {}", e.getMessage());
        }
    }
}
