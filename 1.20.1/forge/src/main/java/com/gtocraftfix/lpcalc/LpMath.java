package com.gtocraftfix.lpcalc;

/** 整數域紀律（§6.5）：全程 long ＋ exact 運算；ArithmeticException → OVERFLOW 回退。 */
final class LpMath {

    private LpMath() {}

    static long addX(long a, long b) {
        try {
            return Math.addExact(a, b);
        } catch (ArithmeticException e) {
            throw new LpFallbackException(FallbackReason.OVERFLOW, "add " + a + "+" + b);
        }
    }

    static long mulX(long a, long b) {
        try {
            return Math.multiplyExact(a, b);
        } catch (ArithmeticException e) {
            throw new LpFallbackException(FallbackReason.OVERFLOW, "mul " + a + "*" + b);
        }
    }

    /** ceil(a/b)，a ≥ 0、b ≥ 1（BAD_PATTERN 保證分母 ≥1）。 */
    static long ceilDiv(long a, long b) {
        return addX(a, b - 1) / b;
    }
}
