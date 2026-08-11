package com.gtocraftfix.mixin;

import appeng.api.networking.IGrid;
import appeng.crafting.CraftingLink;
import appeng.crafting.CraftingLinkNexus;
import appeng.me.service.CraftingService;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * [v1.3.0] link 判死寬限：原版 isDead 對 hasCpu/grid 錯配一次 +60、兩次掃描即死，GTO 大網開機
 * 要多個 tick 才拼完 grid，進行中 job 開機必被誤殺。改缺席/錯配一律 +1、門檻 60 → 1200 次掃描。
 * [重檢18] GTOCore 讓 isDead 每 2 game tick 才跑一次：1200 次 ≈ 2 分鐘。
 */
@Mixin(value = CraftingLinkNexus.class, remap = false)
public abstract class CraftingLinkNexusMixin {

    @Shadow
    private boolean canceled;
    @Shadow
    private boolean done;
    @Shadow
    private int tickOfDeath;
    @Shadow
    private CraftingLink cpu;

    @Shadow
    public abstract CraftingLink getRequest();

    @Shadow
    abstract void cancel();

    private static volatile java.lang.reflect.Method gtocraftfix$mGetCpu;
    private static volatile java.lang.reflect.Method gtocraftfix$mGetReq;

    @Inject(method = "isDead", at = @At("HEAD"), cancellable = true, remap = false)
    private void gtocraftfix$graceIsDead(IGrid g, CraftingService craftingService,
                                         CallbackInfoReturnable<Boolean> cir) {
        if (this.canceled || this.done) {
            cir.setReturnValue(true);
            return;
        }
        var req = this.getRequest();
        if (req == null || this.cpu == null) {
            this.tickOfDeath++;
        } else {
            // getCpu()/getRequester() 為 CraftingLink 包私有、編譯期不可直呼 → 走反射；
            // 反射失敗視為雙方健在（link 仍會因 done/canceled 回收）
            boolean hasCpu = true;
            boolean hasMachine = true;
            try {
                if (gtocraftfix$mGetCpu == null) {
                    var m1 = CraftingLink.class.getDeclaredMethod("getCpu");
                    m1.setAccessible(true);
                    var m2 = CraftingLink.class.getDeclaredMethod("getRequester");
                    m2.setAccessible(true);
                    gtocraftfix$mGetReq = m2;
                    gtocraftfix$mGetCpu = m1;
                }
                var cpuObj = (appeng.api.networking.crafting.ICraftingCPU) gtocraftfix$mGetCpu.invoke(this.cpu);
                hasCpu = craftingService.hasCpu(cpuObj);
                // 原版直接鏈式取 requester.getActionableNode() 不防 null；這裡軟化
                var requester = (appeng.api.networking.crafting.ICraftingRequester) gtocraftfix$mGetReq.invoke(req);
                var node = requester == null ? null : requester.getActionableNode();
                hasMachine = node != null && node.getGrid() == g;
            } catch (Throwable ignored) {
            }
            if (hasCpu && hasMachine) {
                this.tickOfDeath = 0;
            } else {
                this.tickOfDeath++; // 原版 += 60（兩次掃描即死）→ +1：併網暫態不再秒殺
            }
        }
        if (this.tickOfDeath > 1200) { // 原版 60；掃描半頻（GTOCore 偶數 tick 掐斷）→ 1200 次 ≈ 2 分鐘
            this.cancel();
            cir.setReturnValue(true);
            return;
        }
        cir.setReturnValue(false);
    }
}
