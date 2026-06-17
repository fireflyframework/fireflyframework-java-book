package com.firefly.lumen.domain.web;

import com.firefly.lumen.domain.saga.RegisterApplicationSaga;
import com.firefly.lumen.domain.service.LoanOriginationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.fireflyframework.orchestration.saga.engine.SagaResult;
import org.fireflyframework.web.error.exceptions.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Domain (orchestration) tier REST surface for loan-application origination.
 *
 * <p>This is the HTTP face of the orchestration tier and the second hop in the live
 * {@code exp → domain → core} flow. It accepts the experience tier's channel-shaped create
 * request, drives the {@link RegisterApplicationSaga} through {@link LoanOriginationService}
 * (whose root step writes to the core system of record over the SDK seam), and returns a channel
 * detail view the BFF maps straight onto its own {@code ApplicationDetailDTO}.
 *
 * <p>The path and JSON contract are deliberately identical to what the experience tier's
 * {@code WebClient} client sends and expects:
 * <ul>
 *   <li>{@code POST /api/v1/applications} — submit a new application (runs the saga).</li>
 *   <li>{@code GET  /api/v1/applications/{id}} — fetch one application back from core.</li>
 * </ul>
 *
 * <p>The real {@code domain-lending-loan-origination} service generates this controller from an
 * OpenAPI contract; the trimmed sample hand-rolls it so the live three-tier flow runs with no
 * generated SDK and no Docker.
 */
@RestController
@RequestMapping("/api/v1/applications")
@Tag(name = "Loan Origination - Applications")
public class LoanOriginationController {

    private static final Logger log = LoggerFactory.getLogger(LoanOriginationController.class);

    /** Default offered rate, in basis points, for the saga's propose-offer step. */
    private static final int DEFAULT_ANNUAL_RATE_BPS = 575;

    private final LoanOriginationService service;
    private final ObjectProvider<CoreLoanApplicationReader> coreReader;

    public LoanOriginationController(LoanOriginationService service,
                                     ObjectProvider<CoreLoanApplicationReader> coreReader) {
        this.service = service;
        this.coreReader = coreReader;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "submitApplication", summary = "Submit Application",
            description = "Runs the RegisterApplicationSaga, writing the application to the core system of record.")
    public Mono<ResponseEntity<ApplicationDetailView>> submit(@RequestBody ApplicationChannelRequest request) {
        long amountMinor = toMinorUnits(request.requestedAmount());
        String applicantName = applicantNameFor(request);
        log.debug("Submitting application productId={} simulationId={} requestedAmount={} term={}",
                request.productId(), request.simulationId(), request.requestedAmount(), request.term());

        return service.submitApplication(applicantName, amountMinor, DEFAULT_ANNUAL_RATE_BPS)
                .flatMap(result -> toDetail(result, request))
                .map(detail -> ResponseEntity.status(HttpStatus.CREATED).body(detail));
    }

    @GetMapping(value = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "getApplication", summary = "Get Application",
            description = "Fetches a single application back from the core system of record.")
    public Mono<ResponseEntity<ApplicationDetailView>> getById(@PathVariable UUID id) {
        CoreLoanApplicationReader reader = coreReader.getIfAvailable();
        if (reader == null) {
            // No live core reader configured (no core base-path): the read path is unavailable.
            return Mono.error(new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, "CORE_UNAVAILABLE",
                    "core loan-origination read path is not configured"));
        }
        return reader.findById(id)
                .map(ResponseEntity::ok)
                .switchIfEmpty(Mono.error(new BusinessException(HttpStatus.NOT_FOUND, "APPLICATION_NOT_FOUND",
                        "loan application not found: " + id)));
    }

    private Mono<ApplicationDetailView> toDetail(SagaResult result, ApplicationChannelRequest request) {
        if (!result.isSuccess()) {
            String reason = result.error().map(Throwable::getMessage).orElse("saga failed");
            return Mono.error(new BusinessException(HttpStatus.BAD_GATEWAY, "ORIGINATION_FAILED",
                    "loan-origination saga failed: " + reason));
        }
        UUID applicationId = result
                .resultOf(RegisterApplicationSaga.STEP_REGISTER_LOAN_APPLICATION, UUID.class)
                .orElseThrow(() -> new BusinessException(HttpStatus.BAD_GATEWAY, "ORIGINATION_FAILED",
                        "saga completed without a loan application id"));
        LocalDateTime now = LocalDateTime.now();
        // The core service submits the application on create, so its status is SUBMITTED. The
        // requested amount / term / purpose / simulation link are echoed back from the request.
        return Mono.just(new ApplicationDetailView(
                applicationId,
                request.simulationId(),
                "SUBMITTED",
                request.requestedAmount(),
                request.term(),
                request.purpose(),
                now,
                now));
    }

    private long toMinorUnits(BigDecimal amount) {
        if (amount == null) {
            return 0L;
        }
        return amount.movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact();
    }

    private String applicantNameFor(ApplicationChannelRequest request) {
        // The channel request carries no applicant name in this trimmed slice; derive a stable
        // placeholder from the product so the core write has a non-blank name.
        return "applicant-" + request.productId();
    }
}
