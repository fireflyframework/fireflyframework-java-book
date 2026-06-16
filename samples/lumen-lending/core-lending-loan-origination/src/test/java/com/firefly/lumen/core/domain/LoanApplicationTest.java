package com.firefly.lumen.core.domain;

import com.firefly.lumen.core.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the {@link LoanApplication} aggregate's behavior: the legal
 * status transitions and the {@link Money} projection. No Spring, no database.
 */
class LoanApplicationTest {

    private LoanApplication draft() {
        return LoanApplication.builder()
                .requestedAmount(new BigDecimal("1500.00"))
                .currency("EUR")
                .termMonths(24)
                .purpose("HOME_IMPROVEMENT")
                .status(ApplicationStatus.DRAFT)
                .build();
    }

    @Test
    void happyPathReachesApproved() {
        LoanApplication app = draft();
        app.submit();
        assertEquals(ApplicationStatus.SUBMITTED, app.getStatus());
        app.startReview();
        assertEquals(ApplicationStatus.UNDER_REVIEW, app.getStatus());
        app.approve();
        assertEquals(ApplicationStatus.APPROVED, app.getStatus());
        assertTrue(app.getStatus().isTerminal());
    }

    @Test
    void cannotApproveADraft() {
        LoanApplication app = draft();
        assertThrows(IllegalStateException.class, app::approve);
    }

    @Test
    void rejectRequiresAReason() {
        LoanApplication app = draft();
        app.submit();
        assertThrows(IllegalArgumentException.class, () -> app.reject("  "));
    }

    @Test
    void rejectRecordsReasonAndIsTerminal() {
        LoanApplication app = draft();
        app.submit();
        app.reject("Insufficient income");
        assertEquals(ApplicationStatus.REJECTED, app.getStatus());
        assertEquals("Insufficient income", app.getDecisionReason());
        assertTrue(app.getStatus().isTerminal());
    }

    @Test
    void cannotCancelATerminalApplication() {
        LoanApplication app = draft();
        app.submit();
        app.reject("Declined");
        assertThrows(IllegalStateException.class, () -> app.cancel("Too late"));
    }

    @Test
    void requestedMoneyConvertsToMinorUnits() {
        LoanApplication app = draft();
        assertEquals(Money.of(150_000), app.requestedMoney());
    }
}
