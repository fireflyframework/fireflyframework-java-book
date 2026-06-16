package com.firefly.lumen.exp.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Channel (front-end facing) request to create a loan application.
 *
 * <p>Mirrors the real {@code exp-lending} {@code CreateApplicationCommand}: a product, a
 * requested amount, a term in months, an optional purpose, and an optional soft link to the
 * simulation that produced it. Bean Validation constraints fail fast at the edge; the
 * {@link com.firefly.lumen.exp.service.ApplicationService} re-validates defensively before
 * crossing the SDK seam.
 *
 * @param productId       the product the applicant is applying for (required)
 * @param requestedAmount the requested principal, strictly positive (required)
 * @param term            the term in months, at least 1 (required)
 * @param purpose         a free-text loan purpose (optional)
 * @param simulationId    soft link to the simulation that produced this application (optional)
 */
public record CreateApplicationRequest(

        @NotNull(message = "productId is required")
        UUID productId,

        @NotNull(message = "requestedAmount is required")
        @DecimalMin(value = "0.01", message = "requestedAmount must be strictly positive")
        BigDecimal requestedAmount,

        @NotNull(message = "term is required")
        @Min(value = 1, message = "term must be at least 1 month")
        Integer term,

        String purpose,

        UUID simulationId
) {
}
