package com.gtocraftfix.support;

/**
 * 不依賴 Minecraft／AE2 的 hotfix 基礎規則，集中處理 long 邊界與單次工作的共用時間預算。
 * <p>⚠ <b>這個類不能放在 {@code com.gtocraftfix.mixin}</b>：那是 {@code gto_craft_fix.mixins.json}
 * 宣告的 mixin package，Mixin 禁止直接參照該 package 下的任何類別（3.13.2 實測：
 * {@code IllegalClassLoadError: … is in a defined mixin package … and cannot be referenced directly}，
 * 在 {@code CraftingService.<clinit>} 就炸，世界完全載不進去）。放在獨立 package 並開放為 public。
 */
public final class CraftingHotfixSupport {

    public enum PendingKnowledge {
        UNKNOWN,
        NONE,
        PRESENT
    }

    /**
     * {@code logic.insert} 中途失敗後的兩本帳對帳結果。
     * <p>{@code physicalRefund} 只看 CPU 實際留住多少，不能以 waiting 帳認列量代替，否則
     * retained 大於 consumedWaiting 的異常路徑會把 CPU 已留住的實物再退回網路而複製。
     */
    public record LogicInsertCompensation(long retained, long consumedWaiting,
            long restoreWaiting, long physicalRefund) {
    }

    /** NetworkStorage mutation 前後的可用量差額；UNKNOWN 時 amount 固定為 0，不得拿來猜測搬料量。 */
    public record TransferDelta(boolean known, long amount) {
        private static final TransferDelta UNKNOWN = new TransferDelta(false, 0);

        private static TransferDelta unknown() {
            return UNKNOWN;
        }

        private static TransferDelta known(long amount) {
            return new TransferDelta(true, amount);
        }
    }

    private CraftingHotfixSupport() {
    }

    public static long saturatingAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            return right >= 0 ? Long.MAX_VALUE : Long.MIN_VALUE;
        }
    }

    public static long saturatingSubtract(long left, long right) {
        try {
            return Math.subtractExact(left, right);
        } catch (ArithmeticException overflow) {
            return right >= 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
    }

    public static long saturatingMultiply(long left, long right) {
        try {
            return Math.multiplyExact(left, right);
        } catch (ArithmeticException overflow) {
            return ((left ^ right) < 0) ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
    }

    /** 可執行性證明用的精確非負加法；溢位／負輸入回 null，不能拿飽和值冒充精確需求。 */
    public static Long checkedNonNegativeAdd(long left, long right) {
        if (left < 0 || right < 0) {
            return null;
        }
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            return null;
        }
    }

    /** 可執行性證明用的精確非負乘法；語意同 {@link #checkedNonNegativeAdd(long, long)}。 */
    public static Long checkedNonNegativeMultiply(long left, long right) {
        if (left < 0 || right < 0) {
            return null;
        }
        try {
            return Math.multiplyExact(left, right);
        } catch (ArithmeticException overflow) {
            return null;
        }
    }

    /** 正整數的向上取整除法；不使用 {@code value + divisor - 1}，避免加法溢位。 */
    public static long ceilDivPositive(long value, long divisor) {
        if (value <= 0) {
            return 0;
        }
        if (divisor <= 0) {
            throw new IllegalArgumentException("divisor 必須大於 0");
        }
        return 1 + (value - 1) / divisor;
    }

    /** 只回傳正缺口；輸入預期非負，負的可用量一律當 0。 */
    public static long positiveDeficit(long required, long available) {
        if (required <= 0) {
            return 0;
        }
        long safeAvailable = Math.max(0, available);
        return required <= safeAvailable ? 0 : required - safeAvailable;
    }

    /** 最終可交付量刻意不收 used(final)：GTO 不會把開局吸入的成品送進 link。 */
    public static long finalDeliverable(long emitted, long patternProduced) {
        return saturatingAdd(Math.max(0, emitted), Math.max(0, patternProduced));
    }

    /** final 永不餵；task／pending 任一證據未知也 fail-closed。 */
    public static boolean shouldFeedWaiting(boolean finalKey, boolean taskSnapshotKnown,
            boolean producedByTask, PendingKnowledge pending) {
        return !finalKey
                && taskSnapshotKnown
                && !producedByTask
                && pending == PendingKnowledge.NONE;
    }

    /** null／空／全零或負輪數都不是可執行 task；讀取 Iterable 拋例外同樣 fail-closed。 */
    public static boolean hasPositiveTask(Iterable<Long> patternRuns) {
        if (patternRuns == null) {
            return false;
        }
        try {
            for (Long runs : patternRuns) {
                if (runs != null && runs > 0) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            return false;
        }
        return false;
    }

    /** 四本帳 rollback 無法證明完整時，未知來源按機器側傳入並拒收；玩家維持原提交行為。 */
    public static boolean shouldRejectUnknownPlanIntegrity(boolean machineSource, boolean integrityUnknown) {
        return machineSource && integrityUnknown;
    }

    /**
     * 依入庫前後差額計算例外補償；所有輸出都 clamp 在 {@code [0, extracted]}。
     * waiting 只補回「已扣但沒有對應 CPU 留存」的量，實體回存則永遠是
     * {@code extracted - retained}，兩個維度不可混用。
     */
    public static LogicInsertCompensation logicInsertCompensation(long extracted,
            long cpuBefore, long cpuAfter, long waitingBefore, long waitingAfter) {
        long safeExtracted = Math.max(0, extracted);
        long retained = Math.min(safeExtracted, Math.max(0,
                saturatingSubtract(cpuAfter, cpuBefore)));
        long consumedWaiting = Math.min(safeExtracted, Math.max(0,
                saturatingSubtract(waitingBefore, waitingAfter)));
        long acceptedByBothLedgers = Math.min(retained, consumedWaiting);
        return new LogicInsertCompensation(
                retained,
                consumedWaiting,
                positiveDeficit(consumedWaiting, acceptedByBothLedgers),
                positiveDeficit(safeExtracted, retained));
    }

    /**
     * MODULATE extract 的可證實差額。before/after 是同一 key 以 SIMULATE extract(Long.MAX_VALUE)
     * 量到的可用量；方向相反、差額超過本次請求或任一證據未知，都不能猜成 0。
     */
    public static TransferDelta extractedDelta(long requested, Long before, Long after) {
        if (requested < 0 || before == null || after == null || before < 0 || after < 0
                || after > before) {
            return TransferDelta.unknown();
        }
        long delta = before - after;
        return delta <= requested ? TransferDelta.known(delta) : TransferDelta.unknown();
    }

    /** MODULATE insert 的可證實差額；其餘規則同 {@link #extractedDelta(long, Long, Long)}。 */
    public static TransferDelta insertedDelta(long requested, Long before, Long after) {
        if (requested < 0 || before == null || after == null || before < 0 || after < 0
                || after < before) {
            return TransferDelta.unknown();
        }
        long delta = after - before;
        return delta <= requested ? TransferDelta.known(delta) : TransferDelta.unknown();
    }

    /** 毫秒轉奈秒；0／負數代表停用，極大值飽和為「無上限」。 */
    public static long budgetNanos(long milliseconds) {
        if (milliseconds <= 0) {
            return Long.MAX_VALUE;
        }
        return saturatingMultiply(milliseconds, 1_000_000L);
    }

    /** 秒轉奈秒；0／負數代表無冷卻。 */
    public static long cooldownNanos(long seconds) {
        if (seconds <= 0) {
            return 0;
        }
        return saturatingMultiply(seconds, 1_000_000_000L);
    }

    /**
     * 所有子步驟共用同一個 {@code startedAt}，不在每次迭代重設時間；nanoTime 回繞也可由差值正確處理。
     */
    public static boolean budgetExpired(long startedAt, long budgetNanos, long now) {
        return budgetNanos != Long.MAX_VALUE && now - startedAt >= budgetNanos;
    }
}
