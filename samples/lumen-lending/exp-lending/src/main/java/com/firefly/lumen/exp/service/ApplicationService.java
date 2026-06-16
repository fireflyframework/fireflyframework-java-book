package com.firefly.lumen.exp.service;

import com.firefly.lumen.exp.client.LoanOriginationDomainClient;
import com.firefly.lumen.exp.dto.ApplicationDetailDTO;
import com.firefly.lumen.exp.dto.CreateApplicationRequest;
import com.firefly.lumen.exp.util.IdempotencyKeys;
import org.fireflyframework.web.error.exceptions.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Experience-tier (BFF) application service.
 *
 * <p>Mirrors the real {@code exp-lending} {@code ApplicationServiceImpl}: it validates the
 * channel request at the edge, derives a <em>deterministic</em> idempotency key from stable
 * input fields, crosses the SDK seam ({@link LoanOriginationDomainClient}) to the domain
 * origination service, and surfaces downstream failures as
 * {@link BusinessException}s carrying an HTTP status and a stable error code (so
 * {@code fireflyframework-web}'s {@code GlobalExceptionHandler} renders a problem-detail).
 *
 * <p>The idempotency key uses {@link IdempotencyKeys#of(String...)}: the same logical request
 * (a retry of the same product / amount / term / purpose / simulation) yields the same key, so
 * the domain tier dedupes without the channel minting a resource id.
 */
@Service
public class ApplicationService {

    private static final Logger log = LoggerFactory.getLogger(ApplicationService.class);

    private final LoanOriginationDomainClient domainClient;

    public ApplicationService(LoanOriginationDomainClient domainClient) {
        this.domainClient = domainClient;
    }

    /**
     * Validates and submits a new loan application via the domain SDK seam.
     *
     * @param request the channel create request
     * @return the created application's channel detail view
     */
    public Mono<ApplicationDetailDTO> createApplication(CreateApplicationRequest request) {
        return Mono.fromCallable(() -> validate(request))
                .flatMap(validated -> {
                    log.debug("Creating application productId={} simulationId={} requestedAmount={} term={}",
                            validated.productId(), validated.simulationId(),
                            validated.requestedAmount(), validated.term());

                    // Idempotency key derived from stable input fields. Same logical request
                    // (retry of the same input) -> same key -> domain dedupes without the
                    // channel minting a resource id.
                    String submitKey = IdempotencyKeys.of(
                            "exp-lending", "create-application", "submit",
                            String.valueOf(validated.productId()),
                            String.valueOf(validated.simulationId()),
                            validated.requestedAmount().toPlainString(),
                            String.valueOf(validated.term()),
                            String.valueOf(validated.purpose()));

                    return domainClient.submitApplication(validated, submitKey)
                            .onErrorMap(this::isNotBusinessException, this::toUpstreamError);
                });
    }

    /**
     * Fetches a single application by id, mapping a missing application to a 404 problem-detail.
     *
     * @param applicationId the application identifier
     * @return the application's channel detail view
     */
    public Mono<ApplicationDetailDTO> getApplication(UUID applicationId) {
        if (applicationId == null) {
            return Mono.error(new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "applicationId is required"));
        }
        log.debug("Getting application applicationId={}", applicationId);
        // Reads are safe to retry; the key is stable for a given id.
        String getKey = IdempotencyKeys.of("exp-lending", "get-application", applicationId.toString());
        return domainClient.getApplication(applicationId, getKey)
                .switchIfEmpty(Mono.error(new BusinessException(
                        HttpStatus.NOT_FOUND, "APPLICATION_NOT_FOUND",
                        "loan application not found: " + applicationId)))
                .onErrorMap(this::isNotBusinessException, this::toUpstreamError);
    }

    private CreateApplicationRequest validate(CreateApplicationRequest request) {
        if (request == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "createApplication request is required");
        }
        if (request.productId() == null) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "productId is required");
        }
        if (request.requestedAmount() == null || request.requestedAmount().signum() <= 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "requestedAmount must be strictly positive");
        }
        if (request.term() == null || request.term() < 1) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED",
                    "term must be at least 1 month");
        }
        return request;
    }

    private boolean isNotBusinessException(Throwable t) {
        return !(t instanceof BusinessException);
    }

    private BusinessException toUpstreamError(Throwable t) {
        return new BusinessException(HttpStatus.BAD_GATEWAY, "UPSTREAM_ERROR",
                "domain loan-origination call failed: " + t.getMessage(), t);
    }
}
