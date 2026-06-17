package com.firefly.lumen.core.web;

import com.firefly.lumen.core.persistence.LoanApplicationRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Deletion endpoint for loan applications, kept in its own controller so the primary
 * {@link LoanApplicationController} (sliced verbatim in the book's first-HTTP-API chapter) stays
 * untouched while the reactor still serves {@code DELETE}.
 *
 * <p>This exists for the domain orchestration tier's saga: when a dependent step fails, the
 * {@code RegisterApplicationSaga} compensates its root {@code registerLoanApplication} step by
 * deleting the application it created. The delete is idempotent — removing an absent id is a no-op
 * — so a compensation retry, or a delete of an already-removed application, still answers
 * {@code 204 No Content}.
 */
@RestController
@RequestMapping("/api/v1/loan-applications")
@RequiredArgsConstructor
@Tag(name = "LoanApplication", description = "Create and retrieve loan applications")
public class LoanApplicationDeleteController {

    private final LoanApplicationRepository repository;

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a loan application",
            description = "Removes an application by its id; idempotent, so a missing id is a no-op.")
    public Mono<Void> delete(@PathVariable UUID id) {
        return repository.deleteById(id);
    }
}
