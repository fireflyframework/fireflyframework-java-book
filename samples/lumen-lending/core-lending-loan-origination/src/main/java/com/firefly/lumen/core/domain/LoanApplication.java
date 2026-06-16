package com.firefly.lumen.core.domain;

import com.firefly.lumen.core.Money;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A loan application: the system-of-record aggregate for an applicant's request
 * to borrow money.
 *
 * <p>This is the teaching-subset counterpart of the real firefly-oss
 * {@code com.firefly.core.lending.origination.models.entities.LoanApplication}.
 * It keeps the same persistence patterns — Spring Data R2DBC {@code @Table}, a
 * UUID {@code @Id}, snake_case {@code @Column} mappings, and {@code created_at}/
 * {@code updated_at} audit columns — but trims the dozens of distributor,
 * disbursement and channel fields down to the handful chapters 6–9 slice.
 *
 * <p>Unlike the firefly-oss anemic entity, this aggregate also owns the legal
 * status transitions ({@link #submit()}, {@link #startReview()},
 * {@link #approve()}, {@link #reject(String)}, {@link #cancel(String)}) so the
 * book can show domain behavior living next to the data it guards.
 */
@Table("loan_application")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoanApplication implements Persistable<UUID> {

    @Id
    @Column("loan_application_id")
    private UUID loanApplicationId;

    /** Stable public reference, distinct from the surrogate primary key. */
    @Column("application_number")
    private UUID applicationNumber;

    /** Applicant who owns this request (soft reference; no cross-context FK). */
    @Column("applicant_id")
    private UUID applicantId;

    /** Requested principal, stored as a fixed-scale decimal. */
    @Column("requested_amount")
    private BigDecimal requestedAmount;

    /** ISO-4217 currency code of {@link #requestedAmount} (e.g. {@code EUR}). */
    @Column("currency")
    private String currency;

    /** Requested repayment term in whole months. */
    @Column("term_months")
    private Integer termMonths;

    /** Free-text purpose of the loan (e.g. {@code HOME_IMPROVEMENT}). */
    @Column("purpose")
    private String purpose;

    @Column("status")
    private ApplicationStatus status;

    /** Reason captured when the application is rejected or cancelled. */
    @Column("decision_reason")
    private String decisionReason;

    @Column("created_at")
    private LocalDateTime createdAt;

    @Column("updated_at")
    private LocalDateTime updatedAt;

    /**
     * Transient flag (never persisted) that tells Spring Data R2DBC whether a
     * {@code save} should INSERT or UPDATE. Because the primary key is a
     * client-assigned UUID rather than a DB-generated value, R2DBC cannot infer
     * "new vs. existing" from a null id; {@link Persistable} makes it explicit.
     */
    @Transient
    @Builder.Default
    private boolean newEntity = false;

    /** {@inheritDoc} */
    @Override
    public UUID getId() {
        return loanApplicationId;
    }

    /** {@inheritDoc} */
    @Override
    public boolean isNew() {
        return newEntity;
    }

    /**
     * Marks this aggregate as a freshly created entity so the next {@code save}
     * performs an INSERT.
     *
     * @return this aggregate, for chaining
     */
    public LoanApplication markNew() {
        this.newEntity = true;
        return this;
    }

    /**
     * @return the requested principal as a {@link Money} value object (minor
     *         units), or {@code null} if no amount has been captured yet
     */
    public Money requestedMoney() {
        if (requestedAmount == null) {
            return null;
        }
        return Money.of(requestedAmount.movePointRight(2).longValueExact());
    }

    /** Moves a {@link ApplicationStatus#DRAFT} application to {@code SUBMITTED}. */
    public void submit() {
        requireStatus(ApplicationStatus.DRAFT, "submit");
        transitionTo(ApplicationStatus.SUBMITTED);
    }

    /** Moves a {@code SUBMITTED} application to {@code UNDER_REVIEW}. */
    public void startReview() {
        requireStatus(ApplicationStatus.SUBMITTED, "review");
        transitionTo(ApplicationStatus.UNDER_REVIEW);
    }

    /** Approves an application that is {@code UNDER_REVIEW}. */
    public void approve() {
        requireStatus(ApplicationStatus.UNDER_REVIEW, "approve");
        transitionTo(ApplicationStatus.APPROVED);
    }

    /**
     * Rejects an application that is {@code SUBMITTED} or {@code UNDER_REVIEW}.
     *
     * @param reason human-readable decline reason; required
     */
    public void reject(String reason) {
        if (status != ApplicationStatus.SUBMITTED && status != ApplicationStatus.UNDER_REVIEW) {
            throw illegalTransition("reject");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A rejection reason is required");
        }
        this.decisionReason = reason;
        transitionTo(ApplicationStatus.REJECTED);
    }

    /**
     * Cancels an application that has not yet reached a terminal state.
     *
     * @param reason human-readable cancellation reason; required
     */
    public void cancel(String reason) {
        if (status != null && status.isTerminal()) {
            throw illegalTransition("cancel");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A cancellation reason is required");
        }
        this.decisionReason = reason;
        transitionTo(ApplicationStatus.CANCELLED);
    }

    private void requireStatus(ApplicationStatus expected, String action) {
        if (status != expected) {
            throw illegalTransition(action);
        }
    }

    private void transitionTo(ApplicationStatus next) {
        this.status = next;
        this.updatedAt = LocalDateTime.now();
    }

    private IllegalStateException illegalTransition(String action) {
        return new IllegalStateException(
                "Cannot " + action + " a loan application in status " + status);
    }
}
