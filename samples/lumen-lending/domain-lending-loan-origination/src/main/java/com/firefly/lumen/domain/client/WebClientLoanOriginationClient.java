package com.firefly.lumen.domain.client;

import com.firefly.lumen.domain.web.ApplicationDetailView;
import com.firefly.lumen.domain.web.CoreLoanApplicationReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Live {@link LoanOriginationClient} adapter that calls the core system of record over HTTP.
 *
 * <p>Stand-in for the generated core SDK client: it forwards the write-side saga steps to the
 * {@code core-lending-loan-origination} REST API, and (as a {@link CoreLoanApplicationReader})
 * serves the domain web layer's GET-by-id read path. The book sample's tests never exercise this
 * path — they inject the in-memory {@code StubLoanOriginationClient} — so this adapter is only
 * wired when the core base path is configured (see {@link LiveLoanOriginationClientConfig}).
 *
 * <p>The {@link LoanOriginationClient} port maps one-to-one onto saga steps. Only the root
 * {@code createLoanApplication} (and its compensating {@code removeLoanApplication}) reach the
 * trimmed core controller, which exposes just the loan-application resource. The dependent steps
 * ({@code addApplicant}, {@code proposeOffer}) have no core endpoint in this slice, so they
 * complete in-process with a synthesized id — enough for the saga to finish while the real write
 * and its compensation flow over HTTP.
 */
public class WebClientLoanOriginationClient implements LoanOriginationClient, CoreLoanApplicationReader {

    private static final Logger log = LoggerFactory.getLogger(WebClientLoanOriginationClient.class);
    private static final String LOAN_APPLICATIONS_PATH = "/api/v1/loan-applications";

    /** Defaults for the core fields the trimmed write seam does not carry. */
    private static final String DEFAULT_CURRENCY = "EUR";
    private static final String DEFAULT_PURPOSE = "GENERAL";
    private static final int DEFAULT_TERM_MONTHS = 12;

    private final WebClient webClient;

    public WebClientLoanOriginationClient(WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public Mono<UUID> createLoanApplication(String applicantName, long amount) {
        CoreCreateRequest body = new CoreCreateRequest(
                UUID.randomUUID(),
                BigDecimal.valueOf(amount, 2),
                DEFAULT_CURRENCY,
                DEFAULT_TERM_MONTHS,
                DEFAULT_PURPOSE);
        log.debug("Core create loan-application applicant={} amountMinor={}", applicantName, amount);
        return webClient.post()
                .uri(LOAN_APPLICATIONS_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(CoreLoanApplicationResponse.class)
                .map(CoreLoanApplicationResponse::loanApplicationId);
    }

    @Override
    public Mono<Void> removeLoanApplication(UUID loanApplicationId) {
        log.debug("Core compensation delete loan-application id={}", loanApplicationId);
        return webClient.delete()
                .uri(LOAN_APPLICATIONS_PATH + "/{id}", loanApplicationId)
                .retrieve()
                .bodyToMono(Void.class);
    }

    @Override
    public Mono<UUID> addApplicant(UUID loanApplicationId, String applicantName) {
        // The trimmed core controller has no applicant-party endpoint; the dependent step
        // completes in-process so the saga finishes. Faithful to the sample's reduced surface.
        return Mono.fromSupplier(UUID::randomUUID);
    }

    @Override
    public Mono<UUID> proposeOffer(UUID loanApplicationId, long amount, int annualRateBps) {
        // As above: no core offer endpoint in this slice, so the offer step is in-process.
        return Mono.fromSupplier(UUID::randomUUID);
    }

    @Override
    public Mono<ApplicationDetailView> findById(UUID applicationId) {
        return webClient.get()
                .uri(LOAN_APPLICATIONS_PATH + "/{id}", applicationId)
                .retrieve()
                .bodyToMono(CoreLoanApplicationResponse.class)
                .map(core -> new ApplicationDetailView(
                        core.loanApplicationId(),
                        null,
                        core.status(),
                        core.requestedAmount(),
                        core.termMonths(),
                        core.purpose(),
                        core.createdAt(),
                        core.updatedAt()))
                .onErrorResume(WebClientResponseException.NotFound.class, e -> Mono.empty());
    }

    /** Domain's view of the core create payload (mirrors core's {@code CreateLoanApplicationRequest}). */
    record CoreCreateRequest(
            UUID applicantId,
            BigDecimal requestedAmount,
            String currency,
            Integer termMonths,
            String purpose) {
    }

    /** Domain's view of the core response payload (the subset the domain consumes). */
    record CoreLoanApplicationResponse(
            UUID loanApplicationId,
            BigDecimal requestedAmount,
            String currency,
            Integer termMonths,
            String purpose,
            String status,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }
}
