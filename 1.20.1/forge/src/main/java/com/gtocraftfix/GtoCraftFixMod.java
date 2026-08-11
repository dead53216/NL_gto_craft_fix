package com.gtocraftfix;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.fml.common.Mod;

import org.apache.logging.log4j.LogManager;

/**
 * 獨立修復 mod（不改 GTOCore、不動 gtolib），修 GTO 環境下多步自動合成：
 * <ol>
 *   <li>算料同步化：GTO async 算料多步時 Future 不返回 → 改伺服器執行緒同步執行。</li>
 *   <li>機器源 present-once：讓機器源走與玩家相同的 IgnoreMissing 分支（缺料記 waitingFor、回流認領）。</li>
 *   <li>保母：定期掃孤兒 waitingFor，網路有貨直餵解凍。</li>
 * </ol>
 * 全部實作於 {@code mixin.CraftingServiceSyncMixin}；{@code mixin.CraftingCpuHelperMixin} 為金絲雀。
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
