package com.firefly.lumen.exp.client;

import com.firefly.lumen.exp.dto.ApplicationDetailDTO;
import com.firefly.lumen.exp.dto.CreateApplicationRequest;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * The experience-to-domain SDK seam: the reactive port the experience (BFF) tier calls
 * to reach the {@code domain-lending-loan-origination} orchestration service.
 *
 * <p>In production this interface is backed by the <em>generated domain SDK</em> client.
 * The real Firefly experience service ({@code exp-lending}) injects
 * {@code com.firefly.domain.lending.loan.origination.sdk.api.LoanOriginationApi} — a
 * {@code WebClient}-based client generated from the domain service's OpenAPI contract and
 * wired by a {@code ClientFactory} (see {@link com.firefly.lumen.exp.config.LoanOriginationClientConfig}).
 * We hand-roll a trimmed port here so the book sample compiles and its tests run with
 * <strong>no running domain service and no Docker</strong>; an in-memory stub lives under
 * {@code src/test/java}.
 *
 * <p>Every method is reactive ({@link Mono}) because the whole experience stack — controller,
 * service, the domain HTTP call — is non-blocking end to end. Each call carries a deterministic
 * idempotency key so a retried channel request dedupes downstream without the client minting a
 * resource id (see {@link com.firefly.lumen.exp.service.ApplicationService}).
 */
public interface LoanOriginationDomainClient {

    /**
     * Submits a new loan application to the domain origination service.
     *
     * @param request        the channel-shaped create request, already validated by the BFF
     * @param idempotencyKey deterministic key so retries of the same logical request dedupe
     * @return the created application's detail view
     */
    Mono<ApplicationDetailDTO> submitApplication(CreateApplicationRequest request, String idempotencyKey);

    /**
     * Fetches a single loan application by its identifier.
     *
     * @param applicationId  the application's server-assigned identifier
     * @param idempotencyKey deterministic key for safe read retries
     * @return the application's detail view, or an empty {@link Mono} if it does not exist
     */
    Mono<ApplicationDetailDTO> getApplication(UUID applicationId, String idempotencyKey);
}
