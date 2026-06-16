package com.firefly.lumen.exp.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Channel-shaped summary view of a loan application, for list responses.
 *
 * @param applicationId   the server-assigned application identifier
 * @param status          the current application status
 * @param requestedAmount the requested principal
 * @param createdAt       creation timestamp
 */
public record ApplicationSummaryDTO(
        UUID applicationId,
        String status,
        BigDecimal requestedAmount,
        LocalDateTime createdAt
) {
}
