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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * {@code /craftfix why} —— 拿著要查的物品打這行，當場回答「這東西為什麼不合成」。
 *
 * <p>請求器要不要下單只看三件事：<b>可合成性、網路現貨、已請求量</b>，全部在
 * {@link ICraftingService} 上，本 mod 掛在 {@code CraftingService} 本來就拿得到。
 *
 * <p><b>[3.15.1] 只印相關的網路。</b>一個世界裡每一段沒接起來的 AE 線材都是一張獨立 grid，
 * 實測有 1088 張；3.15.0 全部照印，真正那張被埋在上千行裡完全看不到。現在只印
 * 「有 CPU／有樣板／有現貨／有在途」的網路，其餘只回報略過幾張。
 *
 * <p><b>[3.15.1] 找 NBT 不同的同名品。</b>「樣板數=0」最常見的原因不是樣板不見了，而是
 * <b>手上這顆的 NBT 跟樣板產出的不是同一個 AEKey</b>（GTO 很多東西帶 tag）。所以查不到精確
 * 樣板時，會再掃網路的可合成清單，把「同一個物品、不同 NBT」的 key 列出來。
 *
 * <p>純唯讀：只查詢不改動任何東西。
 */
public final class CraftFixCommand {

    /** 一次最多印幾張網路，避免又洗版。 */
    private static final int MAX_GRIDS = 8;
    /** 同名不同 NBT 的候選最多列幾個。 */
    private static final int MAX_VARIANTS = 5;

    private CraftFixCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("craftfix")
                .then(Commands.literal("why")
                        .executes(ctx -> why(ctx.getSource()))));
    }

    private record Report(int index, int patterns, boolean craftable, boolean emitable,
            long stock, long requested, boolean requesting, int cpus, List<String> variants) {

        boolean relevant() {
            return cpus > 0 || patterns > 0 || stock > 0 || requested > 0 || !variants.isEmpty();
        }

        int rank() {
            return patterns * 1000 + cpus;
        }
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

        List<Report> reports = new ArrayList<>();
        int i = 0;
        for (var entry : grids) {
            i++;
            try {
                reports.add(inspect(i, entry, key));
            } catch (Throwable ignored) {
                // 單一網路查詢失敗不影響其他網路
            }
        }
        List<Report> shown = new ArrayList<>(reports.stream().filter(Report::relevant).toList());
        shown.sort(Comparator.comparingInt(Report::rank).reversed());
        int skipped = reports.size() - shown.size();
        boolean truncated = shown.size() > MAX_GRIDS;
        if (truncated) {
            shown = shown.subList(0, MAX_GRIDS);
        }

        src.sendSuccess(() -> Component.literal("[craftfix] " + key.getDisplayName().getString()
                + "  " + key), false);
        final int fSkipped = skipped;
        final int fTotal = reports.size();
        final boolean fTruncated = truncated;
        src.sendSuccess(() -> Component.literal("  共 " + fTotal + " 張網路，"
                + (fSkipped > 0 ? "略過 " + fSkipped + " 張無關的（無 CPU／無樣板／無現貨）" : "全部相關")
                + (fTruncated ? "，只列前 " + MAX_GRIDS + " 張" : "")), false);

        if (shown.isEmpty()) {
            src.sendSuccess(() -> Component.literal("  **每一張網路都沒有 CPU、沒有樣板、也沒有現貨**"
                    + " → 你手上這顆不是從這些網路做出來的，或網路沒接上"), false);
            return 1;
        }
        for (Report r : shown) {
            src.sendSuccess(() -> Component.literal(format(r)), false);
            for (String v : r.variants()) {
                src.sendSuccess(() -> Component.literal("      同名不同 NBT 的可合成品：" + v), false);
            }
        }
        return 1;
    }

    private static Report inspect(int index, GridRegistry.Entry entry, AEKey key) {
        ICraftingService cs = entry.crafting();
        int patterns = cs.getCraftingFor(key).size();
        boolean craftable = cs.isCraftable(key);
        boolean emitable = cs.canEmitFor(key);
        long requested = cs.getRequestedAmount(key);
        boolean requesting = cs.isRequesting(key);
        long stock = entry.stock(key);
        int cpus = cs.getCpus().size();

        List<String> variants = new ArrayList<>();
        // 精確 key 查不到樣板時，才去找「同一個物品、不同 NBT」的可合成品——這是「樣板數=0」
        // 最常見的真因（手上那顆的 tag 跟樣板產出的不一樣）。
        if (patterns == 0 && cpus > 0) {
            try {
                for (AEKey c : cs.getCraftables(k -> true)) {
                    if (c != null && !c.equals(key)
                            && java.util.Objects.equals(c.getPrimaryKey(), key.getPrimaryKey())) {
                        variants.add(c.toString());
                        if (variants.size() >= MAX_VARIANTS) {
                            break;
                        }
                    }
                }
            } catch (Throwable ignored) {
                // 掃不動就算了，主要欄位已經夠判斷
            }
        }
        return new Report(index, patterns, craftable, emitable, stock, requested, requesting, cpus,
                variants);
    }

    private static String format(Report r) {
        String line = "  網路#" + r.index()
                + "  樣板=" + r.patterns()
                + "  可合成=" + r.craftable()
                + "  可發射=" + r.emitable()
                + "  現貨=" + r.stock()
                + "  已請求=" + r.requested()
                + "  CPU=" + r.cpus();
        if (r.patterns() > 0 && r.requested() > 0) {
            line += "  ← 有樣板但已經有在途，請求器要等它結案才會再下單";
        } else if (r.patterns() > 0) {
            line += "  ← 有樣板，這張網路做得出來";
        } else if (!r.variants().isEmpty()) {
            line += "  ← **精確 NBT 對不上**：樣板產出的是下面那些 key，不是你手上這顆";
        } else if (r.emitable()) {
            line += "  ← 沒樣板但可發射（等級發射器）";
        } else if (r.cpus() > 0) {
            line += "  ← **這張有 CPU 的網路上沒有任何樣板能做它**";
        }
        return line;
    }
}
