package com.gtocraftfix;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.fml.common.Mod;

import org.apache.logging.log4j.LogManager;

/**
 * 獨立修復 mod：不改 GTOCore、不動 gtolib。修 GTO 環境下「合成樹超過一步就無法（正確）自動合成」：
 * <ol>
 *   <li><b>算料同步化</b>：GTO 單執行緒 async 算料多步時 Future 不返回 → 終端 ctrl+左鍵卡死。
 *       改伺服器執行緒同步執行。</li>
 *   <li><b>機器源 present-once 走 IgnoreMissing</b>：GTO 計畫的 usedItems 含「執行期間才回流的中間產物」，
 *       嚴格取料必失敗 → 接口/請求器/合成卡被 MISSING_INGREDIENT 無限拒單。包 present-once 讓機器源
 *       走與玩家相同的 IgnoreMissing 分支（缺料記 waitingFor、回流認領）。</li>
 *   <li><b>保母</b>：GTO 算料器會把「網路 0 個的中間料」寫進 usedItems 而不排合成任務（批量餘數）
 *       → job 永久凍結。每 5 秒掃孤兒 waitingFor：有貨直餵、沒貨代下巢狀合成單。</li>
 * </ol>
 * 全部實作於 {@code mixin.CraftingServiceSyncMixin}（皆掛在 AE2 類上）；
 * {@code mixin.CraftingCpuHelperMixin} 為金絲雀（present-once 失效警報）。
 */
@Mod(GtoCraftFixMod.MODID)
public final class GtoCraftFixMod {

    public static final String MODID = "gto_craft_fix";

    public GtoCraftFixMod() {
        // 停機清理：static 佇列（LP 回退佇列、算料泵佇列）強持 Level/IGrid，不清會讓整個舊
        // ServerLevel 在主選單期間無法 GC，且新世界首個 tick 會對死 grid 建構/泵發算料
        MinecraftForge.EVENT_BUS.addListener(GtoCraftFixMod::onServerStopped);
        LogManager.getLogger("gtocraftfix").info("[craftfix] 已載入：同步算料＋機器源 IgnoreMissing＋保母。");
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        com.gtocraftfix.lpcalc.LpFallbackQueue.clearOnServerStopped();
        com.gtocraftfix.calc.CalcTicker.clearOnServerStopped();
    }
}
