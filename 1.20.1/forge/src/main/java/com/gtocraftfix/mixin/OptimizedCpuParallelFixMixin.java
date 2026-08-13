package com.gtocraftfix.mixin;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyMap;
import appeng.api.stacks.KeyCounter;
import appeng.crafting.execution.CraftingCpuLogic;
import appeng.crafting.inv.ListCraftingInventory;

import com.gto.datasynclib.util.holder.ObjHolder;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 根治上游 parallel==1 死角（GTOCore OptimizedCraftingCpuLogic.executeCrafting:221-238）：
 * 「並行樣板＋剩餘輪數>1＋庫存恰夠 1 輪」時 getMaxParallel 回 1，>1 分支不進、外層 else 到不了
 * → craftingContainer 恆 null → 每 tick 無聲跳過永久卡死（中子反射板 x2 實錄；催化劑返還配方
 * 按淨需求備料必然踩中，已回報上游）。
 *
 * 修法＝等價補上缺失的 else：攔 ObjHolder 建構子記住本輪容器、攔 getMaxParallel 呼叫——算出 1 時
 * 就地用原樣板做單輪取料塞進容器，照樣回傳 1。上游後續照原生流程走：配額按 parallelValue=1 折算、
 * 推送用原樣板、成功後 progress -= 1，語意與官方修 else 一字不差。取料失敗（真缺料）容器留 null，
 * 行為退回原狀。目標類為 GTOCore 開源類（targets 字串綁定，不引其編譯依賴）。
 */
@Mixin(targets = "com.gtocore.api.ae2.crafting.OptimizedCraftingCpuLogic", remap = false)
public abstract class OptimizedCpuParallelFixMixin {

    @Shadow(remap = false)
    private static KeyCounter[] extractPatternInputs(IPatternDetails details,
                                                     ListCraftingInventory sourceInv,
                                                     KeyCounter expectedOutputs) {
        throw new AssertionError();
    }

    @Shadow(remap = false)
    private static long getMaxParallel(long maxParallel, IPatternDetails details,
                                       AEKeyMap<AEKey> sourceInv) {
        throw new AssertionError();
    }

    /** 本輪任務的 craftingContainer（executeCrafting 每個任務 new 一次，時序在 getMaxParallel 前）。 */
    private ObjHolder<KeyCounter[]> gtocraftfix$curContainer;
    /** job / expectedOutputs 反射欄位（型別套件私有，不可直接引用）；解析失敗永久停用修復（軟失敗）。 */
    private static volatile java.lang.reflect.Field gtocraftfix$fJobField;
    private static volatile java.lang.reflect.Field gtocraftfix$fExpectedField;
    private static volatile boolean gtocraftfix$reflectBroken;

    @Redirect(method = "executeCrafting",
            at = @At(value = "NEW",
                    target = "(Ljava/lang/Object;)Lcom/gto/datasynclib/util/holder/ObjHolder;"),
            remap = false)
    private ObjHolder<KeyCounter[]> gtocraftfix$captureContainer(Object init) {
        @SuppressWarnings("unchecked")
        var h = new ObjHolder<>((KeyCounter[]) init);
        gtocraftfix$curContainer = h;
        return h;
    }

    @Redirect(method = "executeCrafting",
            at = @At(value = "INVOKE",
                    target = "Lcom/gtocore/api/ae2/crafting/OptimizedCraftingCpuLogic;getMaxParallel(JLappeng/api/crafting/IPatternDetails;Lappeng/api/stacks/AEKeyMap;)J"),
            remap = false)
    private long gtocraftfix$parallelOneFix(long maxParallel, IPatternDetails details,
                                            AEKeyMap<AEKey> sourceInv) {
        long real = getMaxParallel(maxParallel, details, sourceInv);
        var holder = gtocraftfix$curContainer;
        if (real == 1 && holder != null && holder.value == null && !gtocraftfix$reflectBroken) {
            try {
                if (gtocraftfix$fJobField == null) {
                    var fj = this.getClass().getDeclaredField("job");
                    fj.setAccessible(true);
                    Object job0 = fj.get(this);
                    if (job0 == null) {
                        gtocraftfix$fJobField = fj;
                        return real;
                    }
                    var fe = job0.getClass().getDeclaredField("expectedOutputs");
                    fe.setAccessible(true);
                    gtocraftfix$fJobField = fj;
                    gtocraftfix$fExpectedField = fe;
                }
                Object job = gtocraftfix$fJobField.get(this);
                if (job == null || gtocraftfix$fExpectedField == null) {
                    return real;
                }
                var expected = (KeyCounter) gtocraftfix$fExpectedField.get(job);
                var inv = ((CraftingCpuLogic) (Object) this).getInventory();
                if (expected != null && inv != null) {
                    // 上游已在本輪先 clear 過 expectedOutputs；取料成功會把樣板產出填回去（原生語意）
                    holder.value = extractPatternInputs(details, inv, expected);
                }
            } catch (NoSuchFieldException e) {
                gtocraftfix$reflectBroken = true; // 欄位改名（上游更新）→ 永久停用，回退原行為
            } catch (Throwable ignored) {
            }
        }
        return real;
    }
}
