package com.firefly.lumen.core.service;

import com.firefly.lumen.core.domain.ApplicationStatus;
import com.firefly.lumen.core.domain.LoanApplication;
import com.firefly.lumen.core.dto.CreateLoanApplicationRequest;
import com.firefly.lumen.core.dto.LoanApplicationResponse;
import com.firefly.lumen.core.mapper.LoanApplicationMapper;
import com.firefly.lumen.core.persistence.LoanApplicationRepository;
import lombok.RequiredArgsConstructor;
import org.fireflyframework.web.error.exceptions.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Application service for the loan-origination slice: creates, fetches and lists
 * {@link LoanApplication} aggregates.
 *
 * <p>Follows the firefly-oss service pattern (constructor-injected repository and
 * mapper, {@code @Transactional}, returns reactive types) but throws the
 * framework's {@link ResourceNotFoundException} on a miss so the web layer emits
 * an RFC 7807 problem-detail response automatically — there is no hand-written
 * 404 handling.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class LoanApplicationService {

    private final LoanApplicationRepository repository;
    private final LoanApplicationMapper mapper;

    /**
     * Opens a new application, submits it, persists it, and returns the view.
     *
     * @param request the validated create request
     * @return the persisted application as a response DTO
     */
    public Mono<LoanApplicationResponse> create(CreateLoanApplicationRequest request) {
        LoanApplication application = mapper.toNewEntity(request);
        application.setLoanApplicationId(UUID.randomUUID());
        application.setApplicationNumber(UUID.randomUUID());
        application.setStatus(ApplicationStatus.DRAFT);
        application.setCreatedAt(LocalDateTime.now());
        application.setUpdatedAt(LocalDateTime.now());
        application.submit();
        application.markNew();
        return repository.save(application).map(mapper::toResponse);
    }

    /**
     * Fetches one application by its surrogate id.
     *
     * @param id the loan-application id
     * @return the application
     * @throws ResourceNotFoundException if no application has that id
     */
    @Transactional(readOnly = true)
    public Mono<LoanApplicationResponse> getById(UUID id) {
        return repository.findById(id)
                .map(mapper::toResponse)
                .switchIfEmpty(Mono.error(
                        new ResourceNotFoundException("Loan application not found: " + id)));
    }

    /**
     * Lists applications, optionally filtered by status.
     *
     * @param status status to filter by, or {@code null} for all
     * @return the matching applications
     */
    @Transactional(readOnly = true)
    public Flux<LoanApplicationResponse> list(ApplicationStatus status) {
        Flux<LoanApplication> source = (status == null)
                ? repository.findAll()
                : repository.findByStatus(status);
        return source.map(mapper::toResponse);
    }
}
