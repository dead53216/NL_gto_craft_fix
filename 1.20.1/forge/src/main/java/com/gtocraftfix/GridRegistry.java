package com.gtocraftfix;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingService;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * 記住目前活著的 ME 網路，讓 {@link CraftFixCommand} 有東西可查。
 *
 * <p>玩家不隸屬於任何 grid，所以指令沒辦法自己找到網路；由 {@code CraftingServiceSyncMixin}
 * 每 tick 把自己登記進來。用 {@link WeakHashMap} 存，網路消失後自動掉，不會拖住舊的
 * {@code ServerLevel}（這個 repo 有過 static 佇列強持 Level 導致無法 GC 的紀錄）。
 *
 * <p>只在伺服器緒讀寫（tick 與指令都是伺服器緒），仍加同步以防未來有人從別的緒呼叫。
 */
public final class GridRegistry {

    /** 一張網路的查詢入口。 */
    public record Entry(ICraftingService crafting, IGrid grid) {

        /** 網路現貨；讀不到回 -1（不要用 0 冒充「沒有」）。 */
        public long stock(AEKey key) {
            try {
                return grid.getStorageService().getInventory()
                        .extract(key, Long.MAX_VALUE, Actionable.SIMULATE, IActionSource.empty());
            } catch (Throwable ignored) {
                return -1;
            }
        }
    }

    private static final Map<ICraftingService, IGrid> LIVE = new WeakHashMap<>();

    private GridRegistry() {
    }

    public static void seen(ICraftingService crafting, IGrid grid) {
        if (crafting == null || grid == null) {
            return;
        }
        synchronized (LIVE) {
            LIVE.put(crafting, grid);
        }
    }

    public static List<Entry> snapshot() {
        synchronized (LIVE) {
            List<Entry> out = new ArrayList<>(LIVE.size());
            for (var e : LIVE.entrySet()) {
                out.add(new Entry(e.getKey(), e.getValue()));
            }
            return Collections.unmodifiableList(out);
        }
    }

    public static void clearOnServerStopped() {
        synchronized (LIVE) {
            LIVE.clear();
        }
    }
}
