package com.gtocraftfix;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.fml.common.Mod;

import org.apache.logging.log4j.LogManager;

/**
 * 獨立 slim 修復 mod：不改 GTOCore、不動 gtolib。修 GTO 環境下的多步自動合成錯帳與凍結：
 * <ol>
 *   <li><b>算料同步化</b>：GTO 單執行緒 async 算料多步時 Future 不返回 → 終端 ctrl+左鍵卡死。
 *       改伺服器執行緒同步執行。</li>
 *   <li><b>機器源 present-once 走 IgnoreMissing</b>：GTO 計畫的 usedItems 含「執行期間才回流的中間產物」，
 *       嚴格取料必失敗 → 接口/請求器/合成卡被 MISSING_INGREDIENT 無限拒單。包 present-once 讓機器源
 *       走與玩家相同的 IgnoreMissing 分支（缺料記 waitingFor、回流認領）。</li>
 *   <li><b>計畫修補</b>：補齊 missing／used 幻影、最終產量與循環啟動缺口；機器來源的無樣板或
 *       無任務退化計畫不送進 CPU。</li>
 *   <li><b>執行期救援</b>：只把已證明沒有任務產出、也沒有樣板在途的網路現貨中間料補進
 *       waitingFor，並繞開並行樣板只夠一輪時的上游死角。最終成品永不餵入，也不代下巢狀單。</li>
 * </ol>
 * 全部實作於 {@code mixin.CraftingServiceSyncMixin}（皆掛在 AE2 類上）；
 * {@code mixin.CraftingCpuHelperMixin} 為金絲雀（present-once 失效警報）。
 */
@Mod(GtoCraftFixMod.MODID)
public final class GtoCraftFixMod {

    public static final String MODID = "gto_craft_fix";

    public GtoCraftFixMod() {
        // [3.13.7] 先把 gtocraftfix 的 log 導到 logs/craftfix.log，之後所有訊息（含本方法下面那行）
        // 都只進獨立檔。本 mod 的帳本／探針量大，混在 latest.log 裡會把它撐到數十 MB。
        CraftLog.install();
        // 停機清理：static 佇列（LP 回退佇列、算料泵佇列）強持 Level/IGrid，不清會讓整個舊
        // ServerLevel 在主選單期間無法 GC，且新世界首個 tick 會對死 grid 建構/泵發算料
        MinecraftForge.EVENT_BUS.addListener(GtoCraftFixMod::onServerStopped);
        LogManager.getLogger("gtocraftfix").info(
                "[craftfix] 已載入 slim：同步算料＋機器源 IgnoreMissing＋計畫修補＋中間料救援。");
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        com.gtocraftfix.diag.CraftDiag.clearOnServerStopped();
        com.gtocraftfix.lpcalc.LpFallbackQueue.clearOnServerStopped();
        com.gtocraftfix.calc.CalcTicker.clearOnServerStopped();
    }
}
