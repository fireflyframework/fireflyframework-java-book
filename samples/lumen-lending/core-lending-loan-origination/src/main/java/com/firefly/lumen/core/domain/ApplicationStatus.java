package com.firefly.lumen.core.domain;

/**
 * Lifecycle of a {@link LoanApplication}.
 *
 * <p>Mirrors the real firefly-oss
 * {@code com.firefly.core.lending.origination.interfaces.enums.ApplicationStatusEnum},
 * trimmed to the states the book's loan-origination slice exercises. The legal
 * transitions are enforced by {@link LoanApplication}, not by this type.
 */
public enum ApplicationStatus {

    /** Captured but not yet submitted for review. */
    DRAFT,

    /** Submitted by the applicant; awaiting a credit officer. */
    SUBMITTED,

    /** A credit officer is actively reviewing the application. */
    UNDER_REVIEW,

    /** Approved; an offer may be proposed to the applicant. */
    APPROVED,

    /** Declined; a terminal state. */
    REJECTED,

    /** Withdrawn before a decision was reached; a terminal state. */
    CANCELLED;

    /**
     * @return {@code true} if no further transition is allowed from this state
     */
    public boolean isTerminal() {
        return this == APPROVED || this == REJECTED || this == CANCELLED;
    }
}
