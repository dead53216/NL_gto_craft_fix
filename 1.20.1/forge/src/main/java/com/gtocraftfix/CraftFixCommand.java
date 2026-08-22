package com.gtocraftfix;

import com.mojang.brigadier.CommandDispatcher;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;

import appeng.api.networking.crafting.ICraftingService;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;

/**
 * {@code /craftfix why} —— 拿著要查的物品打這行，當場回答「這東西為什麼不合成」。
 *
 * <p>起因：2026-08-22 稀土金屬粉的請求器完全不呼叫 {@code beginCraftingCalculation}，
 * 而 mod 只看得到「沒有人來請求」，查不出上游為什麼不來。請求器決定要不要下單，看的是
 * <b>可合成性、網路現貨、已請求量</b>這三件事——全部都在 {@link ICraftingService} 上，
 * 是這個 mod 本來就拿得到的資料，只是以前沒有地方可以問。
 *
 * <p>純唯讀：只查詢不改動任何東西。
 */
public final class CraftFixCommand {

    private CraftFixCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("craftfix")
                .then(Commands.literal("why")
                        .executes(ctx -> why(ctx.getSource()))));
    }

    private static int why(CommandSourceStack src) {
        ServerPlayer player = src.getPlayer();
        if (player == null) {
            src.sendFailure(Component.literal("[craftfix] 這個指令要由玩家執行（要讀你手上的物品）"));
            return 0;
        }
        var stack = player.getItemInHand(InteractionHand.MAIN_HAND);
        if (stack.isEmpty()) {
            src.sendFailure(Component.literal("[craftfix] 主手要拿著想查的物品"));
            return 0;
        }
        AEKey key = AEItemKey.of(stack);
        if (key == null) {
            src.sendFailure(Component.literal("[craftfix] 這個物品轉不成 AE key"));
            return 0;
        }

        var grids = GridRegistry.snapshot();
        if (grids.isEmpty()) {
            src.sendFailure(Component.literal("[craftfix] 目前沒有看到任何 ME 網路"
                    + "（要先有一張網路 tick 過一次；剛進世界請等幾秒）"));
            return 0;
        }

        src.sendSuccess(() -> Component.literal("[craftfix] " + key.getDisplayName().getString()
                + "（" + key + "）— 共 " + grids.size() + " 張網路"), false);
        int n = 0;
        for (var entry : grids) {
            n++;
            ICraftingService cs = entry.crafting();
            String line;
            try {
                int patterns = cs.getCraftingFor(key).size();
                boolean craftable = cs.isCraftable(key);
                boolean emitable = cs.canEmitFor(key);
                long requested = cs.getRequestedAmount(key);
                boolean requesting = cs.isRequesting(key);
                long stock = entry.stock(key);
                int cpus = cs.getCpus().size();
                line = "  網路#" + n
                        + "  樣板數=" + patterns
                        + "  可合成=" + craftable
                        + "  可發射=" + emitable
                        + "  網路現貨=" + stock
                        + "  已請求量=" + requested
                        + "  isRequesting=" + requesting
                        + "  CPU=" + cpus;
                if (patterns == 0 && !emitable) {
                    line += "  ← **沒有任何樣板能做它**，請求器不會下單";
                } else if (requested > 0) {
                    line += "  ← 已經有在途，請求器要等它結案才會再下單";
                }
            } catch (Throwable t) {
                line = "  網路#" + n + "  查詢失敗：" + t;
            }
            final String out = line;
            src.sendSuccess(() -> Component.literal(out), false);
        }
        return 1;
    }
}
