package com.firefly.lumen.core.dto;

import com.firefly.lumen.core.domain.ApplicationStatus;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * View of a {@link com.firefly.lumen.core.domain.LoanApplication} returned by the
 * REST layer. A record, so it is immutable and serializes straight to JSON.
 *
 * @param loanApplicationId surrogate identifier
 * @param applicationNumber stable public reference
 * @param applicantId       applicant who owns the request
 * @param requestedAmount   requested principal
 * @param currency          ISO-4217 currency code
 * @param termMonths        repayment term in months
 * @param purpose           business purpose of the loan
 * @param status            current lifecycle status
 * @param decisionReason    reason captured on rejection/cancellation, if any
 * @param createdAt         creation timestamp
 * @param updatedAt         last-modification timestamp
 */
public record LoanApplicationResponse(
        UUID loanApplicationId,
        UUID applicationNumber,
        UUID applicantId,
        BigDecimal requestedAmount,
        String currency,
        Integer termMonths,
        String purpose,
        ApplicationStatus status,
        String decisionReason,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
