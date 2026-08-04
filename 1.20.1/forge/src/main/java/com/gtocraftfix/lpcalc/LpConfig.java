package com.gtocraftfix.lpcalc;

/** 系統屬性集中讀取（類初始化時讀一次快取，DESIGN-lpcalc.md §4.1／§12）。 */
public final class LpConfig {

    private static final boolean ENABLED =
            !"false".equalsIgnoreCase(System.getProperty("gtodiag.lpcalc.enabled", "true"));
    private static final boolean SHADOW_VERIFY =
            !"false".equalsIgnoreCase(System.getProperty("gtodiag.lpcalc.shadowVerifyOnMissing", "true"));
    private static final long SNAPSHOT_BUDGET_NANOS =
            parseLong(System.getProperty("gtodiag.lpcalc.snapshotBudgetNanos"), 1_000_000L);
    private static final long SOLVE_BUDGET_NANOS =
            parseLong(System.getProperty("gtodiag.lpcalc.solveBudgetNanos"), 100_000_000L);
    private static final int MAX_KEYS =
            (int) parseLong(System.getProperty("gtodiag.lpcalc.maxKeys"), 4096);
    private static final int MAX_PATTERNS =
            (int) parseLong(System.getProperty("gtodiag.lpcalc.maxPatterns"), 16384);

    private LpConfig() {}

    private static long parseLong(String s, long def) {
        try {
            return s == null ? def : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** [鐵則12] 一鍵停用，預設 true */
    public static boolean enabled() {
        return ENABLED;
    }

    /** [鐵則8] missing 非空時影子跑樹狀版，預設 true */
    public static boolean shadowVerifyOnMissing() {
        return SHADOW_VERIFY;
    }

    /** [鐵則14] 快照期 nanoTime 硬預算，預設 ~1ms */
    public static long snapshotBudgetNanos() {
        return SNAPSHOT_BUDGET_NANOS;
    }

    /** 求解期（背景緒）nanoTime 硬預算，預設 100ms（超限 SOLVE_BUDGET 回退樹狀版） */
    public static long solveBudgetNanos() {
        return SOLVE_BUDGET_NANOS;
    }

    public static int maxKeys() {
        return MAX_KEYS;
    }

    public static int maxPatterns() {
        return MAX_PATTERNS;
    }

    /** [鐵則7] 多趟傳播上限，固定 4 */
    public static int maxPasses() {
        return 4;
    }

    /** audit／SCC 證書波次上限，固定 64 */
    public static int maxWaves() {
        return 64;
    }

    /** SCC 啟動料倍增上限，固定 3 */
    public static int maxBootstrapDoublings() {
        return 3;
    }
}
