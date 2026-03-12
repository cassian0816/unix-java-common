package exchange.antx.common.util;

import com.google.common.base.Strings;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class DecimalUtil {

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

    public static BigDecimal roundByStepSize(BigDecimal value, BigDecimal stepSize, RoundingMode roundingMode) {
        return value.divide(stepSize, 0, roundingMode).multiply(stepSize);
    }

    public static BigDecimal divideRoundByStepSize(BigDecimal value, BigDecimal divisor, BigDecimal stepSize,
                                                   RoundingMode roundingMode) {
        return value.divide(divisor.multiply(stepSize), 0, roundingMode).multiply(stepSize);
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
}