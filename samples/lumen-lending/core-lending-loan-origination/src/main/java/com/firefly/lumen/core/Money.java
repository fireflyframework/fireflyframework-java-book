package com.firefly.lumen.core;

/**
 * A non-negative monetary amount, held as an integer number of minor units
 * (e.g. cents) to avoid floating-point rounding error.
 *
 * <p>This is the seed value object for the lending domain; later chapters grow
 * it (currency, allocation, rounding policies). For now it only needs to be a
 * real, well-behaved value type with an enforced invariant.
 *
 * @param minorUnits the amount in minor units; never negative
 */
public record Money(long minorUnits) {

    /**
     * Creates a {@code Money} of the given minor units.
     *
     * @param minorUnits amount in minor units
     * @return the value object
     * @throws IllegalArgumentException if {@code minorUnits} is negative
     */
    public static Money of(long minorUnits) {
        if (minorUnits < 0) {
            throw new IllegalArgumentException(
                    "Money cannot be negative: " + minorUnits);
        }
        return new Money(minorUnits);
    }

    /**
     * Subtracts {@code other} from this amount.
     *
     * @param other the amount to subtract
     * @return the difference
     * @throws IllegalArgumentException if the result would be negative
     */
    public Money minus(Money other) {
        return Money.of(this.minorUnits - other.minorUnits);
    }
}
