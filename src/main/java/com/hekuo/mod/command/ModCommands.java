package com.hekuo.mod.command;

import com.hekuo.mod.HekuosMod;
import com.hekuo.mod.ai.AiService;
import com.hekuo.mod.ai.ConversationManager;
import com.hekuo.mod.config.ModConfig;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.UUID;

/**
 * 模组命令注册
 * - /askai <内容> [--all] - AI对话
 * - /searchconversion <对话id> [--thinking] [--all] - 查看对话
 * - /hekuomod reload - 重载配置
 * - /hekuomod status - 查看模组状态
 */
public class ModCommands {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher,
                                 CommandRegistryAccess registryAccess,
                                 CommandManager.RegistrationEnvironment environment) {
        // /askai 命令
        dispatcher.register(CommandManager.literal("askai")
            .requires(source -> ModConfig.get().aiChatEnabled)
            .then(CommandManager.argument("content", StringArgumentType.greedyString())
                .executes(context -> executeAskAi(context, false))
                .then(CommandManager.literal("--all")
                    .executes(context -> executeAskAi(context, true))
                )
            )
        );

        // /searchconversion 命令
        dispatcher.register(CommandManager.literal("searchconversion")
            .requires(source -> ModConfig.get().aiChatEnabled)
            .then(CommandManager.argument("conversationId", StringArgumentType.word())
                .executes(context -> executeSearchConversion(context, false, false))
                .then(CommandManager.literal("--thinking")
                    .executes(context -> executeSearchConversion(context, true, false))
                    .then(CommandManager.literal("--all")
                        .executes(context -> executeSearchConversion(context, true, true))
                    )
                )
                .then(CommandManager.literal("--all")
                    .executes(context -> executeSearchConversion(context, false, true))
                    .then(CommandManager.literal("--thinking")
                        .executes(context -> executeSearchConversion(context, true, true))
                    )
                )
            )
        );

        // /hekuomod 管理命令
        dispatcher.register(CommandManager.literal("hekuomod")
            .requires(ServerCommandSource::isExecutedByPlayer)
            .then(CommandManager.literal("reload")
                .requires(source -> source.hasPermissionLevel(2))
                .executes(ModCommands::executeReload)
            )
            .then(CommandManager.literal("status")
                .executes(ModCommands::executeStatus)
            )
        );
    }

    /**
     * 执行 /askai 命令
     * 生成会话ID，调用AI API，将结果私信给用户
     * 带 --all 时发送到公屏
     */
    private static int executeAskAi(CommandContext<ServerCommandSource> context, boolean broadcast) {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayer();

        if (player == null) return 0;

        String content = StringArgumentType.getString(context, "content");
        // 移除可能尾随的 --all
        content = content.replace(" --all", "").trim();
        final String finalContent = content;

        // 生成会话ID
        String conversationId = ConversationManager.getInstance().generateConversationId();

        // 告知用户正在处理
        player.sendMessage(Text.literal("会话ID: " + conversationId)
            .formatted(Formatting.GRAY), false);
        player.sendMessage(Text.literal("正在思考中...")
            .formatted(Formatting.YELLOW), false);

        // 异步调用AI
        AiService aiService = new AiService();
        aiService.chatAsync(finalContent, conversationId).thenAccept(response -> {
            if (response.hasError()) {
                player.sendMessage(Text.literal("AI调用失败: " + response.error)
                    .formatted(Formatting.RED), false);
                return;
            }

            if (broadcast) {
                // 发送到公屏
                source.getServer().getPlayerManager().broadcast(
                    Text.literal("[AI] " + player.getName().getString() + " 问: " + finalContent)
                        .formatted(Formatting.GOLD),
                    false
                );
                source.getServer().getPlayerManager().broadcast(
                    Text.literal("[AI] " + response.content)
                        .formatted(Formatting.WHITE),
                    false
                );
            } else {
                // 私信给用户
                player.sendMessage(Text.literal("会话ID: " + conversationId)
                    .formatted(Formatting.AQUA), false);
                player.sendMessage(Text.literal("AI回复: " + response.content)
                    .formatted(Formatting.WHITE), false);
            }
        });

        return 1;
    }

    /**
     * 执行 /searchconversion 命令
     * 查看对话历史
     * --thinking 显示思考过程
     * --all 公开展示
     */
    private static int executeSearchConversion(CommandContext<ServerCommandSource> context,
                                                boolean showThinking, boolean broadcast) {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayer();

        if (player == null) return 0;

        String convId = StringArgumentType.getString(context, "conversationId");
        ConversationManager.Conversation conv = ConversationManager.getInstance().getConversation(convId);

        if (conv == null) {
            player.sendMessage(Text.literal("未找到对话: " + convId)
                .formatted(Formatting.RED), false);
            return 0;
        }

        if (broadcast) {
            // 公开展示
            source.getServer().getPlayerManager().broadcast(
                Text.literal("=== 对话: " + convId + " ===")
                    .formatted(Formatting.GOLD),
                false
            );

            for (ConversationManager.Message msg : conv.getMessages()) {
                Formatting color = "user".equals(msg.role) ? Formatting.GREEN : Formatting.WHITE;
                source.getServer().getPlayerManager().broadcast(
                    Text.literal("[" + msg.role + "] " + msg.content)
                        .formatted(color),
                    false
                );
            }

            if (showThinking) {
                if (!ModConfig.get().aiThinkingEnabled) {
                    source.getServer().getPlayerManager().broadcast(
                        Text.literal("本服未开启模型思考功能喵喵喵~")
                            .formatted(Formatting.LIGHT_PURPLE),
                        false
                    );
                } else {
                    String thinking = conv.getThinkingSummary();
                    if (thinking != null) {
                        source.getServer().getPlayerManager().broadcast(
                            Text.literal("[思考过程] " + thinking)
                                .formatted(Formatting.GRAY),
                            false
                        );
                    }
                }
            }
        } else {
            // 私信给用户
            player.sendMessage(Text.literal("=== 对话: " + convId + " ===")
                .formatted(Formatting.GOLD), false);

            for (ConversationManager.Message msg : conv.getMessages()) {
                Formatting color = "user".equals(msg.role) ? Formatting.GREEN : Formatting.WHITE;
                player.sendMessage(Text.literal("[" + msg.role + "] " + msg.content)
                    .formatted(color), false);
            }

            if (showThinking) {
                if (!ModConfig.get().aiThinkingEnabled) {
                    player.sendMessage(Text.literal("本服未开启模型思考功能喵喵喵~")
                        .formatted(Formatting.LIGHT_PURPLE), false);
                } else {
                    String thinking = conv.getThinkingSummary();
                    if (thinking != null) {
                        player.sendMessage(Text.literal("[思考过程] " + thinking)
                            .formatted(Formatting.GRAY), false);
                    }
                }
            }
        }

        return 1;
    }

    /**
     * 重载配置
     */
    private static int executeReload(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        ModConfig.reload();
        source.sendFeedback(() -> Text.literal("Hekuo's Mod 配置已重载")
            .formatted(Formatting.GREEN), true);
        return 1;
    }

    /**
     * 查看模组状态
     */
    private static int executeStatus(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        ModConfig config = ModConfig.get();

        source.sendFeedback(() -> Text.literal("=== Hekuo's Mod 状态 ===")
            .formatted(Formatting.GOLD), false);
        source.sendFeedback(() -> Text.literal("铁砧火焰保护水桶: " + (config.fireProtectionWaterBucketEnabled ? "开启" : "关闭"))
            .formatted(config.fireProtectionWaterBucketEnabled ? Formatting.GREEN : Formatting.RED), false);
        source.sendFeedback(() -> Text.literal("修复牧羊人小屋: " + (config.fixShepherdHouseEnabled ? "开启" : "关闭"))
            .formatted(config.fixShepherdHouseEnabled ? Formatting.GREEN : Formatting.RED), false);
        source.sendFeedback(() -> Text.literal("下界冰变霜冰: " + (config.netherIceToFrostedIceEnabled ? "开启" : "关闭"))
            .formatted(config.netherIceToFrostedIceEnabled ? Formatting.GREEN : Formatting.RED), false);
        source.sendFeedback(() -> Text.literal("AI对话: " + (config.aiChatEnabled ? "开启" : "关闭"))
            .formatted(config.aiChatEnabled ? Formatting.GREEN : Formatting.RED), false);
        source.sendFeedback(() -> Text.literal("OneBot群服互联: " + (config.oneBotEnabled ? "开启" : "关闭"))
            .formatted(config.oneBotEnabled ? Formatting.GREEN : Formatting.RED), false);
        source.sendFeedback(() -> Text.literal("末地烛交互: " + (config.endRodInteractionEnabled ? "开启" : "关闭"))
            .formatted(config.endRodInteractionEnabled ? Formatting.GREEN : Formatting.RED), false);
        source.sendFeedback(() -> Text.literal("服务器状态网页: " + (config.webStatusEnabled ? "开启" : "关闭"))
            .formatted(config.webStatusEnabled ? Formatting.GREEN : Formatting.RED), false);

        return 1;
    }
}
