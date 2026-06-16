package com.firefly.lumen.core.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * An offer the lender proposes against an approved {@link LoanApplication}.
 *
 * <p>Trimmed counterpart of the firefly-oss
 * {@code com.firefly.core.lending.origination.models.entities.ProposedOffer}: it
 * keeps the priced terms (amount, term, rate, monthly payment) and the soft link
 * back to the originating application, dropping the workflow/audit columns the
 * book does not exercise.
 */
@Table("proposed_offer")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProposedOffer {

    @Id
    @Column("proposed_offer_id")
    private UUID proposedOfferId;

    /** Soft link to the application this offer was made against. */
    @Column("loan_application_id")
    private UUID loanApplicationId;

    @Column("offered_amount")
    private BigDecimal offeredAmount;

    @Column("currency")
    private String currency;

    @Column("term_months")
    private Integer termMonths;

    /** Nominal annual interest rate as a percentage (e.g. {@code 6.50}). */
    @Column("annual_interest_rate")
    private BigDecimal annualInterestRate;

    @Column("monthly_payment")
    private BigDecimal monthlyPayment;

    @Column("created_at")
    private LocalDateTime createdAt;
}
