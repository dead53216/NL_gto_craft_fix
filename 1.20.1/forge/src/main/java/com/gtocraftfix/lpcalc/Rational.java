package com.gtocraftfix.lpcalc;

import java.math.BigInteger;

/**
 * 有理數（不可變）。只允許存在於兩處：SCC 高斯消去（§6.3）與 CRAFT_LESS 速率（§7），
 * 離開前必經 {@link #ceilToLong()} 整數化。
 * 內部一律 BigInteger（SCC 矩陣極小、正確性優先）；任一分子/分母超過 128 bit → OVERFLOW（§4.3）。
 */
final class Rational {

    static final Rational ZERO = new Rational(BigInteger.ZERO, BigInteger.ONE);
    static final Rational ONE = new Rational(BigInteger.ONE, BigInteger.ONE);

    private final BigInteger num;
    private final BigInteger den; // 恆 > 0

    private Rational(BigInteger num, BigInteger den) {
        this.num = num;
        this.den = den;
    }

    static Rational of(long num, long den) {
        return make(BigInteger.valueOf(num), BigInteger.valueOf(den));
    }

    private static Rational make(BigInteger n, BigInteger d) {
        if (d.signum() == 0) {
            throw new LpFallbackException(FallbackReason.OVERFLOW, "den=0");
        }
        if (d.signum() < 0) {
            n = n.negate();
            d = d.negate();
        }
        BigInteger g = n.gcd(d);
        if (!g.equals(BigInteger.ONE) && g.signum() != 0) {
            n = n.divide(g);
            d = d.divide(g);
        }
        if (n.bitLength() > 128 || d.bitLength() > 128) {
            throw new LpFallbackException(FallbackReason.OVERFLOW, "rational >128bit");
        }
        return new Rational(n, d);
    }

    Rational add(Rational o) {
        return make(num.multiply(o.den).add(o.num.multiply(den)), den.multiply(o.den));
    }

    Rational sub(Rational o) {
        return make(num.multiply(o.den).subtract(o.num.multiply(den)), den.multiply(o.den));
    }

    Rational mul(Rational o) {
        return make(num.multiply(o.num), den.multiply(o.den));
    }

    Rational div(Rational o) {
        if (o.isZero()) {
            throw new LpFallbackException(FallbackReason.OVERFLOW, "div by zero");
        }
        return make(num.multiply(o.den), den.multiply(o.num));
    }

    boolean isZero() {
        return num.signum() == 0;
    }

    boolean isNegative() {
        return num.signum() < 0;
    }

    /** 離開有理數域的唯一出口：ceil 後轉 long，不可表示 → OVERFLOW。 */
    long ceilToLong() {
        BigInteger[] qr = num.divideAndRemainder(den);
        BigInteger q = qr[0];
        if (qr[1].signum() != 0 && num.signum() > 0) {
            q = q.add(BigInteger.ONE);
        }
        if (q.bitLength() > 63) {
            throw new LpFallbackException(FallbackReason.OVERFLOW, "ceilToLong >63bit");
        }
        return q.longValueExact();
    }
}
