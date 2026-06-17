package com.firefly.lumen.domain.web;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Channel-shaped detail view the domain (orchestration) tier returns to the experience (BFF)
 * tier over HTTP.
 *
 * <p>The field set mirrors the experience tier's {@code ApplicationDetailDTO} exactly, so the
 * BFF's {@code WebClient} maps the response body field-for-field with no translation. The
 * {@code applicationId} is the id the core system of record assigned to the loan application that
 * the {@code RegisterApplicationSaga} created; {@code simulationId}, {@code requestedAmount},
 * {@code term} and {@code purpose} are echoed back from the submitted request.
 *
 * @param applicationId   the core-assigned application identifier
 * @param simulationId    soft link to the simulation that produced this application
 * @param status          the current application status
 * @param requestedAmount the requested principal
 * @param term            the term in months
 * @param purpose         the loan purpose
 * @param createdAt       creation timestamp
 * @param updatedAt       last-update timestamp
 */
public record ApplicationDetailView(
        UUID applicationId,
        UUID simulationId,
        String status,
        BigDecimal requestedAmount,
        Integer term,
        String purpose,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
