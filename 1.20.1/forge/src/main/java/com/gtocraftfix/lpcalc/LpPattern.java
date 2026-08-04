package com.gtocraftfix.lpcalc;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.AEKey;

/** 編譯後樣板（不可變，§4.5）。快照期建構，背景緒唯讀。 */
final class LpPattern {

    /** [鐵則13] 原始 IPatternDetails 實例（hashCode 已在快照期暖機）——patternTimes 的 key 只准用它 */
    final IPatternDetails details;
    /** 每槽解析後 key（唯一重導向已代入，§5.3）；possibleInputs 為空的槽已剔除 */
    final AEKey[] inKey;
    /** 每輪 = getPossibleInputs()[0].amount() * getMultiplier()（與執行器 extractPatternInputs 口徑一致） */
    final long[] inAmt;
    final AEKey[] outKey;
    final long[] outAmt;
    final AEKey primaryOut;
    final long primaryOutAmt;
    /** [鐵則1] true ⇒ 啟動料權重 w≥2 ＋ audit 波次重放套 parallel==1 死角規則 */
    final boolean parallelOrUnknown;
    /** 任一槽 possibleInputs.length > 1（鐵則 10 粗化用） */
    final boolean multiInput;
    /** 任一槽重導向多候選；此樣板 runs>0 才觸發 REDIRECT_AMBIGUOUS 回退（§8） */
    final boolean ambiguousRedirect;

    LpPattern(IPatternDetails details, AEKey[] inKey, long[] inAmt, AEKey[] outKey, long[] outAmt,
              AEKey primaryOut, long primaryOutAmt, boolean parallelOrUnknown,
              boolean multiInput, boolean ambiguousRedirect) {
        this.details = details;
        this.inKey = inKey;
        this.inAmt = inAmt;
        this.outKey = outKey;
        this.outAmt = outAmt;
        this.primaryOut = primaryOut;
        this.primaryOutAmt = primaryOutAmt;
        this.parallelOrUnknown = parallelOrUnknown;
        this.multiInput = multiInput;
        this.ambiguousRedirect = ambiguousRedirect;
    }

    /** 此樣板每輪對 key k 的總產出（主＋副，同 key 疊加；比對用 equals）。 */
    long outputOf(AEKey k) {
        long tot = 0;
        for (int i = 0; i < outKey.length; i++) {
            if (outKey[i].equals(k)) {
                tot = LpMath.addX(tot, outAmt[i]);
            }
        }
        return tot;
    }

    /** 此樣板每輪對 key k 的總消耗。 */
    long inputOf(AEKey k) {
        long tot = 0;
        for (int i = 0; i < inKey.length; i++) {
            if (inKey[i].equals(k)) {
                tot = LpMath.addX(tot, inAmt[i]);
            }
        }
        return tot;
    }
}
