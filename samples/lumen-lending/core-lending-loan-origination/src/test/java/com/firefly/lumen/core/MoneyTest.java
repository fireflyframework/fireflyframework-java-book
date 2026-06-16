package com.firefly.lumen.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MoneyTest {

    @Test
    void ofRejectsNegativeAmounts() {
        assertThrows(IllegalArgumentException.class, () -> Money.of(-1));
    }

    @Test
    void minusReturnsTheDifference() {
        assertEquals(Money.of(50), Money.of(150).minus(Money.of(100)));
    }

    @Test
    void minusThrowsWhenResultWouldBeNegative() {
        assertThrows(IllegalArgumentException.class,
                () -> Money.of(100).minus(Money.of(150)));
    }
}
