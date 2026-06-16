package com.firefly.lumen.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Trivial smoke test for the domain module skeleton. Kept as a plain unit test
 * (no Spring context, no external infra) so the build stays green and fast;
 * later chapters replace this with real orchestration tests.
 */
class DomainLendingApplicationTest {

    @Test
    void applicationClassIsPresent() {
        assertNotNull(DomainLendingApplication.class);
    }
}
