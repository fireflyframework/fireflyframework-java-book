package com.firefly.lumen.core.web;

import com.firefly.lumen.core.domain.ApplicationStatus;
import com.firefly.lumen.core.dto.CreateLoanApplicationRequest;
import com.firefly.lumen.core.dto.LoanApplicationResponse;
import com.firefly.lumen.core.service.LoanApplicationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Reactive REST endpoint for loan applications.
 *
 * <p>Mirrors the firefly-oss controller style — constructor injection,
 * {@code @Tag}/{@code @Operation} OpenAPI metadata, reactive return types — but
 * returns {@code Mono}/{@code Flux} of DTOs directly. Not-found cases are raised
 * as {@link org.fireflyframework.web.error.exceptions.ResourceNotFoundException}
 * by the service; the framework's global handler turns those into RFC 7807
 * problem-detail responses, so this controller has no error-handling code.
 */
@RestController
@RequestMapping("/api/v1/loan-applications")
@RequiredArgsConstructor
@Tag(name = "LoanApplication", description = "Create and retrieve loan applications")
public class LoanApplicationController {

    private final LoanApplicationService service;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a loan application",
            description = "Validates the request, opens and submits a new application.")
    public Mono<LoanApplicationResponse> create(
            @Valid @RequestBody CreateLoanApplicationRequest request) {
        return service.create(request);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a loan application",
            description = "Fetches a single application by its id; 404 if absent.")
    public Mono<LoanApplicationResponse> getById(@PathVariable UUID id) {
        return service.getById(id);
    }

    @GetMapping
    @Operation(summary = "List loan applications",
            description = "Lists applications, optionally filtered by status.")
    public Flux<LoanApplicationResponse> list(
            @RequestParam(required = false) ApplicationStatus status) {
        return service.list(status);
    }
}
