package com.firefly.lumen.exp;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Trivial smoke test for the experience module skeleton. Kept as a plain unit
 * test (no Spring context, no external infra) so the build stays green and
 * fast; later chapters replace this with real BFF/web tests.
 */
class ExpLendingApplicationTest {

    @Test
    void applicationClassIsPresent() {
        assertNotNull(ExpLendingApplication.class);
    }
}
