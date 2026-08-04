package com.gtocraftfix.mixin;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingPlan;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.GenericStack;
import appeng.crafting.execution.CraftingCpuHelper;
import appeng.crafting.inv.ListCraftingInventory;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 金絲雀（不改行為）：機器源正常應由 {@code CraftingServiceSyncMixin} 的 present-once 包裝
 * 導向 IgnoreMissing 分支，不會走到這裡的嚴格取料。若機器源出現在此 → 包裝失效
 * （如 FakePlayer 建立失敗），多步自動合成會退回 MISSING_INGREDIENT 無限拒單的原始 bug——
 * 印警告供診斷（上限 5 次）。
 */
@Mixin(value = CraftingCpuHelper.class, remap = false)
public abstract class CraftingCpuHelperMixin {

    private static final Logger LOG = LogManager.getLogger("gtocraftfix");
    private static final AtomicInteger HITS = new AtomicInteger();

    @Inject(method = "tryExtractInitialItems", at = @At("HEAD"), remap = false)
    private static void gtocraftfix$canary(ICraftingPlan plan, IGrid grid,
                                           ListCraftingInventory cpuInventory, IActionSource src,
                                           CallbackInfoReturnable<GenericStack> cir) {
        if (src.player().isPresent()) {
            return;
        }
        int c = HITS.incrementAndGet();
        if (c <= 5) {
            LOG.warn("[craftfix] 警告：機器源走到嚴格取料（present-once 未生效？）out={}，多步自動合成可能被拒單。",
                    plan.finalOutput());
        }
    }
}
