package com.gtocraftfix.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CraftingHotfixSupportTest {

    @Test
    void saturatingArithmeticClampsBothDirections() {
        assertEquals(Long.MAX_VALUE, CraftingHotfixSupport.saturatingAdd(Long.MAX_VALUE, 1));
        assertEquals(Long.MIN_VALUE, CraftingHotfixSupport.saturatingAdd(Long.MIN_VALUE, -1));
        assertEquals(Long.MIN_VALUE, CraftingHotfixSupport.saturatingSubtract(Long.MIN_VALUE, 1));
        assertEquals(Long.MAX_VALUE, CraftingHotfixSupport.saturatingSubtract(Long.MAX_VALUE, -1));
        assertEquals(Long.MAX_VALUE, CraftingHotfixSupport.saturatingMultiply(Long.MAX_VALUE, 2));
        assertEquals(Long.MIN_VALUE, CraftingHotfixSupport.saturatingMultiply(Long.MAX_VALUE, -2));
        assertEquals(Long.MAX_VALUE, CraftingHotfixSupport.saturatingMultiply(Long.MIN_VALUE, -1));
        assertEquals(42, CraftingHotfixSupport.saturatingAdd(40, 2));
    }

    @Test
    void feasibilityArithmeticReturnsUnknownInsteadOfSaturatingOverflow() {
        assertEquals(12L, CraftingHotfixSupport.checkedNonNegativeAdd(5, 7));
        assertEquals(Long.MAX_VALUE, CraftingHotfixSupport.checkedNonNegativeAdd(Long.MAX_VALUE, 0));
        assertEquals(null, CraftingHotfixSupport.checkedNonNegativeAdd(1L << 62, 1L << 62));
        assertEquals(null, CraftingHotfixSupport.checkedNonNegativeAdd(-1, 1));

        assertEquals(12L, CraftingHotfixSupport.checkedNonNegativeMultiply(3, 4));
        assertEquals(null, CraftingHotfixSupport.checkedNonNegativeMultiply(Long.MAX_VALUE, 2));
        assertEquals(null, CraftingHotfixSupport.checkedNonNegativeMultiply(-1, 2));
    }

    @Test
    void ceilDivisionDoesNotOverflowNearLongMax() {
        assertEquals(0, CraftingHotfixSupport.ceilDivPositive(0, 7));
        assertEquals(4, CraftingHotfixSupport.ceilDivPositive(10, 3));
        assertEquals((Long.MAX_VALUE / 2) + 1,
                CraftingHotfixSupport.ceilDivPositive(Long.MAX_VALUE, 2));
        assertThrows(IllegalArgumentException.class,
                () -> CraftingHotfixSupport.ceilDivPositive(1, 0));
    }

    @Test
    void deficitIsPositiveAndTreatsNegativeAvailabilityAsZero() {
        assertEquals(0, CraftingHotfixSupport.positiveDeficit(0, 100));
        assertEquals(0, CraftingHotfixSupport.positiveDeficit(10, 10));
        assertEquals(4, CraftingHotfixSupport.positiveDeficit(10, 6));
        assertEquals(10, CraftingHotfixSupport.positiveDeficit(10, -1));
    }

    @Test
    void finalDeliverableNeverCountsInitiallyUsedFinalItems() {
        long initiallyUsedFinalItems = 50_000;
        long deliverable = CraftingHotfixSupport.finalDeliverable(25_000, 25_000);

        assertEquals(50_000, deliverable);
        assertEquals(50_000, CraftingHotfixSupport.positiveDeficit(100_000, deliverable));
        assertEquals(0, CraftingHotfixSupport.finalDeliverable(0, 0));
        assertEquals(Long.MAX_VALUE,
                CraftingHotfixSupport.finalDeliverable(Long.MAX_VALUE, Long.MAX_VALUE));

        // The removed 3.13.1 formula would have inflated this to the full request and hidden the deficit.
        assertEquals(100_000,
                CraftingHotfixSupport.saturatingAdd(deliverable, initiallyUsedFinalItems));
    }

    @Test
    void waitingFeedFailsClosedForFinalOrUnknownEvidence() {
        var none = CraftingHotfixSupport.PendingKnowledge.NONE;
        var unknown = CraftingHotfixSupport.PendingKnowledge.UNKNOWN;
        var present = CraftingHotfixSupport.PendingKnowledge.PRESENT;

        assertFalse(CraftingHotfixSupport.shouldFeedWaiting(true, true, false, none));
        assertFalse(CraftingHotfixSupport.shouldFeedWaiting(false, false, false, none));
        assertFalse(CraftingHotfixSupport.shouldFeedWaiting(false, true, false, unknown));
        assertFalse(CraftingHotfixSupport.shouldFeedWaiting(false, true, false, present));
        assertFalse(CraftingHotfixSupport.shouldFeedWaiting(false, true, true, none));
        assertTrue(CraftingHotfixSupport.shouldFeedWaiting(false, true, false, none));
    }

    @Test
    void executableTaskAndRollbackPoliciesFailClosedForMachines() {
        assertFalse(CraftingHotfixSupport.hasPositiveTask(null));
        assertFalse(CraftingHotfixSupport.hasPositiveTask(java.util.List.of()));
        assertFalse(CraftingHotfixSupport.hasPositiveTask(java.util.Arrays.asList(null, 0L, -1L)));
        assertTrue(CraftingHotfixSupport.hasPositiveTask(java.util.Arrays.asList(0L, 2L)));

        assertTrue(CraftingHotfixSupport.shouldRejectUnknownPlanIntegrity(true, true));
        assertFalse(CraftingHotfixSupport.shouldRejectUnknownPlanIntegrity(false, true));
        assertFalse(CraftingHotfixSupport.shouldRejectUnknownPlanIntegrity(true, false));
    }

    @Test
    void logicInsertCompensationReconcilesBothLedgersWithoutDuplicatingPhysicalItems() {
        assertEquals(new CraftingHotfixSupport.LogicInsertCompensation(10, 10, 0, 0),
                CraftingHotfixSupport.logicInsertCompensation(10, 0, 10, 10, 0));

        // GTO 標準風險路徑：waiting 已扣，CPU insert 前拋出。
        assertEquals(new CraftingHotfixSupport.LogicInsertCompensation(0, 10, 10, 10),
                CraftingHotfixSupport.logicInsertCompensation(10, 0, 0, 10, 0));

        // waiting 比 CPU 多扣：差額要補回 waiting，實體只回 CPU 未留住的部分。
        assertEquals(new CraftingHotfixSupport.LogicInsertCompensation(4, 7, 3, 6),
                CraftingHotfixSupport.logicInsertCompensation(10, 100, 104, 20, 13));

        // 非標準 R>D：CPU 已留住 7 就只能回 3；若按兩本帳共同認列的 3 回 7，會複製 4。
        assertEquals(new CraftingHotfixSupport.LogicInsertCompensation(7, 3, 0, 3),
                CraftingHotfixSupport.logicInsertCompensation(10, 100, 107, 20, 17));
    }

    @Test
    void networkTransferDeltaRequiresConsistentBeforeAndAfterEvidence() {
        var knownExtract = new CraftingHotfixSupport.TransferDelta(true, 4);
        var knownInsert = new CraftingHotfixSupport.TransferDelta(true, 4);
        var unknown = new CraftingHotfixSupport.TransferDelta(false, 0);

        assertEquals(knownExtract, CraftingHotfixSupport.extractedDelta(10, 100L, 96L));
        assertEquals(new CraftingHotfixSupport.TransferDelta(true, Long.MAX_VALUE),
                CraftingHotfixSupport.extractedDelta(Long.MAX_VALUE, Long.MAX_VALUE, 0L));
        assertEquals(unknown, CraftingHotfixSupport.extractedDelta(10, 100L, null));
        assertEquals(unknown, CraftingHotfixSupport.extractedDelta(10, 100L, 101L));
        assertEquals(unknown, CraftingHotfixSupport.extractedDelta(10, 100L, 89L));

        assertEquals(knownInsert, CraftingHotfixSupport.insertedDelta(10, 100L, 104L));
        assertEquals(new CraftingHotfixSupport.TransferDelta(true, Long.MAX_VALUE),
                CraftingHotfixSupport.insertedDelta(Long.MAX_VALUE, 0L, Long.MAX_VALUE));
        assertEquals(unknown, CraftingHotfixSupport.insertedDelta(10, null, 100L));
        assertEquals(unknown, CraftingHotfixSupport.insertedDelta(10, 100L, 99L));
        assertEquals(unknown, CraftingHotfixSupport.insertedDelta(10, 100L, 111L));
    }

    @Test
    void sharedBudgetUsesSaturatedConversionsAndHandlesNanoTimeWrap() {
        assertEquals(Long.MAX_VALUE, CraftingHotfixSupport.budgetNanos(0));
        assertEquals(Long.MAX_VALUE, CraftingHotfixSupport.budgetNanos(Long.MAX_VALUE));
        assertEquals(5_000_000, CraftingHotfixSupport.budgetNanos(5));
        assertEquals(0, CraftingHotfixSupport.cooldownNanos(0));
        assertEquals(Long.MAX_VALUE, CraftingHotfixSupport.cooldownNanos(Long.MAX_VALUE));

        assertFalse(CraftingHotfixSupport.budgetExpired(100, Long.MAX_VALUE, Long.MAX_VALUE));
        assertFalse(CraftingHotfixSupport.budgetExpired(100, 10, 109));
        assertTrue(CraftingHotfixSupport.budgetExpired(100, 10, 110));
        assertTrue(CraftingHotfixSupport.budgetExpired(Long.MAX_VALUE - 5, 10, Long.MIN_VALUE + 5));
    }
}
