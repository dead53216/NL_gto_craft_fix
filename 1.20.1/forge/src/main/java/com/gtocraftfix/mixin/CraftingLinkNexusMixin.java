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
 * [v1.3.0] link 判死寬限。原版 {@code isDead}：兩側 link 任一側缺席（開機載入時序不齊的主路徑）
 * → {@code tickOfDeath++}，門檻 {@code > 60} 即 cancel；兩側都在但 hasCpu/grid 錯配（chunk 邊界、
 * 子網重組）→「{@code tickOfDeath += 60}」連續兩次掃描即死。GTO 數千節點大網開機要多個 tick
 * 才拼完 grid——結果每次重開世界，進行中合成 job 幾秒內被 AE2 自己判死取消（實錄：開機 30 秒
 * 連殺三張 job，交付帳全滿、任務整包蒸發）。
 * <p>
 * [重檢18] 掃描頻率注意：GTOCore 的 CraftingServiceMixin 在偶數 tick 掐斷 onServerEndTick、
 * isDead 實際每 2 個 game tick 才被呼叫一次——原版門檻實效 ≈6 秒；本 mixin 門檻 1200 次
 * 掃描 ≈ 2400 game tick ≈ 2 分鐘（20 TPS）。
 * <p>
 * 修法：整段改寫——缺席/錯配一律 +1、拔掉 += 60，門檻 60 → 1200 次掃描。真死的 link
 * 只是多掛 ~2 分鐘才回收；被誤殺的 job 從此撐得過開機與併網暫態。
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
            // getCpu()/getRequester() 為 CraftingLink 包私有——編譯期不可直呼（runtime 合併後
            // 其實同包可用），走反射；反射失敗視為雙方健在（不判死，link 仍會因 done/canceled 回收）
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
