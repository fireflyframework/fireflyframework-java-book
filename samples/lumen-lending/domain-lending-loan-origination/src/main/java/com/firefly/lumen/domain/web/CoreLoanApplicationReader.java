package com.firefly.lumen.domain.web;

import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Read seam the domain web layer uses to fetch a loan application back from the core system of
 * record for the GET-by-id endpoint.
 *
 * <p>The write-side SDK seam ({@link com.firefly.lumen.domain.client.LoanOriginationClientConfig
 * LoanOriginationClient}) maps one-to-one onto saga steps and deliberately carries no read
 * operation, so the read path gets its own small port. In the live configuration this is backed
 * by a {@code WebClient} pointed at the core service; it is only present when the core base path
 * is configured.
 */
public interface CoreLoanApplicationReader {

    /**
     * Fetches a loan application from core by its id and projects it onto the channel detail view.
     *
     * @param applicationId the core-assigned application id
     * @return the channel detail view, or an empty {@link Mono} if core has no such application
     */
    Mono<ApplicationDetailView> findById(UUID applicationId);
}
