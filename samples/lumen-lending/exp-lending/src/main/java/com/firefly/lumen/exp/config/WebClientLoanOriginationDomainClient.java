package com.firefly.lumen.exp.config;

import com.firefly.lumen.exp.client.LoanOriginationDomainClient;
import com.firefly.lumen.exp.dto.ApplicationDetailDTO;
import com.firefly.lumen.exp.dto.CreateApplicationRequest;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Production {@link LoanOriginationDomainClient} adapter backed by a {@link WebClient}.
 *
 * <p>Stand-in for the generated domain SDK client: it forwards the experience-tier calls to the
 * domain Loan Origination service over HTTP, propagating the deterministic idempotency key as the
 * standard {@code Idempotency-Key} header. The book sample never exercises this path in tests
 * (which inject an in-memory stub), but it is wired so chapters can show the real seam.
 */
class WebClientLoanOriginationDomainClient implements LoanOriginationDomainClient {

    private static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    private static final String APPLICATIONS_PATH = "/api/v1/applications";

    private final WebClient webClient;

    WebClientLoanOriginationDomainClient(WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public Mono<ApplicationDetailDTO> submitApplication(CreateApplicationRequest request, String idempotencyKey) {
        return webClient.post()
                .uri(APPLICATIONS_PATH)
                .header(IDEMPOTENCY_HEADER, idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(ApplicationDetailDTO.class);
    }

    @Override
    public Mono<ApplicationDetailDTO> getApplication(UUID applicationId, String idempotencyKey) {
        return webClient.get()
                .uri(APPLICATIONS_PATH + "/{id}", applicationId)
                .header(IDEMPOTENCY_HEADER, idempotencyKey)
                .retrieve()
                .bodyToMono(ApplicationDetailDTO.class);
    }
}
