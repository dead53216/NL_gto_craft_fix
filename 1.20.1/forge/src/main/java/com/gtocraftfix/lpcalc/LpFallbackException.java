package com.gtocraftfix.lpcalc;

/** 求解期回退例外；帶 FallbackReason 供 LpStats 計數與 LpFallbackQueue 走回退。 */
public final class LpFallbackException extends RuntimeException {

    private final FallbackReason reason;

    public LpFallbackException(FallbackReason reason, String detail) {
        // 回退是常態流程，不需要堆疊（省背景緒成本）
        super(reason + ": " + detail, null, false, false);
        this.reason = reason;
    }

    public FallbackReason reason() {
        return reason;
    }
}
