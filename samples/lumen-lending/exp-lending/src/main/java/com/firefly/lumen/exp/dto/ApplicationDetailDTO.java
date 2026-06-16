package com.firefly.lumen.exp.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Channel-shaped full detail view of a loan application returned by the experience tier.
 *
 * <p>This is the BFF's own view, decoupled from the domain SDK's wire model: the service
 * maps the domain response into this record so the front-end never depends on domain
 * internals. The {@code simulationId} is echoed back so the front-end can persist the
 * traceability link without a follow-up call.
 *
 * @param applicationId   the server-assigned application identifier
 * @param simulationId    soft link to the simulation that produced this application
 * @param status          the current application status
 * @param requestedAmount the requested principal
 * @param term            the term in months
 * @param purpose         the loan purpose
 * @param createdAt       creation timestamp
 * @param updatedAt       last-update timestamp
 */
public record ApplicationDetailDTO(
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
