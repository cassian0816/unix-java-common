package exchange.antx.common.util;

import com.google.common.base.Strings;
import com.google.common.primitives.UnsignedInteger;
import com.google.common.primitives.UnsignedLong;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

public class DecimalUtil {

    private static final BigDecimal FEE_RATE_DECIMAL_BASE = new BigDecimal(1000000);

    public static boolean isZero(BigDecimal val) {
        return val.compareTo(BigDecimal.ZERO) == 0;
    }

    public static boolean isPositive(BigDecimal val) {
        return val.compareTo(BigDecimal.ZERO) > 0;
    }

    public static boolean isPositiveOrZero(BigDecimal val) {
        return val.compareTo(BigDecimal.ZERO) >= 0;
    }

    public static boolean isNegative(BigDecimal val) {
        return val.compareTo(BigDecimal.ZERO) < 0;
    }

    public static boolean isNegativeOrZero(BigDecimal val) {
        return val.compareTo(BigDecimal.ZERO) <= 0;
    }

    public static boolean isEqual(BigDecimal v1, BigDecimal v2) {
        return v1.compareTo(v2) == 0;
    }

    public static boolean isGreater(BigDecimal v1, BigDecimal v2) {
        return v1.compareTo(v2) > 0;
    }

    public static boolean isGreaterEqual(BigDecimal v1, BigDecimal v2) {
        return v1.compareTo(v2) >= 0;
    }

    public static boolean isLess(BigDecimal v1, BigDecimal v2) {
        return v1.compareTo(v2) < 0;
    }

    public static boolean isLessEqual(BigDecimal v1, BigDecimal v2) {
        return v1.compareTo(v2) <= 0;
    }

    public static BigDecimal fromString(String str) {
        if (Strings.isNullOrEmpty(str)) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(str);
    }

    public static BigDecimal feeRateIntToDecimal(int feeRate) {
        return new BigDecimal(feeRate).divide(FEE_RATE_DECIMAL_BASE, 18, RoundingMode.DOWN);
    }

    public static int feeRateDecimalToInt(BigDecimal feeRate) {
        return feeRate.multiply(FEE_RATE_DECIMAL_BASE).toBigInteger().intValueExact();
    }

    /**
     * 按照stepSize精度要求执行roundingMode
     */
    public static BigDecimal roundByStepSize(
            BigDecimal value,
            BigDecimal stepSize,
            RoundingMode roundingMode) {
        return value.divide(
                        stepSize,
                        0,
                        roundingMode)
                .multiply(stepSize);
    }

    /**
     * 按照stepSize精度要求执行divide & roundingMode
     */
    public static BigDecimal divideRoundByStepSize(
            BigDecimal value,
            BigDecimal divisor,
            BigDecimal stepSize,
            RoundingMode roundingMode) {
        return value.divide(
                        divisor.multiply(stepSize),
                        0,
                        roundingMode)
                .multiply(stepSize);
    }

    public static BigDecimal clamp(BigDecimal preference, BigDecimal min, BigDecimal max) {
        if (isLess(preference, min)) {
            return min;
        }

        if (isGreater(preference, max)) {
            return max;
        }

        return preference;
    }

    public static BigDecimal fromString(String value, int scale) {
        BigInteger unscaled = new BigInteger(value);
        return new BigDecimal(unscaled, scale);
    }

    public static BigDecimal fromSignedLong(long value, int scale) {
        return new BigDecimal(BigInteger.valueOf(value), scale);
    }

    public static BigDecimal fromUnsignedLong(long value, int scale) {
        BigInteger bitIntVal = UnsignedLong.fromLongBits(value).bigIntegerValue();
        return new BigDecimal(bitIntVal, scale);
    }

    public static BigDecimal fromUnsignedInt(int value, int scale) {
        BigInteger bitIntVal = UnsignedInteger.fromIntBits(value).bigIntegerValue();
        return new BigDecimal(bitIntVal, scale);
    }

    public static BigDecimal fromSignedInt(int value, int scale) {
        return BigDecimal.valueOf(value, scale);
    }

    public static void main(String[] args) {
        BigDecimal a = fromUnsignedLong(1870093L, 3);
        System.out.println(a.toPlainString());

        BigDecimal d = fromUnsignedLong(-1870093L, 3);
        System.out.println(d.toPlainString());

        BigDecimal f = fromUnsignedInt(-1400000, 6);
        System.out.println(f.toPlainString());
    }
}