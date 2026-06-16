package com.firefly.lumen.core.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.fireflyframework.annotations.ValidAmount;
import org.fireflyframework.annotations.ValidCurrencyCode;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Payload to open a new loan application.
 *
 * <p>Validation mixes plain Jakarta constraints with the finance-aware
 * constraints from {@code fireflyframework-validators}: {@link ValidAmount}
 * checks the principal is a sane, positive monetary value, and
 * {@link ValidCurrencyCode} checks the currency is a real ISO-4217 code. These
 * are the same constraint annotations the firefly-oss services use on their
 * DTOs, surfaced here on a clean request record.
 *
 * @param applicantId     applicant placing the request; required
 * @param requestedAmount principal requested; required, positive
 * @param currency        ISO-4217 currency code (e.g. {@code EUR}); required
 * @param termMonths      repayment term in whole months; required, positive
 * @param purpose         business purpose of the loan; required
 */
public record CreateLoanApplicationRequest(

        @NotNull(message = "Applicant ID is required")
        UUID applicantId,

        @NotNull(message = "Requested amount is required")
        @ValidAmount(min = 0.01, message = "Requested amount must be a positive monetary value")
        BigDecimal requestedAmount,

        @NotBlank(message = "Currency is required")
        @ValidCurrencyCode(message = "Currency must be a valid ISO-4217 code")
        String currency,

        @NotNull(message = "Term is required")
        @Positive(message = "Term must be a positive number of months")
        Integer termMonths,

        @NotBlank(message = "Loan purpose is required")
        String purpose
) {
}
