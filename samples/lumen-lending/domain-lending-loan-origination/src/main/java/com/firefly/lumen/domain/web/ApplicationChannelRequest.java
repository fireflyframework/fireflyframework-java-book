package com.firefly.lumen.domain.web;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Channel-shaped create request the domain (orchestration) tier accepts from the experience
 * (BFF) tier over HTTP.
 *
 * <p>The field set is identical to the experience tier's {@code CreateApplicationRequest} so the
 * BFF's {@code WebClient} body deserializes here field-for-field: a product, a requested
 * principal, a term in months, an optional purpose, and an optional soft link to the simulation
 * that produced the application. The domain controller maps it onto the saga's per-step inputs.
 *
 * <p>This is the trimmed sample's stand-in for the generated domain SDK request model; the real
 * service would bind the OpenAPI-generated {@code CreateApplicationCommand}.
 *
 * @param productId       the product the applicant is applying for
 * @param requestedAmount the requested principal (major units, e.g. {@code 25000.00})
 * @param term            the term in months
 * @param purpose         a free-text loan purpose (optional)
 * @param simulationId    soft link to the simulation that produced this application (optional)
 */
public record ApplicationChannelRequest(
        UUID productId,
        BigDecimal requestedAmount,
        Integer term,
        String purpose,
        UUID simulationId
) {
}
